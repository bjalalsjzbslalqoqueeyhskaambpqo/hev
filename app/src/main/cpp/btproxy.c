#include <jni.h>
#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>
#include <poll.h>
#include <fcntl.h>

#define LOG_TAG "btproxy"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define T_OPEN  0x01
#define T_DATA  0x02
#define T_CLOSE 0x03
#define T_PING  0x04
#define T_PONG  0x05
#define FRAME_HDR    7
#define MAX_PAYLOAD  16384

#define PROXY_HOST_IPV6   "2606:4700::6812:16b7"
#define PROXY_HOST        "emailmarketing.personal.com.ar"
#define PROXY_PORT        80
#define TUNNEL_HOST       "2.brawlpass.com.ar"

#define SOCKS5_PORT       10809
#define UDP_RELAY_PORT    10810

#define RELAY_BACKLOG         512
#define MAX_WORKERS           64
#define ACCEPT_POLL_MS        100
#define KEEPALIVE_SEC         20
#define PONG_TIMEOUT_SEC      180
#define PING_IDLE_SEC         120
#define IDLE_TRAFFIC_SEC      30
#define RECONNECT_DELAY_MIN   2
#define RECONNECT_DELAY_MAX   30
#define CONNECT_TIMEOUT_SEC   10
#define HANDSHAKE_TIMEOUT_SEC 12

#define HT_SIZE 4096
#define HT_MASK (HT_SIZE-1)

typedef struct stream_s { struct stream_s *next; int fd; uint32_t sid; } stream_t;
static stream_t        *g_ht[HT_SIZE];
static pthread_mutex_t  g_ht_mu[HT_SIZE];

static void ht_init(void) {
    for (int i = 0; i < HT_SIZE; i++) { g_ht[i] = NULL; pthread_mutex_init(&g_ht_mu[i], NULL); }
}
static stream_t *ht_get(uint32_t sid) {
    int s = sid & HT_MASK; pthread_mutex_lock(&g_ht_mu[s]);
    stream_t *n = g_ht[s]; while (n && n->sid != sid) n = n->next;
    pthread_mutex_unlock(&g_ht_mu[s]); return n;
}
static stream_t *ht_put(uint32_t sid, int fd) {
    stream_t *n = malloc(sizeof(stream_t)); if (!n) return NULL;
    n->sid = sid; n->fd = fd;
    int s = sid & HT_MASK; pthread_mutex_lock(&g_ht_mu[s]);
    n->next = g_ht[s]; g_ht[s] = n; pthread_mutex_unlock(&g_ht_mu[s]); return n;
}
static void ht_del(uint32_t sid) {
    int s = sid & HT_MASK; pthread_mutex_lock(&g_ht_mu[s]);
    stream_t **pp = &g_ht[s];
    while (*pp) {
        if ((*pp)->sid == sid) {
            stream_t *n = *pp; *pp = n->next; pthread_mutex_unlock(&g_ht_mu[s]);
            if (n->fd >= 0) close(n->fd); free(n); return;
        }
        pp = &(*pp)->next;
    }
    pthread_mutex_unlock(&g_ht_mu[s]);
}
static void ht_clear(void) {
    for (int i = 0; i < HT_SIZE; i++) {
        pthread_mutex_lock(&g_ht_mu[i]);
        stream_t *n = g_ht[i];
        while (n) { stream_t *nx = n->next; if (n->fd >= 0) close(n->fd); free(n); n = nx; }
        g_ht[i] = NULL; pthread_mutex_unlock(&g_ht_mu[i]);
    }
}

typedef struct task_s { struct task_s *next; int cfd, tfd; uint32_t sid; int open_sent; uint8_t connect_req[512]; int connect_req_len; } task_t;
static pthread_mutex_t g_q_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_q_cv = PTHREAD_COND_INITIALIZER;
static task_t *g_q_head = NULL, *g_q_tail = NULL;

static void q_push(int cfd, int tfd, uint32_t sid, int open_sent, const uint8_t *creq, int creq_len) {
    task_t *t = malloc(sizeof(task_t)); if (!t) { close(cfd); return; }
    t->next = NULL; t->cfd = cfd; t->tfd = tfd; t->sid = sid; t->open_sent = open_sent;
    t->connect_req_len = 0;
    if (!open_sent && creq && creq_len > 0 && creq_len <= (int)sizeof(t->connect_req)) {
        memcpy(t->connect_req, creq, creq_len);
        t->connect_req_len = creq_len;
    }
    pthread_mutex_lock(&g_q_mu);
    if (g_q_tail) g_q_tail->next = t; else g_q_head = t;
    g_q_tail = t; pthread_cond_signal(&g_q_cv); pthread_mutex_unlock(&g_q_mu);
}
static task_t *q_pop(void) {
    pthread_mutex_lock(&g_q_mu);
    while (!g_q_head) pthread_cond_wait(&g_q_cv, &g_q_mu);
    task_t *t = g_q_head; g_q_head = t->next; if (!g_q_head) g_q_tail = NULL;
    pthread_mutex_unlock(&g_q_mu); return t;
}
static void q_clear(void) {
    pthread_mutex_lock(&g_q_mu);
    task_t *t = g_q_head;
    while (t) { task_t *nx = t->next; if (t->cfd >= 0) close(t->cfd); free(t); t = nx; }
    g_q_head = g_q_tail = NULL; pthread_mutex_unlock(&g_q_mu);
}

