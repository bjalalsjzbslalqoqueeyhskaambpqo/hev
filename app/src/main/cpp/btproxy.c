#include <jni.h>
#include <android/log.h>
#include <android/multinetwork.h>
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

#define FRAME_HDR             7
#define MAX_PAYLOAD           16384
#define RELAY_BACKLOG         512
#define PONG_TIMEOUT_SEC      180
#define RECONNECT_DELAY_MIN   2
#define RECONNECT_DELAY_MAX   30
#define CONNECT_TIMEOUT_SEC   10
#define HANDSHAKE_TIMEOUT_SEC 1
#define CONN_WORKERS          64
#define HT_SIZE               4096
#define HT_MASK               (HT_SIZE - 1)
#define SNDBUF_GAMING         32768

#define PROXY_HOST_IPV6  "2606:4700::6812:16b7"
#define PROXY_HOST       "emailmarketing.personal.com.ar"
#define PROXY_PORT       80
#define TUNNEL_HOST      "2.brawlpass.com.ar"

#define MODE_DAILY  0
#define MODE_GAMING 1

static atomic_int g_mode   = MODE_DAILY;
static int        g_sndbuf = 262144;

static int detect_sndbuf(void) {
    FILE *f = fopen("/proc/meminfo", "r");
    if (!f) return 262144;
    char line[128]; long kb = 0;
    while (fgets(line, sizeof(line), f))
        if (strncmp(line, "MemTotal:", 9) == 0) { sscanf(line+9, " %ld", &kb); break; }
    fclose(f);
    long g10 = (kb * 10L) / (1024L * 1024L);
    if (g10 <= 35) return 65536;
    if (g10 <= 55) return 131072;
    return 262144;
}

typedef struct stream_s {
    struct stream_s *next;
    int fd, pfd[2];
    uint32_t sid;
} stream_t;

typedef struct conn_task_s {
    struct conn_task_s *next;
    int cfd, tfd;
    uint32_t sid;
} conn_task_t;

static stream_t       *g_ht[HT_SIZE];
static pthread_mutex_t g_ht_mu[HT_SIZE];
static int             g_ht_inited = 0;

static void ht_init(void) {
    for (int i = 0; i < HT_SIZE; i++) {
        g_ht[i] = NULL;
        if (!g_ht_inited) pthread_mutex_init(&g_ht_mu[i], NULL);
    }
    g_ht_inited = 1;
}

static stream_t *ht_get(uint32_t sid) {
    int slot = sid & HT_MASK;
    pthread_mutex_lock(&g_ht_mu[slot]);
    stream_t *s = g_ht[slot];
    while (s && s->sid != sid) s = s->next;
    pthread_mutex_unlock(&g_ht_mu[slot]);
    return s;
}

static stream_t *ht_put(uint32_t sid, int fd) {
    stream_t *s = malloc(sizeof(*s));
    if (!s) return NULL;
    if (pipe(s->pfd) < 0) { free(s); return NULL; }
    fcntl(s->pfd[1], F_SETFL, O_NONBLOCK);
#ifdef F_SETPIPE_SZ
    fcntl(s->pfd[0], F_SETPIPE_SZ,
          atomic_load(&g_mode) == MODE_GAMING ? 65536 : g_sndbuf);
#endif
    s->sid = sid; s->fd = fd; s->next = NULL;
    int slot = sid & HT_MASK;
    pthread_mutex_lock(&g_ht_mu[slot]);
    s->next = g_ht[slot]; g_ht[slot] = s;
    pthread_mutex_unlock(&g_ht_mu[slot]);
    return s;
}

static void ht_del(uint32_t sid) {
    int slot = sid & HT_MASK;
    pthread_mutex_lock(&g_ht_mu[slot]);
    stream_t **pp = &g_ht[slot];
    while (*pp) {
        if ((*pp)->sid == sid) {
            stream_t *s = *pp; *pp = s->next;
            pthread_mutex_unlock(&g_ht_mu[slot]);
            close(s->fd); close(s->pfd[0]); close(s->pfd[1]);
            free(s); return;
        }
        pp = &(*pp)->next;
    }
    pthread_mutex_unlock(&g_ht_mu[slot]);
}

static void ht_clear(void) {
    for (int i = 0; i < HT_SIZE; i++) {
        pthread_mutex_lock(&g_ht_mu[i]);
        stream_t *s = g_ht[i];
        while (s) {
            stream_t *nx = s->next;
            close(s->fd); close(s->pfd[0]); close(s->pfd[1]);
            free(s); s = nx;
        }
        g_ht[i] = NULL;
        pthread_mutex_unlock(&g_ht_mu[i]);
    }
}

