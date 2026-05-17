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
#include <sys/epoll.h>
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

#define FRAME_HDR              7
#define MAX_PAYLOAD            16384
#define RELAY_BACKLOG          512
#define PONG_TIMEOUT_SEC       120
#define KEEPALIVE_INTERVAL_SEC 10
#define RECONNECT_DELAY_MIN    2
#define RECONNECT_DELAY_MAX    30
#define CONNECT_TIMEOUT_SEC    15
#define HANDSHAKE_TIMEOUT_SEC  4
#define MAX_EPOLL_EVENTS       32
#define HAPPY_DELAY_MS         200
#define RESOLVED_IP_MAX        8
#define WRITE_POLL_TIMEOUT_MS  500

#define PROXY_HOST  "emailmarketing.personal.com.ar"
#define PROXY_PORT  80
#define TUNNEL_HOST "2.brawlpass.com.ar"

static const char *PROXY_IPS[] = {
    "2606:4700::6812:16b7",
    "2606:4700::6812:17b7",
};
#define PROXY_IP_COUNT 2

typedef enum {
    TUNNEL_OK            =  0,
    TUNNEL_ERR_TRANSIENT = -1,
    TUNNEL_ERR_AUTH      = -2,
    TUNNEL_ERR_EXPIRED   = -3,
} tunnel_result_t;

#define READY_PENDING   0
#define READY_OK        1
#define READY_FAIL     -1
#define READY_AUTH_ERR -2

static int resolve_proxy_ipv6(char out[][INET6_ADDRSTRLEN], int cap) {
    struct addrinfo hints = {0}, *res = NULL;
    hints.ai_family   = AF_INET6;
    hints.ai_socktype = SOCK_STREAM;
    if (getaddrinfo(PROXY_HOST, "80", &hints, &res) != 0 || !res) return 0;
    int n = 0;
    for (struct addrinfo *it = res; it && n < cap; it = it->ai_next) {
        if (it->ai_family != AF_INET6 || !it->ai_addr) continue;
        struct sockaddr_in6 *sa = (struct sockaddr_in6 *)it->ai_addr;
        char ip[INET6_ADDRSTRLEN] = {0};
        if (!inet_ntop(AF_INET6, &sa->sin6_addr, ip, sizeof(ip))) continue;
        int dup = 0;
        for (int i = 0; i < n; i++) if (strcmp(out[i], ip) == 0) { dup = 1; break; }
        if (dup) continue;
        snprintf(out[n], INET6_ADDRSTRLEN, "%s", ip);
        n++;
    }
    freeaddrinfo(res);
    return n;
}

static volatile int    g_running         = 0;
static int             g_relay_fd        = -1;
static int             g_tun_fd          = -1;
static int             g_epoll_fd        = -1;
static int             g_wake_r          = -1;
static int             g_wake_w          = -1;
static atomic_int      g_next_sid        = 1;
static atomic_int      g_tunnel_epoch    = 0;
static atomic_long     g_last_pong       = 0;
static char            g_internal_id[160] = {0};
static JavaVM         *g_jvm             = NULL;
static jobject         g_svc             = NULL;
static net_handle_t    g_net             = NETWORK_UNSPECIFIED;
static pthread_t       g_main_thread     = 0;
static pthread_mutex_t g_mu              = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_wmu             = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_log_mu          = PTHREAD_MUTEX_INITIALIZER;
static char            g_logbuf[32768];
static size_t          g_loglen          = 0;
static pthread_mutex_t g_ready_mu        = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_ready_cv        = PTHREAD_COND_INITIALIZER;
static int             g_ready_st        = READY_PENDING;
static int             g_ht_inited       = 0;
static int             g_gaming_mode     = 0;

#define HT_SIZE 4096
#define HT_MASK (HT_SIZE - 1)

typedef struct hn_s { struct hn_s *next; uint32_t sid; int cfd; } hn_t;
static hn_t           *g_ht[HT_SIZE];
static pthread_mutex_t g_ht_mu[HT_SIZE];

static void ht_init(void) {
    for (int i = 0; i < HT_SIZE; i++) {
        g_ht[i] = NULL;
        if (!g_ht_inited) pthread_mutex_init(&g_ht_mu[i], NULL);
    }
    g_ht_inited = 1;
}

static int ht_get(uint32_t sid) {
    int slot = sid & HT_MASK;
    pthread_mutex_lock(&g_ht_mu[slot]);
    hn_t *n = g_ht[slot];
    while (n && n->sid != sid) n = n->next;
    int cfd = n ? n->cfd : -1;
    pthread_mutex_unlock(&g_ht_mu[slot]);
    return cfd;
}

