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

#define T_DATA  0x02
#define T_CLOSE 0x03
#define T_PING  0x04
#define T_PONG  0x05
#define T_OPEN  0x01

#define FRAME_HDR               7
#define MAX_PAYLOAD             16384
#define RELAY_BACKLOG           512
#define KEEPALIVE_SEC           20
#define PONG_TIMEOUT_SEC        180
#define RECONNECT_DELAY_MIN     2
#define RECONNECT_DELAY_MAX     30
#define CONNECT_TIMEOUT_SEC     10
#define HANDSHAKE_TIMEOUT_SEC   12

#define HT_SIZE  4096
#define HT_MASK  (HT_SIZE - 1)

#define PROXY_HOST_IPV6 "2606:4700::6812:16b7"
#define PROXY_HOST      "emailmarketing.personal.com.ar"
#define PROXY_PORT      80
#define TUNNEL_HOST     "2.brawlpass.com.ar"

#define GLOBAL_MODE_DAILY   0
#define GLOBAL_MODE_GAMING  1

#define DAILY_ACCEPT_POLL_MS    200
#define GAMING_ACCEPT_POLL_MS   50

#define PING_ACTIVE_SEC         30
#define PING_IDLE_SEC           600
#define IDLE_TRAFFIC_SEC        10
#define DAILY_CONN_WORKERS      32
#define GAMING_CONN_WORKERS     64

static atomic_int g_global_mode = GLOBAL_MODE_DAILY;

static int current_accept_poll_ms(void) {
    return atomic_load(&g_global_mode) == GLOBAL_MODE_GAMING
            ? GAMING_ACCEPT_POLL_MS
            : DAILY_ACCEPT_POLL_MS;
}

typedef struct stream_s {
    struct stream_s *next;
    int              fd;
    atomic_long      last_active;
    uint32_t         sid;
} stream_t;

static stream_t       *g_ht[HT_SIZE];
static pthread_mutex_t g_ht_mu[HT_SIZE];

static void ht_init(void) {
    for (int i = 0; i < HT_SIZE; i++) {
        g_ht[i] = NULL;
        pthread_mutex_init(&g_ht_mu[i], NULL);
    }
}

static stream_t *ht_get(uint32_t sid) {
    int slot = (int)(sid & HT_MASK);
    pthread_mutex_lock(&g_ht_mu[slot]);
    stream_t *s = g_ht[slot];
    while (s && s->sid != sid) s = s->next;
    pthread_mutex_unlock(&g_ht_mu[slot]);
    return s;
}

static stream_t *ht_put(uint32_t sid, int fd) {
    stream_t *s = malloc(sizeof(stream_t));
    if (!s) return NULL;
    memset(s, 0, sizeof(stream_t));
    s->sid = sid;
    s->fd  = fd;
    atomic_store(&s->last_active, (long)time(NULL));
    int slot = (int)(sid & HT_MASK);
    pthread_mutex_lock(&g_ht_mu[slot]);
    s->next = g_ht[slot]; g_ht[slot] = s;
    pthread_mutex_unlock(&g_ht_mu[slot]);
    return s;
}

static int ht_del(uint32_t sid) {
    int slot = (int)(sid & HT_MASK);
    pthread_mutex_lock(&g_ht_mu[slot]);
    stream_t **pp = &g_ht[slot];
    while (*pp) {
        if ((*pp)->sid == sid) {
            stream_t *s = *pp; *pp = s->next;
            pthread_mutex_unlock(&g_ht_mu[slot]);
            if (s->fd >= 0) close(s->fd);
            free(s);
            return 1;
        }
        pp = &(*pp)->next;
    }
    pthread_mutex_unlock(&g_ht_mu[slot]);
    return 0;
}

static void ht_clear(void) {
    for (int i = 0; i < HT_SIZE; i++) {
        pthread_mutex_lock(&g_ht_mu[i]);
        stream_t *s = g_ht[i];
        while (s) {
            stream_t *nx = s->next;
            if (s->fd >= 0) close(s->fd);
            free(s); s = nx;
        }
        g_ht[i] = NULL;
        pthread_mutex_unlock(&g_ht_mu[i]);
    }
}

static volatile int    g_running   = 0;
static volatile int    g_started   = 0;
static int             g_relay_fd  = -1;
static int             g_tun_fd    = -1;
static atomic_int      g_next_sid  = 1;
static char            g_internal_id[160] = {0};
static pthread_t       g_main_thr;
static JavaVM         *g_jvm       = NULL;
static jobject         g_vpn_svc   = NULL;
static pthread_mutex_t g_state_mu  = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_tun_wmu   = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_log_mu    = PTHREAD_MUTEX_INITIALIZER;
static char            g_log_buf[32768];
static size_t          g_log_len   = 0;
static pthread_mutex_t g_start_mu  = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_start_cv  = PTHREAD_COND_INITIALIZER;
static int             g_start_st  = 0;
static atomic_long     g_last_pong = 0;
static atomic_int      g_tunnel_epoch = 0;
static atomic_long     g_last_traffic = 0;