static volatile int    g_running   = 0;
static volatile int    g_ready     = 0;
static volatile int    g_main_done = 1;
static int             g_relay_fd  = -1;
static int             g_tun_fd    = -1;
static atomic_int      g_next_sid  = 1;
static atomic_int      g_tunnel_epoch = 0;
static atomic_long     g_last_pong = 0;
static char            g_internal_id[160] = {0};
static JavaVM         *g_jvm = NULL;
static jobject         g_svc = NULL;
static net_handle_t    g_net = NETWORK_UNSPECIFIED;
static pthread_mutex_t g_mu      = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_wmu     = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_log_mu  = PTHREAD_MUTEX_INITIALIZER;
static char            g_logbuf[32768];
static size_t          g_loglen = 0;
static pthread_mutex_t g_ready_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_ready_cv = PTHREAD_COND_INITIALIZER;
static int             g_ready_st = 0;
static pthread_mutex_t g_done_mu  = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_done_cv  = PTHREAD_COND_INITIALIZER;
static pthread_mutex_t g_qmu  = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_qcv  = PTHREAD_COND_INITIALIZER;
static conn_task_t    *g_qhead = NULL, *g_qtail = NULL;
static pthread_t       g_workers[CONN_WORKERS];
static int             g_nworkers = 0;

static void push_log(const char *lvl, const char *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    char msg[512]; vsnprintf(msg, sizeof(msg), fmt, ap); va_end(ap);
    if (lvl[0] == 'E') LOGE("%s", msg); else LOGI("%s", msg);
    pthread_mutex_lock(&g_log_mu);
    char line[560];
    int n = snprintf(line, sizeof(line), "%s %s\n", lvl, msg);
    if (n > 0) {
        if (g_loglen + n >= sizeof(g_logbuf)) {
            size_t drop = g_loglen + n - sizeof(g_logbuf) + 1;
            memmove(g_logbuf, g_logbuf + drop, g_loglen - drop);
            g_loglen -= drop;
        }
        memcpy(g_logbuf + g_loglen, line, n);
        g_loglen += n; g_logbuf[g_loglen] = 0;
    }
    pthread_mutex_unlock(&g_log_mu);
}

static void protect_fd(int fd) {
    pthread_mutex_lock(&g_mu);
    net_handle_t net = g_net;
    JavaVM *jvm = g_jvm; jobject svc = g_svc;
    pthread_mutex_unlock(&g_mu);
    if (net != NETWORK_UNSPECIFIED) android_setsocknetwork(net, fd);
    if (!jvm || !svc) return;
    JNIEnv *env = NULL; int att = 0;
    if ((*jvm)->GetEnv(jvm, (void**)&env, JNI_VERSION_1_6) != JNI_OK)
        { (*jvm)->AttachCurrentThread(jvm, &env, NULL); att = 1; }
    jclass cls = (*env)->GetObjectClass(env, svc);
    jmethodID m = (*env)->GetMethodID(env, cls, "protect", "(I)Z");
    if (m) (*env)->CallBooleanMethod(env, svc, m, fd);
    (*env)->DeleteLocalRef(env, cls);
    if (att) (*jvm)->DetachCurrentThread(jvm);
}

static void notify_reconnected(void) {
    pthread_mutex_lock(&g_mu);
    JavaVM *jvm = g_jvm; jobject svc = g_svc;
    pthread_mutex_unlock(&g_mu);
    if (!jvm || !svc) return;
    JNIEnv *env = NULL; int att = 0;
    if ((*jvm)->GetEnv(jvm, (void**)&env, JNI_VERSION_1_6) != JNI_OK)
        { (*jvm)->AttachCurrentThread(jvm, &env, NULL); att = 1; }
    jclass cls = (*env)->GetObjectClass(env, svc);
    jmethodID m = (*env)->GetMethodID(env, cls, "onTunnelReconnected", "()V");
    if (m) (*env)->CallVoidMethod(env, svc, m);
    (*env)->DeleteLocalRef(env, cls);
    if (att) (*jvm)->DetachCurrentThread(jvm);
}

static void reset_tunnel(const char *reason) {
    int rfd, tfd;
    pthread_mutex_lock(&g_mu);
    if (!g_running) { pthread_mutex_unlock(&g_mu); return; }
    rfd = g_relay_fd; g_relay_fd = -1;
    tfd = g_tun_fd;   g_tun_fd   = -1;
    g_ready = 0;
    pthread_mutex_unlock(&g_mu);
    if (reason) push_log("E", "reset: %s", reason);
    if (rfd >= 0) close(rfd);
    if (tfd >= 0) close(tfd);
    pthread_mutex_lock(&g_qmu);
    conn_task_t *t = g_qhead; g_qhead = g_qtail = NULL;
    pthread_mutex_unlock(&g_qmu);
    while (t) { conn_task_t *nx = t->next; close(t->cfd); free(t); t = nx; }
    ht_clear();
}