static volatile int    g_running   = 0;
static volatile int    g_started   = 0;
static int             g_relay_fd  = -1;
static int             g_udp_fd    = -1;
static int             g_tun_tcp   = -1;
static int             g_tun_udp   = -1;
static atomic_int      g_next_sid  = 1;
static atomic_int      g_epoch     = 0;
static atomic_long     g_last_pong    = 0;
static atomic_long     g_last_traffic = 0;
static char            g_iid[160]  = {0};
static int             g_reconnect_delay = RECONNECT_DELAY_MIN;

static struct sockaddr_in g_hev_udp_addr;
static pthread_mutex_t    g_hev_udp_mu = PTHREAD_MUTEX_INITIALIZER;
static int                g_hev_udp_known = 0;

static pthread_t       g_main_thr;
static pthread_t       g_workers[MAX_WORKERS];
static int             g_workers_n = 0;

static pthread_mutex_t g_state_mu    = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_tun_tcp_wmu = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_tun_udp_wmu = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_log_mu      = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_start_mu    = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_start_cv    = PTHREAD_COND_INITIALIZER;
static int             g_start_st    = 0;

static char   g_log_buf[32768];
static size_t g_log_len = 0;
static JavaVM  *g_jvm     = NULL;
static jobject  g_vpn_svc = NULL;

static void push_log(const char *level, const char *fmt, ...) {
    va_list ap; va_start(ap, fmt); char msg[512]; vsnprintf(msg, sizeof(msg), fmt, ap); va_end(ap);
    if (level[0] == 'E') LOGE("%s", msg); else LOGI("%s", msg);
    pthread_mutex_lock(&g_log_mu);
    char line[560]; int n = snprintf(line, sizeof(line), "%s %s\n", level, msg);
    if (n > 0) {
        size_t need = (size_t)n;
        if (g_log_len + need >= sizeof(g_log_buf)) {
            size_t drop = g_log_len + need - (sizeof(g_log_buf) - 1);
            memmove(g_log_buf, g_log_buf + drop, g_log_len - drop); g_log_len -= drop;
        }
        memcpy(g_log_buf + g_log_len, line, need); g_log_len += need; g_log_buf[g_log_len] = '\0';
    }
    pthread_mutex_unlock(&g_log_mu);
}