typedef struct conn_task_s {
    struct conn_task_s *next;
    int cfd;
    int tfd;
    uint32_t sid;
} conn_task_t;

static pthread_mutex_t g_connq_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_connq_cv = PTHREAD_COND_INITIALIZER;
static conn_task_t    *g_connq_head = NULL;
static conn_task_t    *g_connq_tail = NULL;
static pthread_t       g_conn_workers[GAMING_CONN_WORKERS];
static int             g_conn_workers_started = 0;

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeStop(JNIEnv *env, jclass clazz);
static void connq_clear(void);

static void push_log(const char *level, const char *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    char msg[512]; vsnprintf(msg, sizeof(msg), fmt, ap); va_end(ap);
    if (level[0] == 'E') LOGE("%s", msg); else LOGI("%s", msg);
    pthread_mutex_lock(&g_log_mu);
    char line[560];
    int n = snprintf(line, sizeof(line), "%s %s\n", level, msg);
    if (n > 0) {
        size_t need = (size_t)n;
        if (g_log_len + need >= sizeof(g_log_buf)) {
            size_t drop = g_log_len + need - (sizeof(g_log_buf) - 1);
            memmove(g_log_buf, g_log_buf + drop, g_log_len - drop);
            g_log_len -= drop;
        }
        memcpy(g_log_buf + g_log_len, line, need);
        g_log_len += need;
        g_log_buf[g_log_len] = '\0';
    }
    pthread_mutex_unlock(&g_log_mu);
}

static void notify_tunnel_reconnected(void) {
    JavaVM *jvm = NULL; jobject svc = NULL;
    pthread_mutex_lock(&g_state_mu);
    jvm = g_jvm; svc = g_vpn_svc;
    pthread_mutex_unlock(&g_state_mu);
    if (!jvm || !svc) return;
    JNIEnv *env = NULL; int att = 0;
    if ((*jvm)->GetEnv(jvm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        (*jvm)->AttachCurrentThread(jvm, &env, NULL); att = 1;
    }
    jclass cls = (*env)->GetObjectClass(env, svc);
    jmethodID m = (*env)->GetMethodID(env, cls, "onTunnelReconnected", "()V");
    if (m) (*env)->CallVoidMethod(env, svc, m);
    (*env)->DeleteLocalRef(env, cls);
    if (att) (*jvm)->DetachCurrentThread(jvm);
}

static void request_tunnel_reset(const char *reason) {
    int rfd = -1, tfd = -1;
    pthread_mutex_lock(&g_state_mu);
    if (!g_running) { pthread_mutex_unlock(&g_state_mu); return; }
    rfd = g_relay_fd; g_relay_fd = -1;
    tfd = g_tun_fd;   g_tun_fd   = -1;
    g_started = 0;
    pthread_mutex_unlock(&g_state_mu);
    if (reason) push_log("E", "tunnel reset: %s", reason);
    if (rfd >= 0) close(rfd);
    if (tfd >= 0) close(tfd);
    connq_clear();
    ht_clear();
}

static void sock_tune(int fd) {
    int gaming = (atomic_load(&g_global_mode) == GLOBAL_MODE_GAMING);
    int v;
    v = 1;                       setsockopt(fd, IPPROTO_TCP, TCP_NODELAY,  &v, sizeof(v));
    v = gaming ? 1 : 0;          setsockopt(fd, IPPROTO_TCP, TCP_QUICKACK, &v, sizeof(v));
    v = gaming ? 131072 : 65536; setsockopt(fd, SOL_SOCKET,  SO_RCVBUF,   &v, sizeof(v));
    v = gaming ? 131072 : 65536; setsockopt(fd, SOL_SOCKET,  SO_SNDBUF,   &v, sizeof(v));
    int flags = fcntl(fd, F_GETFD, 0);
    if (flags >= 0) fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
}

static void sock_keepalive(int fd) {
    int one=1, idle=20, intvl=5, cnt=4;
    setsockopt(fd, SOL_SOCKET,  SO_KEEPALIVE,  &one,   sizeof(one));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE,  &idle,  sizeof(idle));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &intvl, sizeof(intvl));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT,   &cnt,   sizeof(cnt));
}

static int set_nonblocking(int fd, int nb) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) return -1;
    if (nb) flags |= O_NONBLOCK;
    else    flags &= ~O_NONBLOCK;
    return fcntl(fd, F_SETFL, flags);
}