static void tune_tun(int fd) {
    int v;
    v = 1;      setsockopt(fd, IPPROTO_TCP, TCP_NODELAY,   &v, sizeof(v));
    v = 1;      setsockopt(fd, IPPROTO_TCP, TCP_QUICKACK,  &v, sizeof(v));
    v = 0x10;   setsockopt(fd, IPPROTO_IP,  IP_TOS,        &v, sizeof(v));
    v = 262144; setsockopt(fd, SOL_SOCKET,  SO_SNDBUF,     &v, sizeof(v));
    v = 262144; setsockopt(fd, SOL_SOCKET,  SO_RCVBUF,     &v, sizeof(v));
    v = 1;      setsockopt(fd, SOL_SOCKET,  SO_KEEPALIVE,  &v, sizeof(v));
    int mode = atomic_load(&g_mode);
    int idle = (mode == MODE_GAMING) ? 10 : 30;
    int intvl = (mode == MODE_GAMING) ? 3 : 5;
    int cnt = 3;
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE,  &idle,  sizeof(idle));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &intvl, sizeof(intvl));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT,   &cnt,   sizeof(cnt));
    int fl = fcntl(fd, F_GETFD, 0); if (fl >= 0) fcntl(fd, F_SETFD, fl | FD_CLOEXEC);
}

static void tune_local(int fd) {
    int v = 1; setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &v, sizeof(v));
    v = (atomic_load(&g_mode) == MODE_GAMING) ? SNDBUF_GAMING : g_sndbuf;
    setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &v, sizeof(v));
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &v, sizeof(v));
    int fl = fcntl(fd, F_GETFD, 0); if (fl >= 0) fcntl(fd, F_SETFD, fl | FD_CLOEXEC);
}

static int nb_connect(int fd, const struct sockaddr *a, socklen_t l, int sec) {
    int fl = fcntl(fd, F_GETFL, 0); fcntl(fd, F_SETFL, fl | O_NONBLOCK);
    int r = connect(fd, a, l);
    if (r == 0) { fcntl(fd, F_SETFL, fl); return 0; }
    if (errno != EINPROGRESS) { fcntl(fd, F_SETFL, fl); return -1; }
    struct pollfd p = {fd, POLLOUT, 0};
    int pr = poll(&p, 1, sec * 1000); fcntl(fd, F_SETFL, fl);
    if (pr <= 0) return -1;
    int e = 0; socklen_t el = sizeof(e);
    return (getsockopt(fd, SOL_SOCKET, SO_ERROR, &e, &el) < 0 || e) ? -1 : 0;
}

static int tun_send(int fd, uint8_t type, uint32_t sid,
                    const uint8_t *data, uint16_t dlen) {
    uint8_t hdr[FRAME_HDR];
    hdr[0] = type;
    hdr[1] = (sid >> 24) & 0xFF; hdr[2] = (sid >> 16) & 0xFF;
    hdr[3] = (sid >>  8) & 0xFF; hdr[4] =  sid        & 0xFF;
    hdr[5] = (dlen >> 8) & 0xFF; hdr[6] =  dlen       & 0xFF;
    struct iovec iov[2] = {{hdr, FRAME_HDR}, {(void*)data, dlen}};
    int niov = dlen ? 2 : 1;
    ssize_t total = FRAME_HDR + dlen, sent = 0;
    while (sent < total) {
        pthread_mutex_lock(&g_wmu);
        ssize_t n = writev(fd, iov, niov);
        if (n > 0) {
            pthread_mutex_unlock(&g_wmu); sent += n;
            if (sent < total) {
                size_t skip = n;
                for (int i = 0; i < niov && skip > 0; i++) {
                    if (skip >= iov[i].iov_len) { skip -= iov[i].iov_len; iov[i].iov_len = 0; }
                    else { iov[i].iov_base = (uint8_t*)iov[i].iov_base + skip; iov[i].iov_len -= skip; skip = 0; }
                }
            }
        } else if (errno == EINTR) {
            pthread_mutex_unlock(&g_wmu);
        } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
            pthread_mutex_unlock(&g_wmu);
            struct pollfd wp = {fd, POLLOUT, 0};
            if (poll(&wp, 1, (atomic_load(&g_mode) == MODE_GAMING) ? 5 : 30) <= 0) return -1;
        } else {
            pthread_mutex_unlock(&g_wmu); return -1;
        }
    }
    return 0;
}

static int recv_full(int fd, uint8_t *buf, int len, int ms) {
    int off = 0;
    while (off < len) {
        struct pollfd p = {fd, POLLIN, 0};
        int pr = poll(&p, 1, ms);
        if (pr < 0) { if (errno == EINTR) continue; return -1; }
        if (pr == 0) return -2;
        ssize_t n = recv(fd, buf + off, len - off, 0);
        if (n > 0) off += n;
        else if (n == 0) return -1;
        else if (errno == EINTR || errno == EAGAIN) continue;
        else return -1;
    }
    return 0;
}