static void jni_call(const char *method, const char *sig, ...) {
    JavaVM *jvm; jobject svc;
    pthread_mutex_lock(&g_state_mu); jvm = g_jvm; svc = g_vpn_svc; pthread_mutex_unlock(&g_state_mu);
    if (!jvm || !svc) return;
    JNIEnv *env = NULL; int att = 0;
    if ((*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        (*jvm)->AttachCurrentThread(jvm, &env, NULL); att = 1;
    }
    jclass cls = (*env)->GetObjectClass(env, svc);
    jmethodID m = (*env)->GetMethodID(env, cls, method, sig);
    if (m) {
        if (strcmp(sig, "(I)Z") == 0) {
            va_list ap; va_start(ap, sig); int fd = va_arg(ap, int); va_end(ap);
            (*env)->CallBooleanMethod(env, svc, m, fd);
        } else {
            (*env)->CallVoidMethod(env, svc, m);
        }
    }
    (*env)->DeleteLocalRef(env, cls);
    if (att) (*jvm)->DetachCurrentThread(jvm);
}
static void jni_protect(int fd) { jni_call("protect", "(I)Z", fd); }
static void jni_notify_reconnected(void) { jni_call("onTunnelReconnected", "()V"); }

static int tun_tcp_send(uint8_t type, uint32_t sid, const uint8_t *data, uint16_t dlen) {
    uint8_t hdr[FRAME_HDR] = {
        type,
        (sid >> 24) & 0xFF, (sid >> 16) & 0xFF, (sid >> 8) & 0xFF, sid & 0xFF,
        (dlen >> 8) & 0xFF, dlen & 0xFF
    };
    struct iovec iov[2] = {{hdr, FRAME_HDR}, {(void *)data, dlen}};
    int niov = dlen > 0 ? 2 : 1;
    ssize_t total = FRAME_HDR + dlen, sent = 0;
    pthread_mutex_lock(&g_tun_tcp_wmu);
    while (sent < total) {
        ssize_t n = writev(g_tun_tcp, iov, niov);
        if (n > 0) {
            sent += n;
            if (sent < total) {
                size_t skip = (size_t)n;
                for (int i = 0; i < niov && skip > 0; i++) {
                    if (skip >= iov[i].iov_len) { skip -= iov[i].iov_len; iov[i].iov_len = 0; }
                    else { iov[i].iov_base = (uint8_t *)iov[i].iov_base + skip; iov[i].iov_len -= skip; skip = 0; }
                }
            }
        } else if (errno == EINTR) { continue; }
        else if (errno == EAGAIN || errno == EWOULDBLOCK) {
            struct pollfd p = {g_tun_tcp, POLLOUT, 0};
            if (poll(&p, 1, 30) <= 0) { pthread_mutex_unlock(&g_tun_tcp_wmu); return -1; }
        } else { pthread_mutex_unlock(&g_tun_tcp_wmu); return -1; }
    }
    pthread_mutex_unlock(&g_tun_tcp_wmu); return 0;
}

static int tun_udp_send(const uint8_t *data, int dlen) {
    uint8_t lhdr[2] = {(uint8_t)((dlen >> 8) & 0xFF), (uint8_t)(dlen & 0xFF)};
    struct iovec iov[2] = {{lhdr, 2}, {(void *)data, (size_t)dlen}};
    pthread_mutex_lock(&g_tun_udp_wmu);
    ssize_t n = writev(g_tun_udp, iov, 2);
    pthread_mutex_unlock(&g_tun_udp_wmu);
    return (n == (ssize_t)(2 + dlen)) ? 0 : -1;
}

static int tun_recv(int fd, uint8_t *buf, int len, int timeout_ms) {
    int off = 0;
    while (off < len) {
        struct pollfd p = {fd, POLLIN, 0};
        int pr = poll(&p, 1, timeout_ms);
        if (pr < 0) { if (errno == EINTR) continue; return -1; }
        if (pr == 0) return -2;
        ssize_t n = recv(fd, buf + off, len - off, 0);
        if (n > 0) { off += (int)n; }
        else if (n == 0) { return -1; }
        else if (errno == EINTR || errno == EAGAIN) { continue; }
        else { return -1; }
    }
    return 0;
}

static void tunnel_reset(const char *reason) {
    pthread_mutex_lock(&g_state_mu);
    if (!g_running) { pthread_mutex_unlock(&g_state_mu); return; }
    int rfd = g_relay_fd; g_relay_fd = -1;
    int ufd = g_udp_fd;   g_udp_fd   = -1;
    g_tun_tcp = -1; g_tun_udp = -1; g_started = 0;
    pthread_mutex_unlock(&g_state_mu);
    if (reason) push_log("E", "tunnel reset: %s", reason);
    if (rfd >= 0) close(rfd);
    if (ufd >= 0) close(ufd);
    q_clear(); ht_clear();
    pthread_mutex_lock(&g_hev_udp_mu); g_hev_udp_known = 0; pthread_mutex_unlock(&g_hev_udp_mu);
}

static int recv_headers(int fd, char *buf, int cap, int timeout_sec) {
    struct timeval tv = {timeout_sec, 0}; setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    int used = 0;
    while (used < cap - 1) {
        ssize_t n = recv(fd, buf + used, cap - 1 - used, 0); if (n <= 0) break;
        used += (int)n; buf[used] = '\0';
        if (strstr(buf, "\r\n\r\n")) { tv.tv_sec = 0; setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv)); return used; }
    }
    tv.tv_sec = 0; setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv)); return -1;
}
static int http_status(const char *h) { int c = -1; sscanf(h, "HTTP/%*d.%*d %d", &c); return c; }
static int header_val(const char *h, const char *key, char *out, int cap) {
    const char *p = strstr(h, key); if (!p) return 0;
    p += strlen(key); while (*p == ' ' || *p == ':') p++;
    const char *e = strstr(p, "\r\n"); if (!e) return 0;
    int n = (int)(e - p); if (n <= 0 || n >= cap) return 0;
    memcpy(out, p, n); out[n] = '\0'; return 1;
}
static int connect_nb(int fd, const struct sockaddr *addr, socklen_t alen) {
    int fl = fcntl(fd, F_GETFL, 0); fcntl(fd, F_SETFL, fl | O_NONBLOCK);
    int r = connect(fd, addr, alen); fcntl(fd, F_SETFL, fl);
    if (r == 0) return 0; if (errno != EINPROGRESS) return -1;
    struct pollfd p = {fd, POLLOUT, 0}; if (poll(&p, 1, CONNECT_TIMEOUT_SEC * 1000) <= 0) return -1;
    int err = 0; socklen_t el = sizeof(err);
    return (getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &el) == 0 && err == 0) ? 0 : -1;
}