static int connect_with_timeout(int fd, const struct sockaddr *addr, socklen_t addrlen, int timeout_sec) {
    set_nonblocking(fd, 1);
    int r = connect(fd, addr, addrlen);
    if (r == 0) { set_nonblocking(fd, 0); return 0; }
    if (errno != EINPROGRESS) { set_nonblocking(fd, 0); return -1; }
    struct pollfd pfd = { fd, POLLOUT, 0 };
    int pr = poll(&pfd, 1, timeout_sec * 1000);
    set_nonblocking(fd, 0);
    if (pr <= 0) return -1;
    int err = 0; socklen_t elen = sizeof(err);
    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &elen) < 0 || err != 0) return -1;
    return 0;
}

static int tun_send(int tfd, uint8_t type, uint32_t sid,
                    const uint8_t *data, uint16_t dlen) {
    uint8_t hdr[FRAME_HDR];
    hdr[0] = type;
    hdr[1] = (sid >> 24) & 0xFF; hdr[2] = (sid >> 16) & 0xFF;
    hdr[3] = (sid >>  8) & 0xFF; hdr[4] =  sid        & 0xFF;
    hdr[5] = (dlen >> 8) & 0xFF; hdr[6] =  dlen       & 0xFF;

    struct iovec iov[2];
    iov[0].iov_base = hdr;         iov[0].iov_len = FRAME_HDR;
    iov[1].iov_base = (void*)data; iov[1].iov_len = dlen;
    int niov = dlen > 0 ? 2 : 1;

    ssize_t total = FRAME_HDR + dlen, sent = 0;
    while (sent < total) {
        pthread_mutex_lock(&g_tun_wmu);
        ssize_t n = writev(tfd, iov, niov);
        if (n > 0) {
            pthread_mutex_unlock(&g_tun_wmu);
            sent += n;
            if (sent < total) {
                size_t skip = (size_t)n;
                for (int i = 0; i < niov && skip > 0; i++) {
                    if (skip >= iov[i].iov_len) {
                        skip -= iov[i].iov_len; iov[i].iov_len = 0;
                    } else {
                        iov[i].iov_base = (uint8_t*)iov[i].iov_base + skip;
                        iov[i].iov_len -= skip; skip = 0;
                    }
                }
            }
        } else if (errno == EINTR) {
            pthread_mutex_unlock(&g_tun_wmu);
            continue;
        } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
            pthread_mutex_unlock(&g_tun_wmu);
            struct pollfd wpfd = { tfd, POLLOUT, 0 };
            int wp = poll(&wpfd, 1, 30);
            if (wp <= 0 || !(wpfd.revents & POLLOUT)) return -1;
        } else {
            pthread_mutex_unlock(&g_tun_wmu);
            return -1;
        }
    }
    return 0;
}

static int tun_recv_full(int fd, uint8_t *buf, int len, int timeout_ms) {
    int off = 0;
    while (off < len) {
        struct pollfd pfd = { fd, POLLIN, 0 };
        int pr = poll(&pfd, 1, timeout_ms);
        if (pr < 0) { if (errno == EINTR) continue; return -1; }
        if (pr == 0) return -2;
        ssize_t n = recv(fd, buf + off, len - off, 0);
        if (n > 0) {
            off += (int)n;
        } else if (n == 0) {
            return -1;
        } else if (errno == EINTR || errno == EAGAIN) {
            continue;
        } else {
            return -1;
        }
    }
    return 0;
}