static int recv_eoh(int fd, char *buf, int cap, int sec) {
    struct timeval tv = {sec, 0};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    int used = 0, ok = 0;
    while (used < cap - 1) {
        ssize_t n = recv(fd, buf + used, cap - 1 - used, 0);
        if (n <= 0) break;
        used += n; buf[used] = 0;
        if (strstr(buf, "\r\n\r\n")) { ok = 1; break; }
    }
    tv.tv_sec = 0; setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    return ok ? used : -1;
}

static void parse_header(const char *buf, const char *key, char *out, int cap) {
    const char *p = strstr(buf, key);
    if (!p) return;
    p += strlen(key);
    while (*p == ' ' || *p == ':') p++;
    const char *e = strstr(p, "\r\n");
    if (!e) return;
    int n = (int)(e - p);
    if (n <= 0 || n >= cap) return;
    memcpy(out, p, n); out[n] = 0;
}

static int open_tunnel(void) {
    int fd = -1;
    struct sockaddr_in6 a6 = {0};
    a6.sin6_family = AF_INET6; a6.sin6_port = htons(PROXY_PORT);
    if (inet_pton(AF_INET6, PROXY_HOST_IPV6, &a6.sin6_addr) == 1) {
        fd = socket(AF_INET6, SOCK_STREAM, 0);
        if (fd >= 0) {
            protect_fd(fd); tune_tun(fd);
            if (nb_connect(fd, (struct sockaddr*)&a6, sizeof(a6), CONNECT_TIMEOUT_SEC) != 0)
                { close(fd); fd = -1; }
        }
    }
    if (fd < 0) {
        struct addrinfo hints = {0}, *res = NULL;
        hints.ai_family = AF_UNSPEC; hints.ai_socktype = SOCK_STREAM;
        char ps[8]; snprintf(ps, sizeof(ps), "%d", PROXY_PORT);
        if (getaddrinfo(PROXY_HOST, ps, &hints, &res) == 0) {
            for (struct addrinfo *r = res; r && fd < 0; r = r->ai_next) {
                int s = socket(r->ai_family, SOCK_STREAM, 0);
                if (s < 0) continue;
                protect_fd(s); tune_tun(s);
                if (nb_connect(s, r->ai_addr, r->ai_addrlen, CONNECT_TIMEOUT_SEC) == 0) fd = s;
                else close(s);
            }
            freeaddrinfo(res);
        }
    }
    if (fd < 0) { push_log("E", "tunnel connect failed"); return -1; }

    atomic_store(&g_last_pong, (long)time(NULL));

    char req1[256], h1[2048];
    int r1 = snprintf(req1, sizeof(req1), "GET / HTTP/1.1\r\nHost: %s\r\n\r\n", PROXY_HOST);
    send(fd, req1, r1, MSG_NOSIGNAL);
    if (recv_eoh(fd, h1, sizeof(h1), HANDSHAKE_TIMEOUT_SEC) < 0) { close(fd); return -1; }

    char req2[1024], h2[4096];
    int r2 = snprintf(req2, sizeof(req2),
        "- / HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\n"
        "Connection: Upgrade\r\nAction: tunnel\r\nX-Internal-ID: %s\r\n\r\n",
        TUNNEL_HOST, g_internal_id[0] ? g_internal_id : "unknown");
    send(fd, req2, r2, MSG_NOSIGNAL);
    int hlen = recv_eoh(fd, h2, sizeof(h2), HANDSHAKE_TIMEOUT_SEC);

    int code = -1; sscanf(h2, "HTTP/%*d.%*d %d", &code);
    if (hlen < 0 || code != 101) {
        if (code == 403) {
            char body[256] = {0}; recv(fd, body, sizeof(body)-1, MSG_DONTWAIT);
            if (strstr(h2, "not_registered") || strstr(body, "not_registered"))
                push_log("E", "usuario no registrado");
            else if (strstr(h2, "expired") || strstr(body, "expired"))
                push_log("E", "usuario expirado");
            else push_log("E", "error 403");
        } else push_log("E", "handshake failed code=%d", code);
        close(fd); return -1;
    }

    char uname[128] = {0}, udays[32] = {0};
    parse_header(h2, "X-User-Name:", uname, sizeof(uname));
    parse_header(h2, "X-User-Days:", udays, sizeof(udays));
    if (uname[0]) push_log("I", "user=%s days=%s", uname, udays);
    push_log("I", "tunnel ok");
    return fd;
}