static int open_tunnel(const char *action) {
    int fd = -1;
    struct sockaddr_in6 a6 = {0}; a6.sin6_family = AF_INET6; a6.sin6_port = htons(PROXY_PORT);
    if (inet_pton(AF_INET6, PROXY_HOST_IPV6, &a6.sin6_addr) == 1) {
        fd = socket(AF_INET6, SOCK_STREAM, 0);
        if (fd >= 0) {
            jni_protect(fd); int v = 1; setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &v, sizeof(v));
            int fl = fcntl(fd, F_GETFD, 0); if (fl >= 0) fcntl(fd, F_SETFD, fl | FD_CLOEXEC);
            if (connect_nb(fd, (struct sockaddr *)&a6, sizeof(a6)) != 0) { close(fd); fd = -1; }
        }
    }
    if (fd < 0) {
        struct addrinfo hints = {0}, *res = NULL;
        hints.ai_family = AF_UNSPEC; hints.ai_socktype = SOCK_STREAM;
        char ps[8]; snprintf(ps, sizeof(ps), "%d", PROXY_PORT);
        if (getaddrinfo(PROXY_HOST, ps, &hints, &res) == 0) {
            for (struct addrinfo *r = res; r && fd < 0; r = r->ai_next) {
                int s = socket(r->ai_family, SOCK_STREAM, 0); if (s < 0) continue;
                jni_protect(s); int v = 1; setsockopt(s, IPPROTO_TCP, TCP_NODELAY, &v, sizeof(v));
                int fl = fcntl(s, F_GETFD, 0); if (fl >= 0) fcntl(s, F_SETFD, fl | FD_CLOEXEC);
                if (connect_nb(s, r->ai_addr, r->ai_addrlen) == 0) fd = s; else close(s);
            }
            freeaddrinfo(res);
        }
    }
    if (fd < 0) { push_log("E", "[%s] connect failed", action); return -1; }

    int one = 1, idle = 20, intvl = 5, cnt = 4;
    setsockopt(fd, SOL_SOCKET,  SO_KEEPALIVE,  &one,   sizeof(one));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE,  &idle,  sizeof(idle));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &intvl, sizeof(intvl));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT,   &cnt,   sizeof(cnt));

    char req1[256], h1[2048];
    int r1 = snprintf(req1, sizeof(req1), "GET / HTTP/1.1\r\nHost: %s\r\n\r\n", PROXY_HOST);
    send(fd, req1, r1, MSG_NOSIGNAL);
    if (recv_headers(fd, h1, sizeof(h1), HANDSHAKE_TIMEOUT_SEC) < 0) { close(fd); return -1; }

    char req2[1024], h2[4096]; struct timespec t0, t1;
    int r2 = snprintf(req2, sizeof(req2),
        "- / HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nAction: %s\r\nX-Internal-ID: %s\r\n\r\n",
        TUNNEL_HOST, action, g_iid[0] ? g_iid : "unknown");
    clock_gettime(CLOCK_MONOTONIC, &t0);
    send(fd, req2, r2, MSG_NOSIGNAL);
    int hlen = recv_headers(fd, h2, sizeof(h2), HANDSHAKE_TIMEOUT_SEC);

    if (hlen < 0 || http_status(h2) != 101) {
        char body[512] = {0}; const char *eoh = strstr(h2, "\r\n\r\n");
        if (eoh) { int bl = hlen - (int)(eoh + 4 - h2); if (bl > 0 && bl < (int)sizeof(body)) memcpy(body, eoh + 4, bl); }
        if (strstr(h2, "not_registered") || strstr(body, "not_registered") ||
            strstr(h2, "no_registrado")  || strstr(body, "no_registrado"))
            push_log("E", "usuario no registrado");
        else if (strstr(h2, "expired") || strstr(body, "expired") ||
                 strstr(h2, "expirado") || strstr(body, "expirado"))
            push_log("E", "usuario expirado");
        else push_log("E", "auth error %d", http_status(h2));
        close(fd); return -1;
    }
    clock_gettime(CLOCK_MONOTONIC, &t1);
    long ping_ms = (t1.tv_sec - t0.tv_sec) * 1000L + (t1.tv_nsec - t0.tv_nsec) / 1000000L;
    if (ping_ms < 0) ping_ms = 0;
    if (strcmp(action, "tunnel-tcp") == 0) {
        char uname[128] = {0}, udays[32] = {0};
        if (header_val(h2, "X-User-Name", uname, sizeof(uname))) push_log("I", "user_name=%s", uname);
        if (header_val(h2, "X-User-Days", udays, sizeof(udays))) push_log("I", "user_days=%s", udays);
    }
    push_log("I", "[%s] ping_ms=%ld", action, ping_ms);
    return fd;
}

typedef struct { int cfd; } assoc_arg_t;
static void *udp_associate_thread(void *arg) {
    assoc_arg_t *a = (assoc_arg_t *)arg; int cfd = a->cfd; free(a);
    uint8_t auth[] = {0x05, 0x00}; send(cfd, auth, 2, MSG_NOSIGNAL);
    uint8_t req[32] = {0}; ssize_t n = recv(cfd, req, sizeof(req), 0);
    if (n < 3 || req[0] != 0x05 || req[1] != 0x03) { close(cfd); return NULL; }
    uint8_t reply[10] = {0x05,0x00,0x00,0x01,127,0,0,1,(UDP_RELAY_PORT>>8)&0xFF,UDP_RELAY_PORT&0xFF};
    send(cfd, reply, sizeof(reply), MSG_NOSIGNAL);
    while (g_running) { struct pollfd p = {cfd, POLLIN, 0}; if (poll(&p, 1, 5000) > 0) break; }
    close(cfd); return NULL;
}

static void handle_tcp_conn(int cfd, int tfd, uint32_t sid, int open_already_sent, const uint8_t *creq, int creq_len) {
    (void)tfd;
    uint8_t buf[MAX_PAYLOAD];
    if (!open_already_sent) {
        stream_t *s = ht_put(sid, cfd);
        if (!s) { close(cfd); return; }
        int frame_len = 0;
        if (creq && creq_len > 0 && creq_len <= MAX_PAYLOAD) {
            memcpy(buf, creq, creq_len);
            frame_len = creq_len;
        }
        ssize_t n = recv(cfd, buf + frame_len, MAX_PAYLOAD - frame_len, 0);
        if (n <= 0) { ht_del(sid); return; }
        frame_len += (int)n;
        atomic_store(&g_last_traffic, (long)time(NULL));
        if (tun_tcp_send(T_OPEN, sid, buf, (uint16_t)frame_len) < 0) { tunnel_reset("T_OPEN failed"); ht_del(sid); return; }
    }
    while (g_running) {
        ssize_t n = recv(cfd, buf, MAX_PAYLOAD, 0);
        if (n < 0) { if (errno == EINTR) continue; break; }
        if (n == 0) break;
        atomic_store(&g_last_traffic, (long)time(NULL));
        if (tun_tcp_send(T_DATA, sid, buf, (uint16_t)n) < 0) { tunnel_reset("T_DATA failed"); break; }
    }
    tun_tcp_send(T_CLOSE, sid, NULL, 0);
    ht_del(sid);
}