static void jni_protect(int fd) {
    JavaVM *jvm = NULL; jobject svc = NULL;
    pthread_mutex_lock(&g_state_mu);
    jvm = g_jvm; svc = g_vpn_svc;
    pthread_mutex_unlock(&g_state_mu);
    if (!jvm || !svc) return;
    JNIEnv *env = NULL; int att = 0;
    if ((*jvm)->GetEnv(jvm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        (*jvm)->AttachCurrentThread(jvm, &env, NULL); att = 1;
    }
    jclass cls = (*env)->GetObjectClass(env, svc);
    jmethodID m = (*env)->GetMethodID(env, cls, "protect", "(I)Z");
    if (m) (*env)->CallBooleanMethod(env, svc, m, fd);
    (*env)->DeleteLocalRef(env, cls);
    if (att) (*jvm)->DetachCurrentThread(jvm);
}

static int recv_until_eoh(int fd, char *buf, int cap, int timeout_sec) {
    struct timeval tv = {timeout_sec, 0};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    int used = 0, ok = 0;
    while (used < cap - 1) {
        ssize_t n = recv(fd, buf + used, cap - 1 - used, 0);
        if (n <= 0) break;
        used += (int)n; buf[used] = '\0';
        if (strstr(buf, "\r\n\r\n")) { ok = 1; break; }
    }
    tv.tv_sec = 0; setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    return ok ? used : -1;
}

static int parse_http_status(const char *headers) {
    if (!headers) return -1;
    int code = -1;
    if (sscanf(headers, "HTTP/%*d.%*d %d", &code) == 1) return code;
    return -1;
}

static int extract_header_value(const char *headers, const char *key, char *out, int out_cap) {
    if (!headers || !key || !out || out_cap <= 1) return 0;
    const char *p = strstr(headers, key);
    if (!p) return 0;
    p += strlen(key);
    while (*p == ' ' || *p == ':') p++;
    const char *e = strstr(p, "\r\n");
    if (!e) return 0;
    int n = (int)(e - p);
    if (n <= 0) return 0;
    if (n >= out_cap) n = out_cap - 1;
    memcpy(out, p, n);
    out[n] = '\0';
    return 1;
}

static int open_tunnel(void) {
    int fd = -1;

    struct sockaddr_in6 a6 = {0};
    a6.sin6_family = AF_INET6;
    a6.sin6_port   = htons(PROXY_PORT);
    if (inet_pton(AF_INET6, PROXY_HOST_IPV6, &a6.sin6_addr) == 1) {
        fd = socket(AF_INET6, SOCK_STREAM, 0);
        if (fd >= 0) {
            jni_protect(fd); sock_tune(fd); sock_keepalive(fd);
            if (connect_with_timeout(fd, (struct sockaddr*)&a6, sizeof(a6), CONNECT_TIMEOUT_SEC) != 0)
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
                jni_protect(s); sock_tune(s); sock_keepalive(s);
                if (connect_with_timeout(s, r->ai_addr, r->ai_addrlen, CONNECT_TIMEOUT_SEC) == 0)
                    fd = s;
                else close(s);
            }
            freeaddrinfo(res);
        }
    }

    if (fd < 0) { push_log("E", "tunnel connect failed"); return -1; }

    atomic_store(&g_last_pong, (long)time(NULL));

    char req1[256], h1[2048];
    int r1 = snprintf(req1, sizeof(req1),
        "GET / HTTP/1.1\r\nHost: %s\r\n\r\n", PROXY_HOST);
    send(fd, req1, r1, MSG_NOSIGNAL);
    if (recv_until_eoh(fd, h1, sizeof(h1), HANDSHAKE_TIMEOUT_SEC) < 0) { close(fd); return -1; }

    char req2[1024], h2[4096];
    struct timespec t0 = {0}, t1 = {0};
    int r2 = snprintf(req2, sizeof(req2),
        "- / HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\n"
        "Connection: Upgrade\r\nAction: tunnel\r\nX-Internal-ID: %s\r\n\r\n",
        TUNNEL_HOST, g_internal_id[0] ? g_internal_id : "unknown");
    clock_gettime(CLOCK_MONOTONIC, &t0);
    send(fd, req2, r2, MSG_NOSIGNAL);
    int h2_len = recv_until_eoh(fd, h2, sizeof(h2), HANDSHAKE_TIMEOUT_SEC);

    int status = parse_http_status(h2);
    if (h2_len < 0 || status != 101) {
        char body[1024] = {0};
        const char *eoh = strstr(h2, "\r\n\r\n");
        int body_len = 0;
        if (eoh) {
            const char *body_start = eoh + 4;
            body_len = h2_len - (int)(body_start - h2);
            if (body_len > 0) {
                int c = body_len >= (int)sizeof(body) ? (int)sizeof(body) - 1 : body_len;
                memcpy(body, body_start, c);
                body[c] = '\0';
            }
        }
        char clbuf[32] = {0};
        if (extract_header_value(h2, "Content-Length", clbuf, sizeof(clbuf))) {
            int want = atoi(clbuf);
            if (want > body_len && want < (int)sizeof(body)) {
                int remaining = want - body_len;
                if (remaining > 0) {
                    ssize_t rn = recv(fd, body + body_len, remaining, 0);
                    if (rn > 0) { body_len += (int)rn; body[body_len] = '\0'; }
                }
            }
        }

        if (strstr(h2, "not_registered") || strstr(body, "not_registered")
            || strstr(h2, "no_registrado") || strstr(body, "no_registrado")
            || strstr(h2, "usuario_no_registrado") || strstr(body, "usuario_no_registrado")) {
            push_log("E", "usuario no registrado");
        } else if (strstr(h2, "expired") || strstr(body, "expired")
                   || strstr(h2, "expirado") || strstr(body, "expirado")
                   || strstr(h2, "usuario_expirado") || strstr(body, "usuario_expirado")) {
            push_log("E", "usuario expirado");
        } else if (status == 403) {
            push_log("E", "error de autenticacion 403");
        } else {
            push_log("E", "error de autenticacion");
        }
        close(fd); return -1;
    }

    char user_name[128] = {0};
    char user_days[32] = {0};
    if (extract_header_value(h2, "X-User-Name", user_name, sizeof(user_name)))
        push_log("I", "user_name=%s", user_name);
    if (extract_header_value(h2, "X-User-Days", user_days, sizeof(user_days)))
        push_log("I", "user_days=%s", user_days);
    clock_gettime(CLOCK_MONOTONIC, &t1);
    long ping_ms = (t1.tv_sec - t0.tv_sec) * 1000L + (t1.tv_nsec - t0.tv_nsec) / 1000000L;
    if (ping_ms < 0) ping_ms = 0;
    push_log("I", "ping_ms=%ld", ping_ms);

    return fd;
}