static void conn_handle(int cfd, int tfd, uint32_t sid) {
    stream_t *s = ht_put(sid, cfd);
    if (!s) { close(cfd); return; }

    uint8_t buf[MAX_PAYLOAD];
    ssize_t first = recv(cfd, buf, sizeof(buf), 0);
    if (first <= 0) { ht_del(sid); return; }

    if (tun_send(tfd, T_OPEN, sid, buf, (uint16_t)first) < 0) {
        reset_tunnel("T_OPEN failed"); ht_del(sid); return;
    }

    int gaming = (atomic_load(&g_mode) == MODE_GAMING), done = 0;
    struct pollfd pfds[2] = {{cfd, POLLIN, 0}, {s->pfd[0], POLLIN, 0}};

    while (g_running) {
        int pr = poll(pfds, 2, done ? 200 : (gaming ? 100 : 500));
        if (pr < 0) { if (errno == EINTR) continue; break; }
        if (pfds[0].revents & (POLLERR|POLLHUP|POLLNVAL)) break;
        if (pfds[1].revents & (POLLERR|POLLHUP|POLLNVAL)) break;

        int o[2] = {gaming ? 1 : 0, gaming ? 0 : 1};
        for (int i = 0; i < 2; i++) {
            int idx = o[i];
            if (!(pfds[idx].revents & POLLIN)) continue;
            if (idx == 1) {
                ssize_t n = read(s->pfd[0], buf, sizeof(buf));
                if (n > 0) {
                    ssize_t off = 0;
                    while (off < n) {
                        ssize_t w = send(cfd, buf+off, n-off, MSG_NOSIGNAL);
                        if (w > 0) { off += w; continue; }
                        if (errno == EINTR) continue;
                        goto done;
                    }
                } else if (n == 0 || (errno != EAGAIN && errno != EINTR)) goto done;
            } else {
                if (done) continue;
                ssize_t n = recv(cfd, buf, sizeof(buf), 0);
                if (n < 0) { if (errno == EINTR) continue; goto done; }
                if (n == 0) {
                    shutdown(cfd, SHUT_RD);
                    tun_send(tfd, T_CLOSE, sid, NULL, 0);
                    done = 1; pfds[0].events = 0; continue;
                }
                if (tun_send(tfd, T_DATA, sid, buf, (uint16_t)n) < 0)
                    { reset_tunnel("T_DATA failed"); goto done; }
            }
        }
        if (done && pr == 0) break;
    }
done:
    ht_del(sid);
}

static void qpush(int cfd, int tfd, uint32_t sid) {
    conn_task_t *t = malloc(sizeof(*t));
    if (!t) { close(cfd); return; }
    t->cfd = cfd; t->tfd = tfd; t->sid = sid; t->next = NULL;
    pthread_mutex_lock(&g_qmu);
    if (g_qtail) g_qtail->next = t; else g_qhead = t;
    g_qtail = t;
    pthread_cond_signal(&g_qcv);
    pthread_mutex_unlock(&g_qmu);
}

static void *worker(void *arg) {
    (void)arg;
    while (1) {
        pthread_mutex_lock(&g_qmu);
        while (g_running && !g_qhead) pthread_cond_wait(&g_qcv, &g_qmu);
        conn_task_t *t = g_qhead;
        if (t) { g_qhead = t->next; if (!g_qhead) g_qtail = NULL; }
        pthread_mutex_unlock(&g_qmu);
        if (!t) break;
        conn_handle(t->cfd, t->tfd, t->sid);
        free(t);
    }
    return NULL;
}

typedef struct { int tfd, epoch; } thr_t;

static void *tunnel_reader(void *arg) {
    thr_t *ta = (thr_t*)arg; int tfd = ta->tfd, epoch = ta->epoch; free(ta);
    uint8_t hdr[FRAME_HDR], payload[MAX_PAYLOAD];
    while (g_running && atomic_load(&g_tunnel_epoch) == epoch) {
        int rc = recv_full(tfd, hdr, FRAME_HDR, 60000);
        if (!g_running || atomic_load(&g_tunnel_epoch) != epoch) break;
        if (rc == -2) {
            if (atomic_load(&g_last_pong) > 0 &&
                (long)time(NULL) - atomic_load(&g_last_pong) > PONG_TIMEOUT_SEC)
                { reset_tunnel("pong timeout"); break; }
            continue;
        }
        if (rc < 0) { reset_tunnel("header read failed"); break; }
        uint8_t ft = hdr[0];
        uint32_t sid = ((uint32_t)hdr[1]<<24)|((uint32_t)hdr[2]<<16)|
                       ((uint32_t)hdr[3]<<8)|(uint32_t)hdr[4];
        uint16_t len = ((uint16_t)hdr[5]<<8)|hdr[6];
        if (len > MAX_PAYLOAD) { reset_tunnel("payload too large"); break; }
        if (len > 0 && recv_full(tfd, payload, len, 30000) < 0)
            { reset_tunnel("payload read failed"); break; }
        switch (ft) {
        case T_DATA: {
            stream_t *s = ht_get(sid); if (!s || !len) break;
            for (int off = 0; off < len;) {
                ssize_t n = write(s->pfd[1], payload+off, len-off);
                if (n > 0) { off += n; continue; }
                if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) break;
                break;
            }
            break;
        }
        case T_CLOSE: ht_del(sid); break;
        case T_PING:  tun_send(tfd, T_PONG, 0, NULL, 0); break;
        case T_PONG:  atomic_store(&g_last_pong, (long)time(NULL)); break;
        }
    }
    return NULL;
}