static void *worker(void *arg) {
    (void)arg;
    while (g_running) {
        task_t *t = q_pop(); if (!t) continue;
        handle_tcp_conn(t->cfd, t->tfd, t->sid, t->open_sent, t->connect_req, t->connect_req_len);
        free(t);
    }
    return NULL;
}

typedef struct { int tfd; int epoch; } reader_arg_t;
static void *tcp_tunnel_reader(void *arg) {
    reader_arg_t *ra = (reader_arg_t *)arg; int tfd = ra->tfd, epoch = ra->epoch; free(ra);
    uint8_t hdr[FRAME_HDR], payload[MAX_PAYLOAD];
    while (g_running && atomic_load(&g_epoch) == epoch) {
        int rc = tun_recv(tfd, hdr, FRAME_HDR, 60000);
        if (!g_running || atomic_load(&g_epoch) != epoch) break;
        if (rc == -2) { if ((long)time(NULL) - atomic_load(&g_last_pong) > PONG_TIMEOUT_SEC) { tunnel_reset("pong timeout"); break; } continue; }
        if (rc < 0) { tunnel_reset("header read error"); break; }
        uint8_t  ft  = hdr[0];
        uint32_t sid = ((uint32_t)hdr[1] << 24) | ((uint32_t)hdr[2] << 16) | ((uint32_t)hdr[3] << 8) | (uint32_t)hdr[4];
        uint16_t len = ((uint16_t)hdr[5] << 8) | hdr[6];
        if (len > MAX_PAYLOAD) { tunnel_reset("payload too large"); break; }
        if (len > 0 && tun_recv(tfd, payload, len, 30000) < 0) { tunnel_reset("payload read error"); break; }
        switch (ft) {
        case T_DATA: {
            stream_t *s = ht_get(sid); if (!s || len == 0) break;
            atomic_store(&g_last_traffic, (long)time(NULL));
            ssize_t off = 0;
            while (off < len) {
                ssize_t n = send(s->fd, payload + off, len - off, MSG_NOSIGNAL | MSG_DONTWAIT);
                if (n > 0) { off += n; continue; } break;
            }
            break;
        }
        case T_CLOSE: ht_del(sid); break;
        case T_PING:  tun_tcp_send(T_PONG, 0, NULL, 0); break;
        case T_PONG:  atomic_store(&g_last_pong, (long)time(NULL)); break;
        default: break;
        }
    }
    return NULL;
}

typedef struct { int tfd; int epoch; } udp_srv_arg_t;
static void *udp_server_reader(void *arg) {
    udp_srv_arg_t *ra = (udp_srv_arg_t *)arg; int tfd = ra->tfd, epoch = ra->epoch; free(ra);
    uint8_t lhdr[2], payload[MAX_PAYLOAD];
    while (g_running && atomic_load(&g_epoch) == epoch) {
        int rc = tun_recv(tfd, lhdr, 2, 5000);
        if (!g_running || atomic_load(&g_epoch) != epoch) break;
        if (rc == -2) continue;
        if (rc < 0) break;
        uint16_t dlen = ((uint16_t)lhdr[0] << 8) | lhdr[1];
        if (dlen == 0 || dlen > MAX_PAYLOAD) break;
        if (tun_recv(tfd, payload, dlen, 5000) < 0) break;
        pthread_mutex_lock(&g_hev_udp_mu);
        int known = g_hev_udp_known; struct sockaddr_in addr = g_hev_udp_addr;
        pthread_mutex_unlock(&g_hev_udp_mu);
        int ufd = g_udp_fd;
        if (known && ufd >= 0) sendto(ufd, payload, dlen, MSG_NOSIGNAL, (struct sockaddr *)&addr, sizeof(addr));
    }
    return NULL;
}

static void *udp_relay_thread(void *arg) {
    (void)arg; uint8_t buf[MAX_PAYLOAD];
    while (g_running) {
        int ufd = g_udp_fd; if (ufd < 0) { usleep(10000); continue; }
        struct pollfd p = {ufd, POLLIN, 0}; int pr = poll(&p, 1, 1000);
        if (!g_running) break; if (pr <= 0) continue;
        struct sockaddr_in src = {0}; socklen_t sl = sizeof(src);
        ssize_t n = recvfrom(ufd, buf, sizeof(buf), 0, (struct sockaddr *)&src, &sl);
        if (n <= 0) continue;
        atomic_store(&g_last_traffic, (long)time(NULL));
        pthread_mutex_lock(&g_hev_udp_mu);
        if (!g_hev_udp_known) { g_hev_udp_addr = src; g_hev_udp_known = 1; }
        pthread_mutex_unlock(&g_hev_udp_mu);
        if (g_tun_udp >= 0) tun_udp_send(buf, (int)n);
    }
    return NULL;
}