static void ht_put(uint32_t sid, int cfd) {
    hn_t *n = malloc(sizeof(*n));
    if (!n) return;
    n->sid = sid; n->cfd = cfd;
    int slot = sid & HT_MASK;
    pthread_mutex_lock(&g_ht_mu[slot]);
    n->next = g_ht[slot]; g_ht[slot] = n;
    pthread_mutex_unlock(&g_ht_mu[slot]);
}

static void ht_del(uint32_t sid) {
    int slot = sid & HT_MASK;
    pthread_mutex_lock(&g_ht_mu[slot]);
    hn_t **pp = &g_ht[slot];
    while (*pp) {
        if ((*pp)->sid == sid) {
            hn_t *n = *pp; *pp = n->next; free(n); break;
        }
        pp = &(*pp)->next;
    }
    pthread_mutex_unlock(&g_ht_mu[slot]);
}

static void ht_close_all(int epfd) {
    for (int i = 0; i < HT_SIZE; i++) {
        pthread_mutex_lock(&g_ht_mu[i]);
        hn_t *n = g_ht[i];
        while (n) {
            hn_t *nx = n->next;
            if (epfd >= 0) epoll_ctl(epfd, EPOLL_CTL_DEL, n->cfd, NULL);
            close(n->cfd);
            free(n);
            n = nx;
        }
        g_ht[i] = NULL;
        pthread_mutex_unlock(&g_ht_mu[i]);
    }
}

static void push_log(const char *lvl, const char *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    char msg[512]; vsnprintf(msg, sizeof(msg), fmt, ap); va_end(ap);
    if (lvl[0] == 'E') LOGE("%s", msg); else LOGI("%s", msg);
    pthread_mutex_lock(&g_log_mu);
    char line[560];
    int n = snprintf(line, sizeof(line), "%s %s\n", lvl, msg);
    if (n > 0) {
        if (g_loglen + (size_t)n >= sizeof(g_logbuf)) {
            size_t drop = g_loglen + n - sizeof(g_logbuf) + 1;
            memmove(g_logbuf, g_logbuf + drop, g_loglen - drop);
            g_loglen -= drop;
        }
        memcpy(g_logbuf + g_loglen, line, n);
        g_loglen += n;
        g_logbuf[g_loglen] = 0;
    }
    pthread_mutex_unlock(&g_log_mu);
}

static void wake_epoll(void) {
    pthread_mutex_lock(&g_mu);
    int w = g_wake_w;
    pthread_mutex_unlock(&g_mu);
    if (w >= 0) { uint8_t b = 1; write(w, &b, 1); }
}