static void *keepalive(void *arg) {
    thr_t *ta = (thr_t*)arg; int tfd = ta->tfd, epoch = ta->epoch; free(ta);
    atomic_store(&g_last_pong, (long)time(NULL));
    int interval = (atomic_load(&g_mode) == MODE_GAMING) ? 5 : 20;
    long last = time(NULL);
    while (g_running && atomic_load(&g_tunnel_epoch) == epoch) {
        sleep(1);
        if (!g_running || atomic_load(&g_tunnel_epoch) != epoch) break;
        long now = time(NULL), pong = atomic_load(&g_last_pong);
        if (pong > 0 && now - pong > PONG_TIMEOUT_SEC)
            { reset_tunnel("pong timeout"); break; }
        if (now - last < interval) continue;
        last = now;
        struct timespec t0, t1; clock_gettime(CLOCK_MONOTONIC, &t0);
        if (tun_send(tfd, T_PING, 0, NULL, 0) < 0) { reset_tunnel("ping failed"); break; }
        long prev_pong = pong;
        for (int us = 5000, el = 0; el < 3000000 && g_running; el += us) {
            if (atomic_load(&g_last_pong) > prev_pong) break;
            usleep(us); if (us < 50000) us *= 2;
        }
        clock_gettime(CLOCK_MONOTONIC, &t1);
        long rtt = (t1.tv_sec-t0.tv_sec)*1000 + (t1.tv_nsec-t0.tv_nsec)/1000000;
        if (rtt > 0) push_log("I", "ping_ms=%ld", rtt);
    }
    return NULL;
}