static void *keepalive(void *arg) {
    reader_arg_t *ra = (reader_arg_t *)arg; int epoch = ra->epoch; free(ra);
    long last_ping = 0;
    while (g_running && atomic_load(&g_epoch) == epoch) {
        sleep(1); if (!g_running || atomic_load(&g_epoch) != epoch) break;
        long now = (long)time(NULL);
        if (now - atomic_load(&g_last_pong) > PONG_TIMEOUT_SEC) { tunnel_reset("pong timeout"); break; }
        int idle = (now - atomic_load(&g_last_traffic)) > IDLE_TRAFFIC_SEC;
        if (now - last_ping < (idle ? PING_IDLE_SEC : KEEPALIVE_SEC)) continue;
        struct timespec tp0, tp1; clock_gettime(CLOCK_MONOTONIC, &tp0);
        long prev = atomic_load(&g_last_pong);
        if (tun_tcp_send(T_PING, 0, NULL, 0) < 0) { tunnel_reset("ping failed"); break; }
        for (int us = 5000; us <= 3000000 && g_running; us = us < 50000 ? us * 2 : 50000) {
            if (atomic_load(&g_last_pong) != prev) break; usleep(5000);
        }
        clock_gettime(CLOCK_MONOTONIC, &tp1);
        long rtt = (tp1.tv_sec - tp0.tv_sec) * 1000L + (tp1.tv_nsec - tp0.tv_nsec) / 1000000L;
        if (rtt > 0) push_log("I", "ping_ms=%ld", rtt);
        last_ping = now;
    }
    return NULL;
}