static void protect_fd(int fd) {
    pthread_mutex_lock(&g_mu);
    net_handle_t net = g_net;
    JavaVM *jvm = g_jvm; jobject svc = g_svc;
    pthread_mutex_unlock(&g_mu);
    if (net != NETWORK_UNSPECIFIED) android_setsocknetwork(net, fd);
    if (!jvm || !svc) return;
    JNIEnv *env = NULL; int att = 0;
    if ((*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
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
    if ((*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
        { (*jvm)->AttachCurrentThread(jvm, &env, NULL); att = 1; }
    jclass cls = (*env)->GetObjectClass(env, svc);
    jmethodID m = (*env)->GetMethodID(env, cls, "onTunnelReconnected", "()V");
    if (m) (*env)->CallVoidMethod(env, svc, m);
    (*env)->DeleteLocalRef(env, cls);
    if (att) (*jvm)->DetachCurrentThread(jvm);
}

static void notify_auth_error(int expired) {
    pthread_mutex_lock(&g_mu);
    JavaVM *jvm = g_jvm; jobject svc = g_svc;
    pthread_mutex_unlock(&g_mu);
    if (!jvm || !svc) return;
    JNIEnv *env = NULL; int att = 0;
    if ((*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
        { (*jvm)->AttachCurrentThread(jvm, &env, NULL); att = 1; }
    jclass cls = (*env)->GetObjectClass(env, svc);
    jmethodID m = (*env)->GetMethodID(env, cls, "onAuthError", "(Z)V");
    if (m) (*env)->CallVoidMethod(env, svc, m, (jboolean)expired);
    (*env)->DeleteLocalRef(env, cls);
    if (att) (*jvm)->DetachCurrentThread(jvm);
}

static void tune_tun(int fd) {
    int v;
    v = 1;      setsockopt(fd, IPPROTO_TCP, TCP_NODELAY,   &v, sizeof(v));
    v = 1;      setsockopt(fd, IPPROTO_TCP, TCP_QUICKACK,  &v, sizeof(v));
    v = 0x10;   setsockopt(fd, IPPROTO_IP,  IP_TOS,        &v, sizeof(v));
    v = 524288; setsockopt(fd, SOL_SOCKET,  SO_SNDBUF,     &v, sizeof(v));
    v = 524288; setsockopt(fd, SOL_SOCKET,  SO_RCVBUF,     &v, sizeof(v));
    v = 1;      setsockopt(fd, SOL_SOCKET,  SO_KEEPALIVE,  &v, sizeof(v));
    v = 20;     setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE,  &v, sizeof(v));
    v = 5;      setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &v, sizeof(v));
    v = 3;      setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT,   &v, sizeof(v));
    int fl = fcntl(fd, F_GETFD, 0);
    if (fl >= 0) fcntl(fd, F_SETFD, fl | FD_CLOEXEC);
}

static int tun_send(int tfd, uint8_t type, uint32_t sid,
                    const uint8_t *data, uint16_t dlen) {
    uint8_t hdr[FRAME_HDR];
    hdr[0] = type;
    hdr[1] = (sid >> 24) & 0xFF; hdr[2] = (sid >> 16) & 0xFF;
    hdr[3] = (sid >>  8) & 0xFF; hdr[4] =  sid        & 0xFF;
    hdr[5] = (dlen >> 8) & 0xFF; hdr[6] =  dlen       & 0xFF;
    struct iovec iov[2] = {{hdr, FRAME_HDR}, {(void *)data, dlen}};
    int niov = dlen ? 2 : 1;
    ssize_t total = FRAME_HDR + dlen, sent = 0;
    while (sent < total) {
        pthread_mutex_lock(&g_wmu);
        ssize_t n = writev(tfd, iov, niov);
        pthread_mutex_unlock(&g_wmu);
        if (n > 0) {
            sent += n;
            if (sent < total) {
                size_t skip = (size_t)n;
                for (int i = 0; i < niov && skip > 0; i++) {
                    if (skip >= iov[i].iov_len) {
                        skip -= iov[i].iov_len; iov[i].iov_len = 0;
                    } else {
                        iov[i].iov_base = (uint8_t *)iov[i].iov_base + skip;
                        iov[i].iov_len -= skip; skip = 0;
                    }
                }
            }
        } else if (errno == EINTR) {
            continue;
        } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
            struct pollfd wp = {tfd, POLLOUT, 0};
            if (poll(&wp, 1, WRITE_POLL_TIMEOUT_MS) <= 0) return -1;
        } else {
            return -1;
        }
    }
    return 0;
}

static int tun_recv_full(int fd, uint8_t *buf, int len, int ms) {
    int off = 0;
    while (off < len) {
        struct pollfd p = {fd, POLLIN, 0};
        int pr = poll(&p, 1, ms);
        if (pr < 0) { if (errno == EINTR) continue; return -1; }
        if (pr == 0) return -2;
        ssize_t n = recv(fd, buf + off, len - off, 0);
        if (n > 0) { off += n; }
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
    tv.tv_sec = 0;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    return ok ? used : -1;
}

static void parse_hdr(const char *buf, const char *key, char *out, int cap) {
    const char *p = strstr(buf, key); if (!p) return;
    p += strlen(key); while (*p == ' ' || *p == ':') p++;
    const char *e = strstr(p, "\r\n"); if (!e) return;
    int n = (int)(e - p); if (n <= 0 || n >= cap) return;
    memcpy(out, p, n); out[n] = 0;
}

static int try_connect_ip(const char *ip, int timeout_ms) {
    struct sockaddr_in6 a = {0};
    a.sin6_family = AF_INET6; a.sin6_port = htons(PROXY_PORT);
    if (inet_pton(AF_INET6, ip, &a.sin6_addr) != 1) return -1;
    int fd = socket(AF_INET6, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    protect_fd(fd); tune_tun(fd);
    int fl = fcntl(fd, F_GETFL, 0); fcntl(fd, F_SETFL, fl | O_NONBLOCK);
    int r = connect(fd, (struct sockaddr *)&a, sizeof(a));
    if (r == 0) { fcntl(fd, F_SETFL, fl); return fd; }
    if (errno != EINPROGRESS) { close(fd); return -1; }
    struct pollfd p = {fd, POLLOUT, 0};
    if (poll(&p, 1, timeout_ms) <= 0) { close(fd); return -1; }
    int e = 0; socklen_t el = sizeof(e);
    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &e, &el) < 0 || e != 0) { close(fd); return -1; }
    fcntl(fd, F_SETFL, fl);
    return fd;
}

static tunnel_result_t classify_403(int fd, const char *hdrs) {
    char extra[256] = {0};
    recv(fd, extra, sizeof(extra) - 1, MSG_DONTWAIT);

    if (strstr(hdrs, "not_registered") || strstr(extra, "not_registered")) {
        push_log("E", "usuario no registrado");
        return TUNNEL_ERR_AUTH;
    }
    if (strstr(hdrs, "expired") || strstr(extra, "expired")) {
        push_log("E", "usuario expirado");
        return TUNNEL_ERR_EXPIRED;
    }
    push_log("E", "error 403");
    return TUNNEL_ERR_AUTH;
}

static tunnel_result_t open_tunnel(int *fd_out) {
    push_log("I", "resolviendo conexion...");

    int order[PROXY_IP_COUNT];
    for (int i = 0; i < PROXY_IP_COUNT; i++) order[i] = i;
    if ((time(NULL) & 1) == 0) { int tmp = order[0]; order[0] = order[1]; order[1] = tmp; }

    int fd = -1;
    for (int i = 0; i < PROXY_IP_COUNT && fd < 0; i++) {
        push_log("I", "probando ip=%s", PROXY_IPS[order[i]]);
        fd = try_connect_ip(PROXY_IPS[order[i]], HAPPY_DELAY_MS);
        if (fd < 0 && i < PROXY_IP_COUNT - 1)
            push_log("I", "sin respuesta en %dms, probando siguiente", HAPPY_DELAY_MS);
    }

    if (fd < 0) {
        char dyn[RESOLVED_IP_MAX][INET6_ADDRSTRLEN] = {{0}};
        int dn = resolve_proxy_ipv6(dyn, RESOLVED_IP_MAX);
        if (dn > 0) {
            push_log("I", "fallback dns ipv6 count=%d", dn);
            for (int i = 0; i < dn && fd < 0; i++) {
                push_log("I", "probando dns ip=%s", dyn[i]);
                fd = try_connect_ip(dyn[i], CONNECT_TIMEOUT_SEC * 1000);
            }
        }
    }

    if (fd < 0) {
        push_log("E", "tunnel connect failed");
        return TUNNEL_ERR_TRANSIENT;
    }
    push_log("I", "conexion establecida");

    atomic_store(&g_last_pong, (long)time(NULL));

    // payload 1: preflight against proxy
    char payload1[256], h1[2048];
    snprintf(payload1, sizeof(payload1), "GET / HTTP/1.1\r\nHost: %s\r\n\r\n", PROXY_HOST);
    send(fd, payload1, strlen(payload1), MSG_NOSIGNAL);
    if (recv_eoh(fd, h1, sizeof(h1), HANDSHAKE_TIMEOUT_SEC) < 0) {
        close(fd);
        return TUNNEL_ERR_TRANSIENT;
    }

    // payload 2: tunnel upgrade + internal id
    char payload2[1024], h2[4096];
    snprintf(payload2, sizeof(payload2),
        "- / HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\n"
        "Connection: Upgrade\r\nAction: tunnel\r\nX-Internal-ID: %s\r\n\r\n",
        TUNNEL_HOST, g_internal_id[0] ? g_internal_id : "unknown");
    send(fd, payload2, strlen(payload2), MSG_NOSIGNAL);
    int hlen = recv_eoh(fd, h2, sizeof(h2), HANDSHAKE_TIMEOUT_SEC);

    int code = -1;
    sscanf(h2, "HTTP/%*d.%*d %d", &code);

    if (hlen < 0 || code != 101) {
        tunnel_result_t res = TUNNEL_ERR_TRANSIENT;
        if (code == 403) res = classify_403(fd, h2);
        else             push_log("E", "handshake failed code=%d", code);
        close(fd);
        return res;
    }

    char uname[128] = {0}, udays[32] = {0};
    parse_hdr(h2, "X-User-Name:", uname, sizeof(uname));
    parse_hdr(h2, "X-User-Days:", udays, sizeof(udays));
    if (uname[0]) push_log("I", "user_name=%s", uname);
    if (udays[0]) push_log("I", "user_days=%s", udays);
    push_log("I", "tunnel ok");

    *fd_out = fd;
    return TUNNEL_OK;
}

typedef struct { int tfd; int epoch; int epfd; int wake_w; } thr_t;

static void *tunnel_reader(void *arg) {
    thr_t *ta = (thr_t *)arg;
    int tfd = ta->tfd, epoch = ta->epoch, epfd = ta->epfd, wake_w = ta->wake_w;
    free(ta);

    uint8_t hdr[FRAME_HDR], payload[MAX_PAYLOAD];

    while (g_running && atomic_load(&g_tunnel_epoch) == epoch) {
        int rc = tun_recv_full(tfd, hdr, FRAME_HDR, 60000);
        if (!g_running || atomic_load(&g_tunnel_epoch) != epoch) break;
        if (rc == -2) {
            if (atomic_load(&g_last_pong) > 0 &&
                (long)time(NULL) - atomic_load(&g_last_pong) > PONG_TIMEOUT_SEC) {
                push_log("E", "pong timeout");
                uint8_t b = 1; write(wake_w, &b, 1);
            }
            continue;
        }
        if (rc < 0) {
            push_log("E", "tunnel read failed");
            uint8_t b = 1; write(wake_w, &b, 1);
            break;
        }

        uint8_t  ft  = hdr[0];
        uint32_t sid = ((uint32_t)hdr[1] << 24) | ((uint32_t)hdr[2] << 16) |
                       ((uint32_t)hdr[3] <<  8) |  (uint32_t)hdr[4];
        uint16_t len = ((uint16_t)hdr[5] << 8) | hdr[6];

        if (len > MAX_PAYLOAD) {
            push_log("E", "payload too large");
            uint8_t b = 1; write(wake_w, &b, 1);
            break;
        }
        if (len > 0 && tun_recv_full(tfd, payload, len, 30000) < 0) {
            push_log("E", "payload read failed");
            uint8_t b = 1; write(wake_w, &b, 1);
            break;
        }

        switch (ft) {
        case T_DATA: {
            int cfd = ht_get(sid);
            if (cfd >= 0 && len > 0) send(cfd, payload, len, MSG_NOSIGNAL);
            break;
        }
        case T_CLOSE: {
            int cfd = ht_get(sid);
            if (cfd >= 0) {
                epoll_ctl(epfd, EPOLL_CTL_DEL, cfd, NULL);
                ht_del(sid);
                close(cfd);
            }
            break;
        }
        case T_PING: tun_send(tfd, T_PONG, 0, NULL, 0); break;
        case T_PONG: atomic_store(&g_last_pong, (long)time(NULL)); break;
        }
    }

    return NULL;
}

static void *keepalive(void *arg) {
    thr_t *ta = (thr_t *)arg;
    int tfd = ta->tfd, epoch = ta->epoch, wake_w = ta->wake_w;
    free(ta);
    atomic_store(&g_last_pong, (long)time(NULL));
    long last = time(NULL);
    while (g_running && atomic_load(&g_tunnel_epoch) == epoch) {
        sleep(1);
        if (!g_running || atomic_load(&g_tunnel_epoch) != epoch) break;
        long now  = time(NULL);
        long pong = atomic_load(&g_last_pong);
        if (pong > 0 && now - pong > PONG_TIMEOUT_SEC) {
            push_log("E", "pong timeout keepalive");
            uint8_t b = 1; write(wake_w, &b, 1);
            break;
        }
        if (now - last < KEEPALIVE_INTERVAL_SEC) continue;
        last = now;
        struct timespec t0, t1;
        clock_gettime(CLOCK_MONOTONIC, &t0);
        if (tun_send(tfd, T_PING, 0, NULL, 0) < 0) {
            uint8_t b = 1; write(wake_w, &b, 1);
            break;
        }
        long prev = pong;
        for (int us = 5000, el = 0; el < 3000000 && g_running; el += us) {
            if (atomic_load(&g_last_pong) > prev) break;
            usleep(us); if (us < 50000) us *= 2;
        }
        clock_gettime(CLOCK_MONOTONIC, &t1);
        long rtt = (t1.tv_sec - t0.tv_sec) * 1000 + (t1.tv_nsec - t0.tv_nsec) / 1000000;
        if (rtt > 0) push_log("I", "ping_ms=%ld", rtt);
    }
    return NULL;
}

static int make_relay_socket(int port) {
    int rfd = socket(AF_INET, SOCK_STREAM, 0);
    if (rfd < 0) return -1;
    int one = 1;
    setsockopt(rfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    setsockopt(rfd, SOL_SOCKET, SO_REUSEPORT, &one, sizeof(one));
    int fl = fcntl(rfd, F_GETFD, 0);
    if (fl >= 0) fcntl(rfd, F_SETFD, fl | FD_CLOEXEC);
    struct sockaddr_in la = {0};
    la.sin_family      = AF_INET;
    la.sin_port        = htons((uint16_t)port);
    la.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(rfd, (struct sockaddr *)&la, sizeof(la)) < 0 ||
        listen(rfd, RELAY_BACKLOG) < 0) {
        close(rfd); return -1;
    }
    fl = fcntl(rfd, F_GETFL, 0); fcntl(rfd, F_SETFL, fl | O_NONBLOCK);
    return rfd;
}

static void signal_ready(int st) {
    pthread_mutex_lock(&g_ready_mu);
    if (g_ready_st == READY_PENDING) {
        g_ready_st = st;
        pthread_cond_broadcast(&g_ready_cv);
    }
    pthread_mutex_unlock(&g_ready_mu);
}

static void *main_thread(void *arg) {
    int port     = (int)(intptr_t)arg;
    int is_first = 1;

    while (g_running) {
        int tfd = -1;
        tunnel_result_t tres = open_tunnel(&tfd);

        if (tres != TUNNEL_OK) {
            if (tres == TUNNEL_ERR_AUTH || tres == TUNNEL_ERR_EXPIRED) {
                notify_auth_error(tres == TUNNEL_ERR_EXPIRED);
                signal_ready(READY_AUTH_ERR);
                break;
            }

            signal_ready(READY_FAIL);
            break;
        }


        int rfd = make_relay_socket(port);
        if (rfd < 0) {
            push_log("E", "relay bind failed");
            close(tfd); sleep(2); continue;
        }

        int wfds[2] = {-1, -1};
        if (pipe(wfds) < 0) {
            close(rfd); close(tfd); sleep(1); continue;
        }
        fcntl(wfds[0], F_SETFL, O_NONBLOCK); fcntl(wfds[1], F_SETFL, O_NONBLOCK);
        fcntl(wfds[0], F_SETFD, FD_CLOEXEC); fcntl(wfds[1], F_SETFD, FD_CLOEXEC);

        int epfd = epoll_create1(EPOLL_CLOEXEC);
        if (epfd < 0) {
            close(wfds[0]); close(wfds[1]);
            close(rfd); close(tfd); sleep(1); continue;
        }

        struct epoll_event ev;
        ev.events = EPOLLIN; ev.data.fd = rfd;
        epoll_ctl(epfd, EPOLL_CTL_ADD, rfd, &ev);
        ev.events = EPOLLIN; ev.data.fd = wfds[0];
        epoll_ctl(epfd, EPOLL_CTL_ADD, wfds[0], &ev);

        int epoch = atomic_fetch_add(&g_tunnel_epoch, 1) + 1;

        pthread_mutex_lock(&g_mu);
        g_tun_fd   = tfd;
        g_relay_fd = rfd;
        g_epoll_fd = epfd;
        g_wake_r   = wfds[0];
        g_wake_w   = wfds[1];
        pthread_mutex_unlock(&g_mu);

        thr_t *ta = malloc(sizeof(*ta));
        thr_t *tb = malloc(sizeof(*tb));
        if (!ta || !tb) {
            free(ta); free(tb);
            close(epfd); close(wfds[0]); close(wfds[1]); close(rfd); close(tfd);
            sleep(1); continue;
        }
        ta->tfd = tfd; ta->epoch = epoch; ta->epfd = epfd; ta->wake_w = wfds[1];
        tb->tfd = tfd; tb->epoch = epoch; tb->epfd = epfd; tb->wake_w = wfds[1];

        pthread_t tr, tk;
        pthread_create(&tr, NULL, tunnel_reader, ta); pthread_detach(tr);
        pthread_create(&tk, NULL, keepalive,      tb); pthread_detach(tk);

        push_log("I", "relay port=%d epoch=%d", port, epoch);

        signal_ready(READY_OK);

        is_first = 0;

        struct epoll_event events[MAX_EPOLL_EVENTS];
        int dead = 0;

        while (g_running && !dead) {
            int n = epoll_wait(epfd, events, MAX_EPOLL_EVENTS, -1);
            if (n < 0) { if (errno == EINTR) continue; break; }

            for (int i = 0; i < n && !dead; i++) {
                int      efd = events[i].data.fd;
                uint32_t evs = events[i].events;

                if (efd == wfds[0]) { dead = 1; break; }

                if (efd == rfd) {
                    struct sockaddr_in ca; socklen_t cl = sizeof(ca);
                    int cfd = accept(rfd, (struct sockaddr *)&ca, &cl);
                    if (cfd < 0) continue;
                    int fdc = fcntl(cfd, F_GETFD, 0);
                    if (fdc >= 0) fcntl(cfd, F_SETFD, fdc | FD_CLOEXEC);

                    uint32_t sid;
                    do { sid = (uint32_t)atomic_fetch_add(&g_next_sid, 1) & 0x7FFFFFFF; }
                    while (!sid || ht_get(sid) != -1);

                    uint8_t buf[MAX_PAYLOAD];
                    ssize_t first = recv(cfd, buf, sizeof(buf), 0);
                    if (first <= 0) { close(cfd); continue; }

                    ht_put(sid, cfd);
                    if (tun_send(tfd, T_OPEN, sid, buf, (uint16_t)first) < 0) {
                        ht_del(sid); close(cfd); dead = 1; break;
                    }

                    struct epoll_event cev;
                    cev.events   = EPOLLIN;
                    cev.data.u64 = ((uint64_t)sid << 32) | (uint32_t)cfd;
                    if (epoll_ctl(epfd, EPOLL_CTL_ADD, cfd, &cev) < 0)
                        { ht_del(sid); close(cfd); }
                    continue;
                }

                uint32_t sid = (uint32_t)(events[i].data.u64 >> 32);
                int      cfd = (int)(uint32_t)events[i].data.u64;

                if (evs & (EPOLLERR | EPOLLHUP)) {
                    epoll_ctl(epfd, EPOLL_CTL_DEL, cfd, NULL);
                    ht_del(sid); close(cfd);
                    tun_send(tfd, T_CLOSE, sid, NULL, 0);
                    continue;
                }

                if (evs & EPOLLIN) {
                    uint8_t buf[MAX_PAYLOAD];
                    ssize_t n2 = recv(cfd, buf, sizeof(buf), 0);
                    if (n2 > 0) {
                        if (tun_send(tfd, T_DATA, sid, buf, (uint16_t)n2) < 0)
                            dead = 1;
                    } else if (n2 == 0) {
                        epoll_ctl(epfd, EPOLL_CTL_DEL, cfd, NULL);
                        ht_del(sid); close(cfd);
                        tun_send(tfd, T_CLOSE, sid, NULL, 0);
                    }
                }
            }
        }

        push_log("E", "tunnel dropped epoch=%d", epoch);

        atomic_fetch_add(&g_tunnel_epoch, 1);
        ht_close_all(epfd);

        pthread_mutex_lock(&g_mu);
        g_tun_fd = -1; g_relay_fd = -1; g_epoll_fd = -1;
        g_wake_r = -1; g_wake_w  = -1;
        pthread_mutex_unlock(&g_mu);

        close(epfd);
        close(rfd);
        shutdown(tfd, SHUT_RDWR);
        close(tfd);
        close(wfds[0]);
        close(wfds[1]);

        if (g_running) sleep(1);
    }

    return NULL;
}

JNIEXPORT void JNICALL Java_com_blacktunnel_BtProxy_nativeStop(JNIEnv *, jclass);

JNIEXPORT jint JNICALL
Java_com_blacktunnel_BtProxy_nativeStart(JNIEnv *env, jclass clazz,
                                          jint port, jobject svc, jstring iid) {
    pthread_mutex_lock(&g_mu);
    if (g_running) { pthread_mutex_unlock(&g_mu); return 0; }
    pthread_t old = g_main_thread;
    pthread_mutex_unlock(&g_mu);

    if (old != 0) {
        pthread_join(old, NULL);
        pthread_mutex_lock(&g_mu); g_main_thread = 0; pthread_mutex_unlock(&g_mu);
    }

    pthread_mutex_lock(&g_mu);
    (*env)->GetJavaVM(env, &g_jvm);
    g_svc = (*env)->NewGlobalRef(env, svc);
    g_internal_id[0] = 0;
    if (iid) {
        const char *s = (*env)->GetStringUTFChars(env, iid, NULL);
        if (s) { snprintf(g_internal_id, sizeof(g_internal_id), "%s", s);
                 (*env)->ReleaseStringUTFChars(env, iid, s); }
    }
    ht_init();
    g_running = 1;
    g_reconnect_delay = RECONNECT_DELAY_MIN;
    atomic_store(&g_next_sid, 1);
    pthread_mutex_unlock(&g_mu);

    pthread_mutex_lock(&g_ready_mu); g_ready_st = READY_PENDING; pthread_mutex_unlock(&g_ready_mu);

    pthread_t thr;
    if (pthread_create(&thr, NULL, main_thread, (void *)(intptr_t)port) != 0) {
        pthread_mutex_lock(&g_mu); g_running = 0;
        (*env)->DeleteGlobalRef(env, g_svc); g_svc = NULL; g_jvm = NULL;
        pthread_mutex_unlock(&g_mu); return -1;
    }
    pthread_mutex_lock(&g_mu); g_main_thread = thr; pthread_mutex_unlock(&g_mu);
    pthread_detach(thr);

    struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts); ts.tv_sec += 12;
    pthread_mutex_lock(&g_ready_mu);
    while (g_ready_st == READY_PENDING)
        if (pthread_cond_timedwait(&g_ready_cv, &g_ready_mu, &ts) != 0) break;
    int st = g_ready_st;
    pthread_mutex_unlock(&g_ready_mu);

    if (st == READY_OK) {
        push_log("I", "nativeStart ok");
        return 0;
    }

    Java_com_blacktunnel_BtProxy_nativeStop(env, clazz);
    return (st == READY_AUTH_ERR) ? -2 : -1;
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeStop(JNIEnv *env, jclass clazz) {
    (void)clazz;
    pthread_mutex_lock(&g_mu);
    if (!g_running) { pthread_mutex_unlock(&g_mu); return; }
    g_running = 0;
    g_internal_id[0] = 0;
    jobject svc = g_svc; g_svc = NULL; g_jvm = NULL;
    int rfd  = g_relay_fd;  g_relay_fd  = -1;
    int tfd  = g_tun_fd;    g_tun_fd    = -1;
    int epfd = g_epoll_fd;  g_epoll_fd  = -1;
    int wr   = g_wake_r;    g_wake_r    = -1;
    int ww   = g_wake_w;    g_wake_w    = -1;
    pthread_mutex_unlock(&g_mu);

    atomic_fetch_add(&g_tunnel_epoch, 1);

    if (ww   >= 0) { uint8_t b = 1; write(ww, &b, 1); }
    if (epfd >= 0) close(epfd);
    if (rfd  >= 0) { shutdown(rfd, SHUT_RDWR); close(rfd); }
    if (tfd  >= 0) { shutdown(tfd, SHUT_RDWR); close(tfd); }
    if (wr   >= 0) close(wr);
    if (ww   >= 0) close(ww);

    pthread_mutex_lock(&g_ready_mu);
    if (g_ready_st == READY_PENDING) {
        g_ready_st = READY_FAIL;
        pthread_cond_broadcast(&g_ready_cv);
    }
    pthread_mutex_unlock(&g_ready_mu);

    ht_close_all(-1);
    if (svc) (*env)->DeleteGlobalRef(env, svc);
}

JNIEXPORT jstring JNICALL
Java_com_blacktunnel_BtProxy_nativeDrainLogs(JNIEnv *env, jclass c) {
    (void)c;
    pthread_mutex_lock(&g_log_mu);
    if (!g_loglen) { pthread_mutex_unlock(&g_log_mu); return (*env)->NewStringUTF(env, ""); }
    char out[32768];
    memcpy(out, g_logbuf, g_loglen); out[g_loglen] = 0;
    g_loglen = 0; g_logbuf[0] = 0;
    pthread_mutex_unlock(&g_log_mu);
    return (*env)->NewStringUTF(env, out);
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeSetNetwork(JNIEnv *e, jclass c, jlong net) {
    (void)e; (void)c;
    pthread_mutex_lock(&g_mu); g_net = (net_handle_t)net; pthread_mutex_unlock(&g_mu);
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeSetGamingMode(JNIEnv *env, jclass clazz, jboolean enabled) {
    (void)env; (void)clazz;
    (void)env; (void)clazz; (void)enabled;
}

static JNINativeMethod g_methods[] = {
    {"nativeStart",        "(ILandroid/net/VpnService;Ljava/lang/String;)I",
                           (void *)Java_com_blacktunnel_BtProxy_nativeStart},
    {"nativeStop",         "()V",
                           (void *)Java_com_blacktunnel_BtProxy_nativeStop},
    {"nativeDrainLogs",    "()Ljava/lang/String;",
                           (void *)Java_com_blacktunnel_BtProxy_nativeDrainLogs},
    {"nativeSetNetwork",   "(J)V",
                           (void *)Java_com_blacktunnel_BtProxy_nativeSetNetwork},
    {"nativeSetGamingMode","(Z)V",
                           (void *)Java_com_blacktunnel_BtProxy_nativeSetGamingMode},
};

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *r) {
    (void)r;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = (*env)->FindClass(env, "com/blacktunnel/BtProxy");
    if (!cls) return JNI_ERR;
    if ((*env)->RegisterNatives(env, cls, g_methods,
            sizeof(g_methods) / sizeof(g_methods[0])) < 0) return JNI_ERR;
    (*env)->DeleteLocalRef(env, cls);
    return JNI_VERSION_1_6;
}