static void conn_handle(int cfd, int tfd, uint32_t sid) {
    stream_t *s = ht_put(sid, cfd);
    if (!s) { close(cfd); return; }

    uint8_t buf[MAX_PAYLOAD];

    ssize_t first = recv(cfd, buf, sizeof(buf), 0);
    if (first <= 0) {
        ht_del(sid);
        return;
    }

    atomic_store(&g_last_traffic, (long)time(NULL));

    if (tun_send(tfd, T_OPEN, sid, buf, (uint16_t)first) < 0) {
        request_tunnel_reset("tun_send T_OPEN failed");
        ht_del(sid);
        return;
    }

    while (g_running) {
        ssize_t n = recv(cfd, buf, sizeof(buf), 0);
        if (n < 0) { if (errno == EINTR) continue; break; }
        if (n == 0) break;

        atomic_store(&s->last_active, (long)time(NULL));
        atomic_store(&g_last_traffic, (long)time(NULL));

        if (tun_send(tfd, T_DATA, sid, buf, (uint16_t)n) < 0) {
            request_tunnel_reset("tun_send T_DATA failed");
            break;
        }
    }

    tun_send(tfd, T_CLOSE, sid, NULL, 0);
    ht_del(sid);
}

static void connq_push(int cfd, int tfd, uint32_t sid) {
    conn_task_t *t = malloc(sizeof(conn_task_t));
    if (!t) { close(cfd); return; }
    t->next = NULL;
    t->cfd = cfd;
    t->tfd = tfd;
    t->sid = sid;

    pthread_mutex_lock(&g_connq_mu);
    if (g_connq_tail) g_connq_tail->next = t;
    else g_connq_head = t;
    g_connq_tail = t;
    pthread_cond_signal(&g_connq_cv);
    pthread_mutex_unlock(&g_connq_mu);
}

static conn_task_t *connq_pop(void) {
    pthread_mutex_lock(&g_connq_mu);
    while (g_running && g_connq_head == NULL)
        pthread_cond_wait(&g_connq_cv, &g_connq_mu);

    conn_task_t *t = g_connq_head;
    if (t) {
        g_connq_head = t->next;
        if (!g_connq_head) g_connq_tail = NULL;
    }
    pthread_mutex_unlock(&g_connq_mu);
    return t;
}

static void connq_clear(void) {
    pthread_mutex_lock(&g_connq_mu);
    conn_task_t *t = g_connq_head;
    while (t) {
        conn_task_t *nx = t->next;
        if (t->cfd >= 0) close(t->cfd);
        free(t);
        t = nx;
    }
    g_connq_head = NULL;
    g_connq_tail = NULL;
    pthread_mutex_unlock(&g_connq_mu);
}

static void *conn_worker(void *arg) {
    (void)arg;
    while (g_running) {
        conn_task_t *t = connq_pop();
        if (!t) continue;
        conn_handle(t->cfd, t->tfd, t->sid);
        free(t);
    }
    return NULL;
}

static void start_conn_workers(void) {
    if (g_conn_workers_started > 0) return;
    int n = atomic_load(&g_global_mode) == GLOBAL_MODE_GAMING
            ? GAMING_CONN_WORKERS : DAILY_CONN_WORKERS;
    for (int i = 0; i < n; i++) {
        if (pthread_create(&g_conn_workers[i], NULL, conn_worker, NULL) != 0) break;
        g_conn_workers_started++;
    }
}

static void stop_conn_workers(void) {
    pthread_mutex_lock(&g_connq_mu);
    pthread_cond_broadcast(&g_connq_cv);
    pthread_mutex_unlock(&g_connq_mu);
    for (int i = 0; i < g_conn_workers_started; i++)
        pthread_join(g_conn_workers[i], NULL);
    g_conn_workers_started = 0;
}

typedef struct { int tfd; int epoch; } thr_args_t;