static void *main_thread(void *arg) {
    int port = (int)(intptr_t)arg; static int first = 1;

    for (int i = 0; i < MAX_WORKERS; i++) {
        if (pthread_create(&g_workers[i], NULL, worker, NULL) == 0) g_workers_n++; else break;
    }
    pthread_t udp_thr; pthread_create(&udp_thr, NULL, udp_relay_thread, NULL); pthread_detach(udp_thr);

    while (g_running) {
        int tfd_tcp = open_tunnel("tunnel-tcp");
        int tfd_udp = open_tunnel("tunnel-udp");
        if (tfd_tcp < 0 || tfd_udp < 0) {
            if (tfd_tcp >= 0) close(tfd_tcp); if (tfd_udp >= 0) close(tfd_udp);
            pthread_mutex_lock(&g_start_mu);
            if (g_start_st == 0) { g_start_st = -1; pthread_cond_broadcast(&g_start_cv); }
            pthread_mutex_unlock(&g_start_mu);
            if (!g_running) break;
            push_log("E", "reconnect in %ds", g_reconnect_delay);
            for (int i = 0; i < g_reconnect_delay && g_running; i++) sleep(1);
            g_reconnect_delay = g_reconnect_delay < RECONNECT_DELAY_MAX ? g_reconnect_delay * 2 : RECONNECT_DELAY_MAX;
            continue;
        }
        g_reconnect_delay = RECONNECT_DELAY_MIN; g_tun_tcp = tfd_tcp; g_tun_udp = tfd_udp;

        int rfd = socket(AF_INET, SOCK_STREAM, 0);
        if (rfd < 0) { close(tfd_tcp); close(tfd_udp); g_tun_tcp = -1; g_tun_udp = -1; sleep(1); continue; }
        int one = 1; setsockopt(rfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
        int v = 1; setsockopt(rfd, IPPROTO_TCP, TCP_NODELAY, &v, sizeof(v));
        int fl = fcntl(rfd, F_GETFD, 0); if (fl >= 0) fcntl(rfd, F_SETFD, fl | FD_CLOEXEC);
        struct sockaddr_in la = {0}; la.sin_family = AF_INET; la.sin_port = htons((uint16_t)port); la.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        if (bind(rfd, (struct sockaddr *)&la, sizeof(la)) < 0 || listen(rfd, RELAY_BACKLOG) < 0) {
            close(rfd); close(tfd_tcp); close(tfd_udp); g_relay_fd = -1; g_tun_tcp = -1; g_tun_udp = -1;
            pthread_mutex_lock(&g_start_mu);
            if (g_start_st == 0) { g_start_st = -1; pthread_cond_broadcast(&g_start_cv); }
            pthread_mutex_unlock(&g_start_mu);
            push_log("E", "relay bind failed"); sleep(2); continue;
        }

        int ufd = socket(AF_INET, SOCK_DGRAM, 0);
        if (ufd < 0) { close(rfd); close(tfd_tcp); close(tfd_udp); sleep(1); continue; }
        setsockopt(ufd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
        fl = fcntl(ufd, F_GETFD, 0); if (fl >= 0) fcntl(ufd, F_SETFD, fl | FD_CLOEXEC);
        struct sockaddr_in ula = {0}; ula.sin_family = AF_INET; ula.sin_port = htons(UDP_RELAY_PORT); ula.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        if (bind(ufd, (struct sockaddr *)&ula, sizeof(ula)) < 0) {
            close(ufd); close(rfd); close(tfd_tcp); close(tfd_udp); push_log("E", "udp bind failed"); sleep(2); continue;
        }

        int epoch = atomic_fetch_add(&g_epoch, 1) + 1;
        g_relay_fd = rfd; g_udp_fd = ufd; g_started = 1;
        push_log("I", "relay ready tcp:%d udp:%d", port, UDP_RELAY_PORT);
        pthread_mutex_lock(&g_start_mu);
        if (g_start_st == 0) { g_start_st = 1; pthread_cond_broadcast(&g_start_cv); }
        pthread_mutex_unlock(&g_start_mu);
        if (!first) jni_notify_reconnected(); else first = 0;

        reader_arg_t *ra_rd = malloc(sizeof(reader_arg_t));
        reader_arg_t *ra_ka = malloc(sizeof(reader_arg_t));
        udp_srv_arg_t *ra_ur = malloc(sizeof(udp_srv_arg_t));
        if (ra_rd) { ra_rd->tfd = tfd_tcp; ra_rd->epoch = epoch; }
        if (ra_ka) { ra_ka->tfd = tfd_tcp; ra_ka->epoch = epoch; }
        if (ra_ur) { ra_ur->tfd = tfd_udp; ra_ur->epoch = epoch; }
        pthread_t trd, tka, tur;
        if (ra_rd) { pthread_create(&trd, NULL, tcp_tunnel_reader, ra_rd); pthread_detach(trd); } else free(ra_rd);
        if (ra_ka) { pthread_create(&tka, NULL, keepalive,          ra_ka); pthread_detach(tka); } else free(ra_ka);
        if (ra_ur) { pthread_create(&tur, NULL, udp_server_reader,  ra_ur); pthread_detach(tur); } else free(ra_ur);

        while (g_running) {
            struct pollfd pfd = {rfd, POLLIN, 0};
            int pr = poll(&pfd, 1, ACCEPT_POLL_MS);
            if (!g_running) break;
            if (pr < 0) { if (errno == EINTR) continue; tunnel_reset("accept poll failed"); break; }
            if (pr > 0 && (pfd.revents & (POLLERR | POLLHUP | POLLNVAL))) break;
            if (pr == 0) {
                pthread_mutex_lock(&g_state_mu);
                int same = (g_tun_tcp == tfd_tcp && g_relay_fd == rfd);
                pthread_mutex_unlock(&g_state_mu);
                if (!same) break; continue;
            }
            struct sockaddr_in ca; socklen_t cl = sizeof(ca);
            int cfd = accept(rfd, (struct sockaddr *)&ca, &cl);
            if (cfd < 0) { if (errno == EINTR || errno == EAGAIN) continue; tunnel_reset("accept failed"); break; }
            int nd = 1; setsockopt(cfd, IPPROTO_TCP, TCP_NODELAY, &nd, sizeof(nd));
            int cf = fcntl(cfd, F_GETFD, 0); if (cf >= 0) fcntl(cfd, F_SETFD, cf | FD_CLOEXEC);

            uint8_t buf[512] = {0};
            ssize_t bn = recv(cfd, buf, sizeof(buf), 0);
            if (bn < 2 || buf[0] != 0x05) { close(cfd); continue; }

            uint8_t no_auth[] = {0x05, 0x00}; send(cfd, no_auth, 2, MSG_NOSIGNAL);

            int nmethods = (int)buf[1];
            int req_off  = 2 + nmethods;
            uint8_t req[512] = {0}; ssize_t rn;
            if (bn > req_off) {
                rn = bn - req_off;
                if (rn > (ssize_t)sizeof(req)) rn = sizeof(req);
                memcpy(req, buf + req_off, rn);
            } else {
                rn = recv(cfd, req, sizeof(req), 0);
            }
            if (rn < 4 || req[0] != 0x05) { close(cfd); continue; }

            if (req[1] == 0x03) {
                uint8_t udp_reply[10] = {0x05,0x00,0x00,0x01,127,0,0,1,(UDP_RELAY_PORT>>8)&0xFF,UDP_RELAY_PORT&0xFF};
                send(cfd, udp_reply, sizeof(udp_reply), MSG_NOSIGNAL);
                assoc_arg_t *aa = malloc(sizeof(assoc_arg_t));
                if (aa) { aa->cfd = cfd; pthread_t at; pthread_create(&at, NULL, udp_associate_thread, aa); pthread_detach(at); }
                else close(cfd);
            } else {
                int socks_hdr = 4;
                if      (req[3] == 0x01) socks_hdr = 10;
                else if (req[3] == 0x03 && rn > 4) socks_hdr = 5 + (int)req[4] + 2;
                else if (req[3] == 0x04) socks_hdr = 22;

                uint8_t conn_reply[10] = {0x05,0x00,0x00,0x01,127,0,0,1,0x00,0x00};
                send(cfd, conn_reply, sizeof(conn_reply), MSG_NOSIGNAL);

                uint32_t sid;
                do { sid = (uint32_t)atomic_fetch_add(&g_next_sid, 1) & 0x7FFFFFFF; }
                while (sid == 0 || ht_get(sid));

                int payload_off = socks_hdr;
                int payload_len = (int)rn - payload_off;
                int open_sent   = 0;

                if (payload_len > 0 && payload_off >= 0 && payload_off <= (int)rn) {
                    stream_t *s = ht_put(sid, cfd);
                    if (!s) { close(cfd); continue; }
                    atomic_store(&g_last_traffic, (long)time(NULL));
                    if (tun_tcp_send(T_OPEN, sid, req + payload_off, (uint16_t)payload_len) < 0) {
                        tunnel_reset("T_OPEN pipeline failed"); ht_del(sid); continue;
                    }
                    open_sent = 1;
                }
                q_push(cfd, tfd_tcp, sid, open_sent, open_sent ? NULL : req, open_sent ? 0 : (int)rn);
            }
        }
        tunnel_reset(NULL);
        if (g_running) { push_log("E", "tunnel dropped, reconnecting"); sleep(1); }
    }
    pthread_mutex_lock(&g_q_mu); pthread_cond_broadcast(&g_q_cv); pthread_mutex_unlock(&g_q_mu);
    for (int i = 0; i < g_workers_n; i++) pthread_join(g_workers[i], NULL);
    g_workers_n = 0; g_started = 0;
    return NULL;
}

JNIEXPORT void JNICALL Java_com_blacktunnel_BtProxy_nativeStop(JNIEnv *env, jclass clazz);

JNIEXPORT jint JNICALL
Java_com_blacktunnel_BtProxy_nativeStart(JNIEnv *env, jclass clazz, jint port, jobject svc, jstring iid) {
    pthread_mutex_lock(&g_state_mu);
    if (g_running) { pthread_mutex_unlock(&g_state_mu); return 0; }
    (*env)->GetJavaVM(env, &g_jvm); g_vpn_svc = (*env)->NewGlobalRef(env, svc); g_iid[0] = '\0';
    if (iid) { const char *s = (*env)->GetStringUTFChars(env, iid, NULL); if (s) { snprintf(g_iid, sizeof(g_iid), "%s", s); (*env)->ReleaseStringUTFChars(env, iid, s); } }
    ht_init(); g_running = 1; g_started = 0; g_reconnect_delay = RECONNECT_DELAY_MIN;
    atomic_store(&g_next_sid, 1); atomic_store(&g_last_traffic, (long)time(NULL));
    pthread_mutex_unlock(&g_state_mu);
    pthread_mutex_lock(&g_start_mu); g_start_st = 0; pthread_mutex_unlock(&g_start_mu);
    if (pthread_create(&g_main_thr, NULL, main_thread, (void *)(intptr_t)port) != 0) {
        pthread_mutex_lock(&g_state_mu); g_running = 0;
        (*env)->DeleteGlobalRef(env, g_vpn_svc); g_vpn_svc = NULL; g_jvm = NULL;
        pthread_mutex_unlock(&g_state_mu); return -1;
    }
    pthread_detach(g_main_thr);
    struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts); ts.tv_sec += 12;
    pthread_mutex_lock(&g_start_mu);
    while (g_start_st == 0) if (pthread_cond_timedwait(&g_start_cv, &g_start_mu, &ts) != 0) break;
    int st = g_start_st; pthread_mutex_unlock(&g_start_mu);
    if (st != 1) { Java_com_blacktunnel_BtProxy_nativeStop(env, clazz); return -1; }
    push_log("I", "nativeStart ok"); return 0;
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeStop(JNIEnv *env, jclass clazz) {
    (void)clazz;
    pthread_mutex_lock(&g_state_mu);
    if (!g_running) { pthread_mutex_unlock(&g_state_mu); return; }
    g_running = 0; g_iid[0] = '\0';
    jobject svc = g_vpn_svc; g_vpn_svc = NULL; g_jvm = NULL;
    pthread_mutex_unlock(&g_state_mu);
    atomic_fetch_add(&g_epoch, 1);
    if (g_relay_fd >= 0) { shutdown(g_relay_fd, SHUT_RDWR); close(g_relay_fd); g_relay_fd = -1; }
    if (g_udp_fd   >= 0) { close(g_udp_fd); g_udp_fd = -1; }
    if (g_tun_tcp  >= 0) { shutdown(g_tun_tcp, SHUT_RDWR); close(g_tun_tcp); g_tun_tcp = -1; }
    if (g_tun_udp  >= 0) { shutdown(g_tun_udp, SHUT_RDWR); close(g_tun_udp); g_tun_udp = -1; }
    pthread_mutex_lock(&g_q_mu); pthread_cond_broadcast(&g_q_cv); pthread_mutex_unlock(&g_q_mu);
    pthread_mutex_lock(&g_start_mu);
    if (g_start_st == 0) { g_start_st = -1; pthread_cond_broadcast(&g_start_cv); }
    pthread_mutex_unlock(&g_start_mu);
    for (int i = 0; i < 20 && g_started; i++) usleep(10000);
    ht_clear(); if (svc) (*env)->DeleteGlobalRef(env, svc); g_started = 0;
}

JNIEXPORT void JNICALL Java_com_blacktunnel_BtProxy_nativeSetGamingMode(JNIEnv *e, jclass c, jboolean en) { (void)e; (void)c; (void)en; }
JNIEXPORT void JNICALL Java_com_blacktunnel_BtProxy_nativeApplyMode(JNIEnv *e, jclass c, jboolean en) { (void)e; (void)c; (void)en; q_clear(); ht_clear(); push_log("I", "mode applied"); }
JNIEXPORT jstring JNICALL Java_com_blacktunnel_BtProxy_nativeDrainLogs(JNIEnv *env, jclass clazz) {
    (void)clazz;
    pthread_mutex_lock(&g_log_mu);
    if (g_log_len == 0) { pthread_mutex_unlock(&g_log_mu); return (*env)->NewStringUTF(env, ""); }
    char out[32768]; memcpy(out, g_log_buf, g_log_len); out[g_log_len] = '\0';
    g_log_len = 0; g_log_buf[0] = '\0'; pthread_mutex_unlock(&g_log_mu);
    return (*env)->NewStringUTF(env, out);
}