static void *main_thread(void *arg) {
    int port = (int)(intptr_t)arg;
    int reconnect_delay = RECONNECT_DELAY_MIN;
    int is_first = 1;
    g_main_done = 0;

    for (int i = 0; i < CONN_WORKERS; i++)
        pthread_create(&g_workers[i], NULL, worker, NULL);
    g_nworkers = CONN_WORKERS;

    while (g_running) {
        int tfd = open_tunnel();
        if (tfd < 0) {
            pthread_mutex_lock(&g_ready_mu);
            if (g_ready_st == 0) { g_ready_st = -1; pthread_cond_broadcast(&g_ready_cv); }
            pthread_mutex_unlock(&g_ready_mu);
            if (!g_running) break;
            push_log("E", "retry in %ds", reconnect_delay);
            for (int i = 0; i < reconnect_delay && g_running; i++) sleep(1);
            if (reconnect_delay < RECONNECT_DELAY_MAX) reconnect_delay *= 2;
            if (reconnect_delay > RECONNECT_DELAY_MAX) reconnect_delay = RECONNECT_DELAY_MAX;
            continue;
        }
        reconnect_delay = RECONNECT_DELAY_MIN;

        int rfd = socket(AF_INET, SOCK_STREAM, 0);
        if (rfd < 0) { close(tfd); sleep(1); continue; }
        int one = 1;
        setsockopt(rfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
        setsockopt(rfd, SOL_SOCKET, SO_REUSEPORT, &one, sizeof(one));
        int fl = fcntl(rfd, F_GETFD, 0); if (fl >= 0) fcntl(rfd, F_SETFD, fl | FD_CLOEXEC);
        struct sockaddr_in la = {0};
        la.sin_family = AF_INET; la.sin_port = htons((uint16_t)port);
        la.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        if (bind(rfd, (struct sockaddr*)&la, sizeof(la)) < 0 || listen(rfd, RELAY_BACKLOG) < 0) {
            close(rfd); close(tfd);
            pthread_mutex_lock(&g_ready_mu);
            if (g_ready_st == 0) { g_ready_st = -1; pthread_cond_broadcast(&g_ready_cv); }
            pthread_mutex_unlock(&g_ready_mu);
            push_log("E", "relay bind failed"); sleep(2); continue;
        }

        pthread_mutex_lock(&g_mu);
        g_tun_fd = tfd; g_relay_fd = rfd; g_ready = 1;
        pthread_mutex_unlock(&g_mu);

        int epoch = atomic_fetch_add(&g_tunnel_epoch, 1) + 1;
        push_log("I", "relay port=%d epoch=%d", port, epoch);

        pthread_mutex_lock(&g_ready_mu);
        if (g_ready_st == 0) { g_ready_st = 1; pthread_cond_broadcast(&g_ready_cv); }
        pthread_mutex_unlock(&g_ready_mu);

        if (!is_first) notify_reconnected();
        is_first = 0;

        thr_t *ta = malloc(sizeof(*ta)); if (ta) { ta->tfd = tfd; ta->epoch = epoch; }
        thr_t *tb = malloc(sizeof(*tb)); if (tb) { tb->tfd = tfd; tb->epoch = epoch; }
        pthread_t tr, tk;
        if (ta) { pthread_create(&tr, NULL, tunnel_reader, ta); pthread_detach(tr); } else free(ta);
        if (tb) { pthread_create(&tk, NULL, keepalive, tb); pthread_detach(tk); } else free(tb);

        while (g_running) {
            struct pollfd pfd = {rfd, POLLIN, 0};
            int pr = poll(&pfd, 1, 1000);
            if (!g_running) break;
            if (pr < 0) { if (errno == EINTR) continue; reset_tunnel("poll failed"); break; }
            if (pr > 0 && (pfd.revents & (POLLERR|POLLHUP|POLLNVAL))) break;
            if (pr == 0) {
                pthread_mutex_lock(&g_mu);
                int same = (g_tun_fd == tfd && g_relay_fd == rfd);
                pthread_mutex_unlock(&g_mu);
                if (!same) break;
                continue;
            }
            struct sockaddr_in ca; socklen_t cl = sizeof(ca);
            int cfd = accept(rfd, (struct sockaddr*)&ca, &cl);
            if (cfd < 0) {
                if (!g_running) break;
                if (errno == EINTR || errno == EAGAIN) continue;
                reset_tunnel("accept failed"); break;
            }
            tune_local(cfd);
            uint32_t sid;
            do { sid = (uint32_t)atomic_fetch_add(&g_next_sid, 1) & 0x7FFFFFFF; }
            while (!sid || ht_get(sid));
            qpush(cfd, tfd, sid);
        }

        reset_tunnel(NULL);
        if (g_running) { push_log("E", "tunnel dropped, reconnecting"); sleep(1); }
    }

    pthread_mutex_lock(&g_qmu);
    pthread_cond_broadcast(&g_qcv);
    pthread_mutex_unlock(&g_qmu);
    for (int i = 0; i < g_nworkers; i++) pthread_join(g_workers[i], NULL);
    g_nworkers = 0;
    ht_clear();
    push_log("I", "main_thread done");
    pthread_mutex_lock(&g_done_mu);
    g_main_done = 1;
    pthread_cond_broadcast(&g_done_cv);
    pthread_mutex_unlock(&g_done_mu);
    return NULL;
}

static void do_shutdown(JNIEnv *env) {
    pthread_mutex_lock(&g_mu);
    if (!g_running) { pthread_mutex_unlock(&g_mu); return; }
    g_running = 0;
    g_internal_id[0] = 0;
    jobject svc = g_svc; g_svc = NULL; g_jvm = NULL;
    int rfd = g_relay_fd; g_relay_fd = -1;
    int tfd = g_tun_fd;   g_tun_fd   = -1;
    pthread_mutex_unlock(&g_mu);

    atomic_fetch_add(&g_tunnel_epoch, 1);
    if (rfd >= 0) { shutdown(rfd, SHUT_RDWR); close(rfd); }
    if (tfd >= 0) { shutdown(tfd, SHUT_RDWR); close(tfd); }

    pthread_mutex_lock(&g_qmu);
    pthread_cond_broadcast(&g_qcv);
    conn_task_t *t = g_qhead; g_qhead = g_qtail = NULL;
    pthread_mutex_unlock(&g_qmu);
    while (t) { conn_task_t *nx = t->next; close(t->cfd); free(t); t = nx; }

    pthread_mutex_lock(&g_ready_mu);
    g_ready_st = -1; pthread_cond_broadcast(&g_ready_cv);
    pthread_mutex_unlock(&g_ready_mu);

    pthread_mutex_lock(&g_done_mu);
    if (!g_main_done) {
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_sec += 6;
        while (!g_main_done)
            if (pthread_cond_timedwait(&g_done_cv, &g_done_mu, &ts) != 0) break;
    }
    pthread_mutex_unlock(&g_done_mu);

    ht_clear();
    if (svc && env) (*env)->DeleteGlobalRef(env, svc);
    g_ready = 0;
    push_log("I", "shutdown done");
}

JNIEXPORT jint JNICALL
Java_com_blacktunnel_BtProxy_nativeStart(JNIEnv *env, jclass clazz,
                                          jint port, jobject svc, jstring iid) {
    pthread_mutex_lock(&g_mu);
    if (g_running) { pthread_mutex_unlock(&g_mu); return 0; }
    (*env)->GetJavaVM(env, &g_jvm);
    g_svc = (*env)->NewGlobalRef(env, svc);
    g_internal_id[0] = 0;
    if (iid) {
        const char *s = (*env)->GetStringUTFChars(env, iid, NULL);
        if (s) { snprintf(g_internal_id, sizeof(g_internal_id), "%s", s);
                 (*env)->ReleaseStringUTFChars(env, iid, s); }
    }
    g_sndbuf = detect_sndbuf();
    ht_init();
    g_running = 1; g_ready = 0;
    atomic_store(&g_next_sid, 1);
    pthread_mutex_unlock(&g_mu);

    pthread_mutex_lock(&g_ready_mu); g_ready_st = 0; pthread_mutex_unlock(&g_ready_mu);

    pthread_t thr;
    if (pthread_create(&thr, NULL, main_thread, (void*)(intptr_t)port) != 0) {
        pthread_mutex_lock(&g_mu); g_running = 0;
        (*env)->DeleteGlobalRef(env, g_svc); g_svc = NULL; g_jvm = NULL;
        pthread_mutex_unlock(&g_mu);
        return -1;
    }
    pthread_detach(thr);

    struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts); ts.tv_sec += 12;
    pthread_mutex_lock(&g_ready_mu);
    while (g_ready_st == 0)
        if (pthread_cond_timedwait(&g_ready_cv, &g_ready_mu, &ts) != 0) break;
    int st = g_ready_st;
    pthread_mutex_unlock(&g_ready_mu);

    if (st != 1) { do_shutdown(env); return -1; }
    push_log("I", "nativeStart ok");
    return 0;
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeStop(JNIEnv *env, jclass clazz) {
    do_shutdown(env);
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeSetGamingMode(JNIEnv *env, jclass c, jboolean en) {
    atomic_store(&g_mode, en ? MODE_GAMING : MODE_DAILY);
    push_log("I", "mode=%s", en ? "gaming" : "daily");
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeApplyMode(JNIEnv *env, jclass c, jboolean en) {
    atomic_store(&g_mode, en ? MODE_GAMING : MODE_DAILY);
    pthread_mutex_lock(&g_qmu);
    conn_task_t *t = g_qhead; g_qhead = g_qtail = NULL;
    pthread_mutex_unlock(&g_qmu);
    while (t) { conn_task_t *nx = t->next; close(t->cfd); free(t); t = nx; }
    ht_clear();
    push_log("I", "apply_mode=%s", en ? "gaming" : "daily");
}

JNIEXPORT jint JNICALL
Java_com_blacktunnel_BtProxy_nativeGetGamingMode(JNIEnv *env, jclass c) {
    return atomic_load(&g_mode);
}

JNIEXPORT jstring JNICALL
Java_com_blacktunnel_BtProxy_nativeDrainLogs(JNIEnv *env, jclass c) {
    pthread_mutex_lock(&g_log_mu);
    if (!g_loglen) { pthread_mutex_unlock(&g_log_mu); return (*env)->NewStringUTF(env, ""); }
    char out[32768]; memcpy(out, g_logbuf, g_loglen); out[g_loglen] = 0;
    g_loglen = 0; g_logbuf[0] = 0;
    pthread_mutex_unlock(&g_log_mu);
    return (*env)->NewStringUTF(env, out);
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeSetNetwork(JNIEnv *env, jclass c, jlong net) {
    pthread_mutex_lock(&g_mu); g_net = (net_handle_t)net; pthread_mutex_unlock(&g_mu);
}

static JNINativeMethod g_methods[] = {
    {"nativeStart",        "(ILandroid/net/VpnService;Ljava/lang/String;)I",
                           (void*)Java_com_blacktunnel_BtProxy_nativeStart},
    {"nativeStop",         "()V",
                           (void*)Java_com_blacktunnel_BtProxy_nativeStop},
    {"nativeDrainLogs",    "()Ljava/lang/String;",
                           (void*)Java_com_blacktunnel_BtProxy_nativeDrainLogs},
    {"nativeSetGamingMode","(Z)V",
                           (void*)Java_com_blacktunnel_BtProxy_nativeSetGamingMode},
    {"nativeApplyMode",    "(Z)V",
                           (void*)Java_com_blacktunnel_BtProxy_nativeApplyMode},
    {"nativeGetGamingMode","()I",
                           (void*)Java_com_blacktunnel_BtProxy_nativeGetGamingMode},
    {"nativeSetNetwork",   "(J)V",
                           (void*)Java_com_blacktunnel_BtProxy_nativeSetNetwork},
};

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *r) {
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = (*env)->FindClass(env, "com/blacktunnel/BtProxy");
    if (!cls) return JNI_ERR;
    if ((*env)->RegisterNatives(env, cls, g_methods,
            sizeof(g_methods)/sizeof(g_methods[0])) < 0) return JNI_ERR;
    (*env)->DeleteLocalRef(env, cls);
    return JNI_VERSION_1_6;
}