static void *tunnel_reader(void *arg) {
    thr_args_t *ta = (thr_args_t*)arg;
    int tfd = ta->tfd;
    int epoch = ta->epoch;
    free(ta);

    uint8_t hdr[FRAME_HDR], payload[MAX_PAYLOAD];

    while (g_running && atomic_load(&g_tunnel_epoch) == epoch) {
        int rc = tun_recv_full(tfd, hdr, FRAME_HDR, 60000);
        if (!g_running || atomic_load(&g_tunnel_epoch) != epoch) break;
        if (rc == -2) {
            long last = atomic_load(&g_last_pong);
            if (last > 0 && (long)time(NULL) - last > PONG_TIMEOUT_SEC) {
                request_tunnel_reset("pong timeout");
                break;
            }
            continue;
        }
        if (rc < 0) {
            request_tunnel_reset("tunnel header read failed");
            break;
        }

        uint8_t  ft  = hdr[0];
        uint32_t sid = ((uint32_t)hdr[1] << 24) | ((uint32_t)hdr[2] << 16) |
                       ((uint32_t)hdr[3] <<  8) |  (uint32_t)hdr[4];
        uint16_t len = ((uint16_t)hdr[5] << 8) | hdr[6];

        if (len > MAX_PAYLOAD) { request_tunnel_reset("payload too large"); break; }
        if (len > 0) {
            rc = tun_recv_full(tfd, payload, len, 30000);
            if (rc < 0) { request_tunnel_reset("tunnel payload read failed"); break; }
        }

        switch (ft) {
        case T_DATA: {
            stream_t *s = ht_get(sid);
            if (!s || len == 0) break;
            atomic_store(&s->last_active, (long)time(NULL));
            atomic_store(&g_last_traffic, (long)time(NULL));
            ssize_t off = 0;
            while (off < len) {
                ssize_t n = send(s->fd, payload + off, len - off,
                                 MSG_NOSIGNAL | MSG_DONTWAIT);
                if (n > 0) { off += n; continue; }
                if (errno == EAGAIN || errno == EWOULDBLOCK) break;
                break;
            }
            break;
        }
        case T_CLOSE:
            ht_del(sid);
            break;
        case T_PING:
            tun_send(tfd, T_PONG, 0, NULL, 0);
            break;
        case T_PONG:
            atomic_store(&g_last_pong, (long)time(NULL));
            break;
        default:
            break;
        }
    }
    return NULL;
}

static void *keepalive_thread(void *arg) {
    thr_args_t *ta = (thr_args_t*)arg;
    int tfd = ta->tfd;
    int epoch = ta->epoch;
    free(ta);

    atomic_store(&g_last_pong, (long)time(NULL));
    atomic_store(&g_last_traffic, (long)time(NULL));

    long last_ping_report = 0;

    while (g_running && atomic_load(&g_tunnel_epoch) == epoch) {
        for (int i = 0; i < KEEPALIVE_SEC && g_running && atomic_load(&g_tunnel_epoch) == epoch; i++)
            sleep(1);
        if (!g_running || atomic_load(&g_tunnel_epoch) != epoch) break;

        long last_pong = atomic_load(&g_last_pong);
        if (last_pong > 0 && (long)time(NULL) - last_pong > PONG_TIMEOUT_SEC) {
            request_tunnel_reset("pong timeout in keepalive");
            break;
        }

        long now = (long)time(NULL);
        long last_traffic = atomic_load(&g_last_traffic);
        int  is_idle      = (now - last_traffic) > IDLE_TRAFFIC_SEC;
        int  ping_interval = is_idle ? PING_IDLE_SEC : PING_ACTIVE_SEC;

        if ((now - last_ping_report) < ping_interval) continue;

        struct timespec tp0, tp1;
        clock_gettime(CLOCK_MONOTONIC, &tp0);

        if (tun_send(tfd, T_PING, 0, NULL, 0) < 0) {
            request_tunnel_reset("keepalive ping failed");
            break;
        }

        int wait_us = 5000;
        for (int elapsed_us = 0; elapsed_us < 3000000 && g_running; elapsed_us += wait_us) {
            long new_pong = atomic_load(&g_last_pong);
            if (new_pong > last_pong) break;
            usleep(wait_us);
            if (wait_us < 50000) wait_us *= 2;
        }

        clock_gettime(CLOCK_MONOTONIC, &tp1);
        long rtt = (tp1.tv_sec - tp0.tv_sec) * 1000L + (tp1.tv_nsec - tp0.tv_nsec) / 1000000L;
        if (rtt > 0) push_log("I", "ping_ms=%ld", rtt);

        last_ping_report = now;
    }
    return NULL;
}

static int g_reconnect_delay = RECONNECT_DELAY_MIN;

static void *main_thread(void *arg) {
    int port = (int)(intptr_t)arg;
    static int first_start = 1;
    start_conn_workers();

    while (g_running) {
        int tfd = open_tunnel();
        if (tfd < 0) {
            pthread_mutex_lock(&g_start_mu);
            if (g_start_st == 0) { g_start_st = -1; pthread_cond_broadcast(&g_start_cv); }
            pthread_mutex_unlock(&g_start_mu);
            if (!g_running) break;
            push_log("E", "reconnect in %ds", g_reconnect_delay);
            for (int i = 0; i < g_reconnect_delay && g_running; i++) sleep(1);
            if (g_reconnect_delay < RECONNECT_DELAY_MAX) g_reconnect_delay *= 2;
            if (g_reconnect_delay > RECONNECT_DELAY_MAX) g_reconnect_delay = RECONNECT_DELAY_MAX;
            continue;
        }
        g_reconnect_delay = RECONNECT_DELAY_MIN;
        g_tun_fd = tfd;

        int rfd = socket(AF_INET, SOCK_STREAM, 0);
        if (rfd < 0) { close(tfd); g_tun_fd = -1; sleep(1); continue; }
        int one = 1; setsockopt(rfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
        int v = 1;   setsockopt(rfd, IPPROTO_TCP, TCP_NODELAY,  &v,   sizeof(v));
        int fl = fcntl(rfd, F_GETFD, 0);
        if (fl >= 0) fcntl(rfd, F_SETFD, fl | FD_CLOEXEC);
        struct sockaddr_in la = {0};
        la.sin_family = AF_INET; la.sin_port = htons((uint16_t)port);
        la.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        if (bind(rfd, (struct sockaddr*)&la, sizeof(la)) < 0 ||
            listen(rfd, RELAY_BACKLOG) < 0) {
            close(rfd); close(tfd); g_relay_fd = -1; g_tun_fd = -1;
            pthread_mutex_lock(&g_start_mu);
            if (g_start_st == 0) { g_start_st = -1; pthread_cond_broadcast(&g_start_cv); }
            pthread_mutex_unlock(&g_start_mu);
            push_log("E", "relay bind/listen failed");
            sleep(2); continue;
        }

        int cur_epoch = atomic_fetch_add(&g_tunnel_epoch, 1) + 1;
        g_relay_fd = rfd; g_started = 1;
        push_log("I", "relay listening on 127.0.0.1:%d epoch=%d", port, cur_epoch);

        pthread_mutex_lock(&g_start_mu);
        if (g_start_st == 0) { g_start_st = 1; pthread_cond_broadcast(&g_start_cv); }
        pthread_mutex_unlock(&g_start_mu);

        if (first_start) {
            first_start = 0;
        } else {
            notify_tunnel_reconnected();
        }

        thr_args_t *ta_rd = malloc(sizeof(thr_args_t));
        thr_args_t *ta_ka = malloc(sizeof(thr_args_t));
        if (ta_rd) { ta_rd->tfd = tfd; ta_rd->epoch = cur_epoch; }
        if (ta_ka) { ta_ka->tfd = tfd; ta_ka->epoch = cur_epoch; }

        pthread_t trd, tka;
        if (ta_rd) { pthread_create(&trd, NULL, tunnel_reader, ta_rd); pthread_detach(trd); }
        else free(ta_rd);
        if (ta_ka) { pthread_create(&tka, NULL, keepalive_thread, ta_ka); pthread_detach(tka); }
        else free(ta_ka);

        while (g_running) {
            struct pollfd pfd = { rfd, POLLIN, 0 };
            int pr = poll(&pfd, 1, current_accept_poll_ms());
            if (!g_running) break;
            if (pr < 0) {
                if (errno == EINTR) continue;
                request_tunnel_reset("accept poll failed"); break;
            }
            if (pr > 0 && (pfd.revents & (POLLERR | POLLHUP | POLLNVAL))) break;
            if (pr == 0) {
                pthread_mutex_lock(&g_state_mu);
                int still_same = (g_tun_fd == tfd && g_relay_fd == rfd);
                pthread_mutex_unlock(&g_state_mu);
                if (!still_same) break;
                continue;
            }
            struct sockaddr_in ca; socklen_t cl = sizeof(ca);
            int cfd = accept(rfd, (struct sockaddr*)&ca, &cl);
            if (cfd < 0) {
                if (!g_running) break;
                if (errno == EINTR || errno == EAGAIN) continue;
                request_tunnel_reset("accept failed"); break;
            }
            sock_tune(cfd);

            uint32_t sid;
            do {
                sid = (uint32_t)atomic_fetch_add(&g_next_sid, 1) & 0x7FFFFFFF;
            } while (sid == 0 || ht_get(sid) != NULL);

            connq_push(cfd, tfd, sid);
        }

        request_tunnel_reset(NULL);
        if (g_running) { push_log("E", "tunnel dropped, reconnecting"); sleep(1); }
    }
    connq_clear();
    stop_conn_workers();
    g_started = 0;
    return NULL;
}

JNIEXPORT jint JNICALL
Java_com_blacktunnel_BtProxy_nativeStart(JNIEnv *env, jclass clazz,
                                          jint port, jobject svc, jstring internal_id) {
    pthread_mutex_lock(&g_state_mu);
    if (g_running) { pthread_mutex_unlock(&g_state_mu); return 0; }
    (*env)->GetJavaVM(env, &g_jvm);
    g_vpn_svc = (*env)->NewGlobalRef(env, svc);
    g_internal_id[0] = '\0';
    if (internal_id != NULL) {
        const char *iid = (*env)->GetStringUTFChars(env, internal_id, NULL);
        if (iid != NULL) {
            snprintf(g_internal_id, sizeof(g_internal_id), "%s", iid);
            (*env)->ReleaseStringUTFChars(env, internal_id, iid);
        }
    }
    ht_init();
    g_running = 1; g_started = 0;
    g_reconnect_delay = RECONNECT_DELAY_MIN;
    atomic_store(&g_next_sid, 1);
    atomic_store(&g_last_traffic, (long)time(NULL));
    pthread_mutex_unlock(&g_state_mu);

    pthread_mutex_lock(&g_start_mu); g_start_st = 0;
    pthread_mutex_unlock(&g_start_mu);

    if (pthread_create(&g_main_thr, NULL, main_thread,
                       (void*)(intptr_t)port) != 0) {
        pthread_mutex_lock(&g_state_mu); g_running = 0;
        if (g_vpn_svc) { (*env)->DeleteGlobalRef(env, g_vpn_svc); g_vpn_svc = NULL; }
        g_jvm = NULL; pthread_mutex_unlock(&g_state_mu);
        return -1;
    }
    pthread_detach(g_main_thr);

    struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts); ts.tv_sec += 12;
    pthread_mutex_lock(&g_start_mu);
    while (g_start_st == 0)
        if (pthread_cond_timedwait(&g_start_cv, &g_start_mu, &ts) != 0) break;
    int st = g_start_st;
    pthread_mutex_unlock(&g_start_mu);

    if (st != 1) {
        Java_com_blacktunnel_BtProxy_nativeStop(env, clazz);
        return -1;
    }
    push_log("I", "nativeStart ok");
    return 0;
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeStop(JNIEnv *env, jclass clazz) {
    pthread_mutex_lock(&g_state_mu);
    if (!g_running) { pthread_mutex_unlock(&g_state_mu); return; }
    g_running = 0;
    g_internal_id[0] = '\0';
    jobject svc = g_vpn_svc; g_vpn_svc = NULL; g_jvm = NULL;
    pthread_mutex_unlock(&g_state_mu);
    atomic_fetch_add(&g_tunnel_epoch, 1);
    if (g_relay_fd >= 0) { shutdown(g_relay_fd, SHUT_RDWR); close(g_relay_fd); g_relay_fd = -1; }
    if (g_tun_fd   >= 0) { shutdown(g_tun_fd,   SHUT_RDWR); close(g_tun_fd);   g_tun_fd   = -1; }
    pthread_mutex_lock(&g_connq_mu);
    pthread_cond_broadcast(&g_connq_cv);
    pthread_mutex_unlock(&g_connq_mu);
    pthread_mutex_lock(&g_start_mu);
    if (g_start_st == 0) { g_start_st = -1; pthread_cond_broadcast(&g_start_cv); }
    pthread_mutex_unlock(&g_start_mu);
    for (int i = 0; i < 20 && g_started; i++) usleep(10000);
    ht_clear();
    if (svc) (*env)->DeleteGlobalRef(env, svc);
    g_started = 0;
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeSetGamingMode(JNIEnv *env, jclass clazz, jboolean enabled) {
    int mode = enabled ? GLOBAL_MODE_GAMING : GLOBAL_MODE_DAILY;
    atomic_store(&g_global_mode, mode);
    push_log("I", "global_mode=%s", enabled ? "gaming" : "daily");
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeApplyMode(JNIEnv *env, jclass clazz, jboolean enabled) {
    int mode = enabled ? GLOBAL_MODE_GAMING : GLOBAL_MODE_DAILY;
    atomic_store(&g_global_mode, mode);
    connq_clear();
    ht_clear();
    push_log("I", "apply_mode=%s", enabled ? "gaming" : "daily");
}

JNIEXPORT jstring JNICALL
Java_com_blacktunnel_BtProxy_nativeDrainLogs(JNIEnv *env, jclass clazz) {
    pthread_mutex_lock(&g_log_mu);
    if (g_log_len == 0) {
        pthread_mutex_unlock(&g_log_mu);
        return (*env)->NewStringUTF(env, "");
    }
    char out[32768];
    memcpy(out, g_log_buf, g_log_len); out[g_log_len] = '\0';
    g_log_len = 0; g_log_buf[0] = '\0';
    pthread_mutex_unlock(&g_log_mu);
    return (*env)->NewStringUTF(env, out);
}
