#include <jni.h>
#include <android/log.h>
#include <android/multinetwork.h>
#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/epoll.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>
#include <poll.h>
#include <fcntl.h>
#include <signal.h>
#include <netdb.h>

#define lk pthread_mutex_lock
#define ul pthread_mutex_unlock

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
#define KEEPALIVE_INTERVAL_SEC 3
#define CONNECT_TIMEOUT_SEC    15
#define HANDSHAKE_TIMEOUT_SEC  4
#define MAX_EPOLL_EVENTS       64

#define LOCAL_QUEUE_HARD_LIMIT (512 * 1024)
#define WRITE_QUEUE_HIGH_WATER (512 * 1024)
#define WRITE_QUEUE_LOW_WATER  (128 * 1024)
#define STREAM_TIMEOUT_MS      2000000

#define PROXY_HOST  "emailmarketing.personal.com.ar"
#define PROXY_PORT  80
#define TUNNEL_HOST "2.brawlpass.com.ar"

static const char *PROXY_IPS[] = {
    "2606:4700::6812:16b7",
    "2606:4700::6812:17b7",
};
#define PROXY_IP_COUNT 2

static volatile int    g_r      = 0;
static int             g_rf     = -1;
static int             g_tf       = -1;
static int             g_ef     = -1;
static int             g_wr       = -1;
static int             g_ww       = -1;
static atomic_int      g_ns     = 1;
static atomic_int      g_te = 0;
static atomic_long     g_lp    = 0;
static atomic_long     g_lpt = 0;
static atomic_int      g_af = 0;
static char            g_i[160] = {0};
static JavaVM         *g_j          = NULL;
static jobject         g_s          = NULL;
static net_handle_t    g_n          = NETWORK_UNSPECIFIED;
static pthread_t       g_mt  = 0;
static pthread_mutex_t g_m           = PTHREAD_MUTEX_INITIALIZER;

static pthread_mutex_t g_lm  = PTHREAD_MUTEX_INITIALIZER;
static char            g_lb[32768];
static size_t          g_ll  = 0;
static long            nms(void);

static void pl(const char *lvl, const char *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    char msg[512]; vsnprintf(msg, sizeof(msg), fmt, ap); va_end(ap);
    if (lvl[0] == 'E') LOGE("%s", msg); else LOGI("%s", msg);
    lk(&g_lm);
    char line[560];
    int n = snprintf(line, sizeof(line), "%s %s\n", lvl, msg);
    if (n > 0) {
        if (g_ll + (size_t)n >= sizeof(g_lb)) {
            size_t drop = g_ll + n - sizeof(g_lb) + 1;
            memmove(g_lb, g_lb + drop, g_ll - drop);
            g_ll -= drop;
        }
        memcpy(g_lb + g_ll, line, n);
        g_ll += n;
        g_lb[g_ll] = 0;
    }
    ul(&g_lm);
}

#define HT_SIZE 4096
#define HT_MASK (HT_SIZE - 1)

typedef struct hn_s { struct hn_s *next; uint32_t sid; int cfd; } hn_t;
static hn_t           *g_h[HT_SIZE];
static pthread_mutex_t g_hm[HT_SIZE];
static int             g_hi = 0;

static void ht_init(void) {
    for (int i = 0; i < HT_SIZE; i++) {
        g_h[i] = NULL;
        if (!g_hi) pthread_mutex_init(&g_hm[i], NULL);
    }
    g_hi = 1;
}

static int ht_get(uint32_t sid) {
    int slot = sid & HT_MASK;
    lk(&g_hm[slot]);
    hn_t *n = g_h[slot];
    while (n && n->sid != sid) n = n->next;
    int cfd = n ? n->cfd : -1;
    ul(&g_hm[slot]);
    return cfd;
}

static void ht_put(uint32_t sid, int cfd) {
    hn_t *n = malloc(sizeof(*n));
    if (!n) return;
    n->sid = sid; n->cfd = cfd;
    int slot = sid & HT_MASK;
    lk(&g_hm[slot]);
    n->next = g_h[slot]; g_h[slot] = n;
    ul(&g_hm[slot]);
}

static void ht_del(uint32_t sid) {
    int slot = sid & HT_MASK;
    lk(&g_hm[slot]);
    hn_t **pp = &g_h[slot];
    while (*pp) {
        if ((*pp)->sid == sid) {
            hn_t *n = *pp; *pp = n->next; free(n); break;
        }
        pp = &(*pp)->next;
    }
    ul(&g_hm[slot]);
}

static void ht_close_all(int epfd) {
    for (int i = 0; i < HT_SIZE; i++) {
        lk(&g_hm[i]);
        hn_t *n = g_h[i];
        while (n) {
            hn_t *nx = n->next;
            if (epfd >= 0) epoll_ctl(epfd, EPOLL_CTL_DEL, n->cfd, NULL);
            close(n->cfd);
            free(n);
            n = nx;
        }
        g_h[i] = NULL;
        ul(&g_hm[i]);
    }
}

typedef struct chunk_s {
    struct chunk_s *next;
    size_t          len;
    size_t          offset;
    uint8_t         data[];
} chunk_t;

typedef struct { chunk_t *head; chunk_t *tail; size_t bytes; } chunkq_t;

static void cq_push(chunkq_t *q, const uint8_t *data, size_t len) {
    chunk_t *c = malloc(sizeof(chunk_t) + len);
    if (!c) return;
    c->next = NULL; c->len = len; c->offset = 0;
    memcpy(c->data, data, len);
    if (q->tail) q->tail->next = c; else q->head = c;
    q->tail = c; q->bytes += len;
}

static void cq_flush(chunkq_t *q) {
    chunk_t *c = q->head;
    while (c) { chunk_t *nx = c->next; free(c); c = nx; }
    q->head = q->tail = NULL; q->bytes = 0;
}

typedef struct sinfo_s {
    uint32_t  sid;
    int       cfd;
    chunkq_t  lq;
    int       ps;
    int       cp;
    int       pr;
    long      la;
} sinfo_t;

#define SI_SIZE 4096
#define SI_MASK (SI_SIZE - 1)

typedef struct si_hn_s { struct si_hn_s *next; uint32_t sid; sinfo_t *si; } si_hn_t;
static si_hn_t        *g_x[SI_SIZE];
static pthread_mutex_t g_xm[SI_SIZE];
static int             g_xi = 0;

static void si_init(void) {
    for (int i = 0; i < SI_SIZE; i++) {
        g_x[i] = NULL;
        if (!g_xi) pthread_mutex_init(&g_xm[i], NULL);
    }
    g_xi = 1;
}

static sinfo_t *si_get(uint32_t sid) {
    int slot = sid & SI_MASK;
    lk(&g_xm[slot]);
    si_hn_t *n = g_x[slot];
    while (n && n->sid != sid) n = n->next;
    sinfo_t *si = n ? n->si : NULL;
    ul(&g_xm[slot]);
    return si;
}

static void si_put(uint32_t sid, sinfo_t *si) {
    si_hn_t *n = malloc(sizeof(*n));
    if (!n) return;
    n->sid = sid; n->si = si;
    int slot = sid & SI_MASK;
    lk(&g_xm[slot]);
    n->next = g_x[slot]; g_x[slot] = n;
    ul(&g_xm[slot]);
}

static void si_del(uint32_t sid) {
    int slot = sid & SI_MASK;
    lk(&g_xm[slot]);
    si_hn_t **pp = &g_x[slot];
    while (*pp) {
        if ((*pp)->sid == sid) {
            si_hn_t *n = *pp; *pp = n->next; free(n); break;
        }
        pp = &(*pp)->next;
    }
    ul(&g_xm[slot]);
}

static void si_close_all(int epfd) {
    for (int i = 0; i < SI_SIZE; i++) {
        lk(&g_xm[i]);
        si_hn_t *n = g_x[i];
        while (n) {
            si_hn_t *nx = n->next;
            sinfo_t *si = n->si;
            if (epfd >= 0) epoll_ctl(epfd, EPOLL_CTL_DEL, si->cfd, NULL);
            shutdown(si->cfd, SHUT_RDWR);
            close(si->cfd);
            cq_flush(&si->lq);
            free(si); free(n);
            n = nx;
        }
        g_x[i] = NULL;
        ul(&g_xm[i]);
    }
}

typedef struct frame_s {
    struct frame_s *next;
    size_t          total;
    size_t          offset;
    uint8_t         data[];
} frame_t;

typedef struct { frame_t *head; frame_t *tail; size_t bytes; } frameq_t;

static frameq_t        g_wq;
static frame_t        *g_wp    = NULL;
static pthread_mutex_t g_wq_mu = PTHREAD_MUTEX_INITIALIZER;

static void wq_init(void) { g_wq.head = g_wq.tail = NULL; g_wq.bytes = 0; g_wp = NULL; }

static void wq_flush_locked(void) {
    if (g_wp) { free(g_wp); g_wp = NULL; }
    frame_t *f = g_wq.head;
    while (f) { frame_t *nx = f->next; free(f); f = nx; }
    g_wq.head = g_wq.tail = NULL; g_wq.bytes = 0;
}

static frame_t *frame_build(uint8_t type, uint32_t sid,
                             const uint8_t *data, uint16_t dlen) {
    size_t total = FRAME_HDR + dlen;
    frame_t *f = malloc(sizeof(frame_t) + total);
    if (!f) return NULL;
    f->next = NULL; f->total = total; f->offset = 0;
    f->data[0] = type;
    f->data[1] = (sid >> 24) & 0xFF; f->data[2] = (sid >> 16) & 0xFF;
    f->data[3] = (sid >>  8) & 0xFF; f->data[4] =  sid        & 0xFF;
    f->data[5] = (dlen >> 8) & 0xFF; f->data[6] =  dlen       & 0xFF;
    if (dlen && data) memcpy(f->data + FRAME_HDR, data, dlen);
    return f;
}

static int try_flush_wq(int tfd, int epfd) {
    if (g_wp) {
        while (g_wp->offset < g_wp->total) {
            ssize_t n = send(tfd, g_wp->data + g_wp->offset,
                             g_wp->total - g_wp->offset, MSG_NOSIGNAL);
            if (n > 0) { g_wp->offset += (size_t)n; }
            else if (errno == EAGAIN || errno == EWOULDBLOCK) {
                struct epoll_event ev = { EPOLLIN | EPOLLOUT, {.fd = tfd} };
                epoll_ctl(epfd, EPOLL_CTL_MOD, tfd, &ev);
                return 0;
            }
            else if (errno == EINTR) continue;
            else return -1;
        }
        free(g_wp); g_wp = NULL;
    }

    while (g_wq.head) {
        frame_t *f = g_wq.head;
        g_wq.bytes -= (f->total - f->offset);
        g_wq.head   = f->next;
        if (!g_wq.head) g_wq.tail = NULL;

        while (f->offset < f->total) {
            ssize_t n = send(tfd, f->data + f->offset,
                             f->total - f->offset, MSG_NOSIGNAL);
            if (n > 0) { f->offset += (size_t)n; }
            else if (errno == EAGAIN || errno == EWOULDBLOCK) {
                g_wp = f;
                struct epoll_event ev = { EPOLLIN | EPOLLOUT, {.fd = tfd} };
                epoll_ctl(epfd, EPOLL_CTL_MOD, tfd, &ev);
                return 0;
            }
            else if (errno == EINTR) continue;
            else { free(f); return -1; }
        }
        free(f);
    }

    struct epoll_event ev = { EPOLLIN, {.fd = tfd} };
    epoll_ctl(epfd, EPOLL_CTL_MOD, tfd, &ev);
    return 0;
}

static int tun_enqueue(int tfd, int epfd, uint8_t type, uint32_t sid,
                        const uint8_t *data, uint16_t dlen) {
    frame_t *f = frame_build(type, sid, data, dlen);
    if (!f) return -1;
    lk(&g_wq_mu);
    if (g_wq.tail) g_wq.tail->next = f; else g_wq.head = f;
    g_wq.tail = f; g_wq.bytes += f->total;
    int r = try_flush_wq(tfd, epfd);
    ul(&g_wq_mu);
    return r;
}

static void protect_fd(int fd) {
    lk(&g_m);
    net_handle_t net = g_n;
    JavaVM *jvm = g_j; jobject svc = g_s;
    ul(&g_m);
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

static void parse_hdr(const char *hdrs, const char *key, char *out, size_t out_cap) {
    if (!hdrs || !key || !out || out_cap == 0) return;
    out[0] = 0;
    size_t klen = strlen(key);
    const char *p = hdrs;
    while (*p) {
        const char *eol = strstr(p, "\r\n");
        size_t len = eol ? (size_t)(eol - p) : strlen(p);
        if (len >= klen && strncasecmp(p, key, klen) == 0) {
            const char *v = p + klen;
            while (*v == ' ' || *v == '\t') v++;
            size_t vlen = len - (size_t)(v - p);
            if (vlen >= out_cap) vlen = out_cap - 1;
            memcpy(out, v, vlen); out[vlen] = 0;
            return;
        }
        if (!eol) break;
        p = eol + 2;
    }
}

static int try_connect_ip(const char *ip, int timeout_ms) {
    struct sockaddr_in6 a = {0};
    a.sin6_family = AF_INET6; a.sin6_port = htons(PROXY_PORT);
    if (inet_pton(AF_INET6, ip, &a.sin6_addr) != 1) return -1;
    int fd = socket(AF_INET6, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    protect_fd(fd);
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY,   &one, sizeof(one));
    setsockopt(fd, SOL_SOCKET,  SO_KEEPALIVE,  &one, sizeof(one));
    int v;
    v = 30;     setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE,  &v, sizeof(v));
    v = 10;     setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &v, sizeof(v));
    v = 3;      setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT,   &v, sizeof(v));
    v = 524288; setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &v, sizeof(v));
    v = 524288; setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &v, sizeof(v));
    int fl = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, fl | O_NONBLOCK);
    fcntl(fd, F_SETFD, FD_CLOEXEC);
    int r = connect(fd, (struct sockaddr *)&a, sizeof(a));
    if (r == 0) { fcntl(fd, F_SETFL, fl); return fd; }
    if (errno != EINPROGRESS) { close(fd); return -1; }
    struct pollfd p = {fd, POLLOUT, 0};
    if (poll(&p, 1, timeout_ms) <= 0) { close(fd); return -1; }
    int e = 0; socklen_t el = sizeof(e);
    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &e, &el) < 0 || e != 0)
        { close(fd); return -1; }
    fcntl(fd, F_SETFL, fl);
    return fd;
}

static int try_cf_fallback(int timeout_ms, int *tunnel_ready) {
    const char *fallback_domain = "recarga.personal.com.ar";
    const char *fallback_cf_host = "dif2pyjxd7k7p.cloudfront.net";

    struct addrinfo hints = {0}, *res = NULL, *cur = NULL;
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;

    char port_str[8];
    snprintf(port_str, sizeof(port_str), "%d", PROXY_PORT);
    if (getaddrinfo(fallback_domain, port_str, &hints, &res) != 0) {
        pl("E", "fallback getaddrinfo fallo");
        if (tunnel_ready) *tunnel_ready = 0;
        return -1;
    }

    int fd = -1;
    int dns_try = 0;
    for (cur = res; cur && fd < 0; cur = cur->ai_next) {
        if (cur->ai_family != AF_INET || !cur->ai_addr) continue;
        dns_try++;
        struct sockaddr_in *a4 = (struct sockaddr_in *)cur->ai_addr;
        char ipbuf[INET_ADDRSTRLEN] = {0};
        inet_ntop(AF_INET, &a4->sin_addr, ipbuf, sizeof(ipbuf));
        pl("I", "proxy intento=fallback_dns4_%d ip=%s", dns_try, ipbuf);

        fd = socket(AF_INET, SOCK_STREAM, 0);
        if (fd < 0) continue;
        protect_fd(fd);

        int one = 1;
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
        setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof(one));

        int fl = fcntl(fd, F_GETFL, 0);
        fcntl(fd, F_SETFL, fl | O_NONBLOCK);
        fcntl(fd, F_SETFD, FD_CLOEXEC);

        int r = connect(fd, cur->ai_addr, cur->ai_addrlen);
        if (r != 0 && errno == EINPROGRESS) {
            struct pollfd p = {fd, POLLOUT, 0};
            if (poll(&p, 1, timeout_ms) > 0) {
                int e = 0;
                socklen_t el = sizeof(e);
                if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &e, &el) < 0 || e != 0) r = -1;
            } else r = -1;
        }
        if (r != 0) {
            close(fd);
            fd = -1;
            continue;
        }

        fcntl(fd, F_SETFL, fl);

        char req1[256];
        snprintf(req1, sizeof(req1), "HEAD http://%s HTTP/1.1\r\nHost: %s\r\n\r\n", fallback_domain, fallback_domain);
        send(fd, req1, strlen(req1), MSG_NOSIGNAL);

        char h1[2048];
        if (recv_eoh(fd, h1, sizeof(h1), HANDSHAKE_TIMEOUT_SEC) < 0) {
            close(fd);
            fd = -1;
            continue;
        }

        char req2[1024];
        snprintf(req2, sizeof(req2),
                 "PACHTS http://%s HTTP/1.1\r\nHost: %s\r\n\r\n"
                 "GET htt://%s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\n"
                 "Connection: Upgrade\r\nAction: tunnel\r\nX-Internal-ID: %s\r\n\r\n",
                 fallback_domain, fallback_domain, fallback_cf_host, fallback_cf_host, g_i[0] ? g_i : "unknown");
        send(fd, req2, strlen(req2), MSG_NOSIGNAL);

        char h2[4096];
        int hlen = recv_eoh(fd, h2, sizeof(h2), HANDSHAKE_TIMEOUT_SEC);
        int code = -1;
        if (hlen > 0) sscanf(h2, "HTTP/%*d.%*d %d", &code);
        if (hlen < 0 || code != 101) {
            pl("W", "fallback handshake failed code=%d", code);
            close(fd);
            fd = -1;
            continue;
        }

        pl("I", "fallback handshake ok");
        if (tunnel_ready) *tunnel_ready = 1;
    }

    freeaddrinfo(res);
    return fd;
}

static int open_tunnel(void) {
    pl("I", "stage=proxy_connect_start");
    pl("I", "conectando...");

    int fd = -1;
    for (int i = 0; i < PROXY_IP_COUNT && fd < 0; i++) {
        pl("I", "proxy intento=proxy_%d", i + 1);
        fd = try_connect_ip(PROXY_IPS[i], 300);
    }

    if (fd < 0) {
        pl("W", "stage=proxy_static_failed");
        pl("I", "IPs estaticas fallaron, resolviendo %s", PROXY_HOST);
        struct addrinfo hints = {0}, *res = NULL, *cur;
        hints.ai_family   = AF_INET6;
        hints.ai_socktype = SOCK_STREAM;
        char port_str[8];
        snprintf(port_str, sizeof(port_str), "%d", PROXY_PORT);
        if (getaddrinfo(PROXY_HOST, port_str, &hints, &res) == 0) {
            int dns_try = 0;
            for (cur = res; cur && fd < 0; cur = cur->ai_next) {
                dns_try++;
                char ipbuf[INET6_ADDRSTRLEN] = {0};
                struct sockaddr_in6 *a6 = (struct sockaddr_in6 *)cur->ai_addr;
                inet_ntop(AF_INET6, &a6->sin6_addr, ipbuf, sizeof(ipbuf));
                pl("I", "proxy intento=dns_%d", dns_try);
                fd = try_connect_ip(ipbuf, 300);
            }
            freeaddrinfo(res);
        } else {
            pl("E", "getaddrinfo fallo");
        }
    }

    int used_fallback_tunnel = 0;
    if (fd < 0) {
        pl("W", "stage=proxy_dns_failed");
        pl("I", "intentando fallback cloudfront");
        fd = try_cf_fallback(800, &used_fallback_tunnel);
    }

    if (fd < 0) { pl("E", "stage=proxy_connect_failed"); pl("E", "connect failed"); return -1; }

    if (used_fallback_tunnel) {
        pl("I", "stage=proxy_connected");
        pl("I", "stage=access_granted");
        pl("I", "tunnel ok");
        atomic_store(&g_lp, (long)time(NULL));
        fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) | O_NONBLOCK);
        return fd;
    }

    char buf[4096];
    snprintf(buf, sizeof(buf), "GET / HTTP/1.1\r\nHost: %s\r\n\r\n", PROXY_HOST);
    send(fd, buf, strlen(buf), MSG_NOSIGNAL);
    pl("I", "stage=proxy_connected");
    if (recv_eoh(fd, buf, sizeof(buf), HANDSHAKE_TIMEOUT_SEC) < 0) {
        pl("E", "stage=proxy_no_response"); pl("E", "proxy no responde"); close(fd); return -1;
    }

    char req[1024];
    snprintf(req, sizeof(req),
        "- / HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\n"
        "Connection: Upgrade\r\nAction: tunnel\r\nX-Internal-ID: %s\r\n\r\n",
        TUNNEL_HOST, g_i[0] ? g_i : "unknown");
    pl("I", "stage=server_auth_request");
    send(fd, req, strlen(req), MSG_NOSIGNAL);

    char h2[4096];
    int hlen = recv_eoh(fd, h2, sizeof(h2), HANDSHAKE_TIMEOUT_SEC);
    int code = -1;
    sscanf(h2, "HTTP/%*d.%*d %d", &code);

    if (hlen < 0 || code != 101) {
        if (code == 403 || code == 401 || code == 410) {
            atomic_store(&g_af, 1);
            if (code == 410) pl("E", "expired");
            else pl("E", "not_registered");
            pl("E", "stage=auth_rejected");
        }
        pl("E", "handshake failed code=%d", code);
        close(fd); return -1;
    }

    char uname[128] = {0}, udays[32] = {0};
    parse_hdr(h2, "X-User-Name:", uname, sizeof(uname));
    parse_hdr(h2, "X-User-Days:", udays, sizeof(udays));
    if (uname[0]) pl("I", "user_name=%s", uname);
    if (udays[0]) pl("I", "user_days=%s", udays);
    pl("I", "stage=access_granted");
    pl("I", "tunnel ok");
    atomic_store(&g_lp, (long)time(NULL));

    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) | O_NONBLOCK);
    return fd;
}

typedef struct { int tfd; int epoch; int epfd; int wake_w; } thr_t;

static void *tunnel_reader(void *arg) {
    thr_t *ta = (thr_t *)arg;
    int tfd = ta->tfd, epoch = ta->epoch, epfd = ta->epfd, wake_w = ta->wake_w;
    free(ta);

    uint8_t hdr[FRAME_HDR], payload[MAX_PAYLOAD];

    while (g_r && atomic_load(&g_te) == epoch) {
        int rc = tun_recv_full(tfd, hdr, FRAME_HDR, 60000);
        if (!g_r || atomic_load(&g_te) != epoch) break;
        if (rc == -2) {
            if ((long)time(NULL) - atomic_load(&g_lp) > PONG_TIMEOUT_SEC) {
                pl("E", "pong timeout");
                if (atomic_load(&g_te) == epoch) {
                    uint8_t b = 1; write(wake_w, &b, 1);
                }
            }
            continue;
        }
        if (rc < 0) {
            pl("E", "tunnel read failed");
            if (atomic_load(&g_te) == epoch) {
                uint8_t b = 1; write(wake_w, &b, 1);
            }
            break;
        }

        uint8_t  ft  = hdr[0];
        uint32_t sid = ((uint32_t)hdr[1] << 24) | ((uint32_t)hdr[2] << 16) |
                       ((uint32_t)hdr[3] <<  8) |  (uint32_t)hdr[4];
        uint16_t len = ((uint16_t)hdr[5] << 8) | hdr[6];

        if (len > MAX_PAYLOAD) {
            pl("E", "payload too large");
            if (atomic_load(&g_te) == epoch) {
                uint8_t b = 1; write(wake_w, &b, 1);
            }
            break;
        }
        if (len > 0 && tun_recv_full(tfd, payload, len, 30000) < 0) {
            pl("E", "payload read failed");
            if (atomic_load(&g_te) == epoch) {
                uint8_t b = 1; write(wake_w, &b, 1);
            }
            break;
        }

        switch (ft) {
        case T_DATA: {
            if (len == 0) break;
            sinfo_t *si = si_get(sid);
            if (!si) break;

            si->la = nms();

            if (si->lq.bytes + len > LOCAL_QUEUE_HARD_LIMIT) {
                tun_enqueue(tfd, epfd, T_CLOSE, sid, NULL, 0);
                epoll_ctl(epfd, EPOLL_CTL_DEL, si->cfd, NULL);
                ht_del(sid); si_del(sid);
                close(si->cfd); cq_flush(&si->lq); free(si);
                break;
            }

            if (si->ps) {
                cq_push(&si->lq, payload, len);
                break;
            }

            size_t off = 0;
            int stream_dead = 0;
            while (off < len && !stream_dead) {
                ssize_t n = send(si->cfd, payload + off, len - off, MSG_NOSIGNAL);
                if (n > 0) { off += (size_t)n; continue; }
                if (errno == EINTR) continue;
                if (errno == EAGAIN || errno == EWOULDBLOCK) {
                    cq_push(&si->lq, payload + off, len - off);
                    si->ps = 1;
                    struct epoll_event cev;
                    cev.events   = EPOLLIN | EPOLLOUT;
                    cev.data.u64 = ((uint64_t)sid << 32) | (uint32_t)si->cfd;
                    epoll_ctl(epfd, EPOLL_CTL_MOD, si->cfd, &cev);
                    break;
                }
                tun_enqueue(tfd, epfd, T_CLOSE, sid, NULL, 0);
                epoll_ctl(epfd, EPOLL_CTL_DEL, si->cfd, NULL);
                ht_del(sid); si_del(sid);
                close(si->cfd); cq_flush(&si->lq); free(si);
                stream_dead = 1;
            }
            break;
        }
        case T_CLOSE: {
            sinfo_t *si = si_get(sid);
            if (si) {
                if (si->lq.head) {
                    si->cp = 1;
                } else {
                    shutdown(si->cfd, SHUT_RDWR);
                }
            } else {
                int cfd = ht_get(sid);
                if (cfd >= 0) shutdown(cfd, SHUT_RDWR);
            }
            break;
        }
        case T_PING:
            tun_enqueue(tfd, epfd, T_PONG, 0, NULL, 0);
            break;
        case T_PONG: {
            atomic_store(&g_lp, (long)time(NULL));
            long sent = atomic_load(&g_lpt);
            if (sent > 0) {
                long rtt = nms() - sent;
                if (rtt >= 0 && rtt < 10000) pl("I", "ping_ms=%ld", rtt);
            }
            break;
        }
        }
    }
    return NULL;
}

static void *keepalive(void *arg) {
    thr_t *ta = (thr_t *)arg;
    int tfd = ta->tfd, epoch = ta->epoch, epfd = ta->epfd, wake_w = ta->wake_w;
    free(ta);

    long last = time(NULL);
    while (g_r && atomic_load(&g_te) == epoch) {
        sleep(1);
        if (!g_r || atomic_load(&g_te) != epoch) break;
        long now = time(NULL);
        if (now - atomic_load(&g_lp) > PONG_TIMEOUT_SEC) {
            pl("E", "pong timeout keepalive");
            if (atomic_load(&g_te) == epoch) {
                uint8_t b = 1; write(wake_w, &b, 1);
            }
            break;
        }
        if (now - last < KEEPALIVE_INTERVAL_SEC) continue;
        last = now;
        atomic_store(&g_lpt, nms());
        if (tun_enqueue(tfd, epfd, T_PING, 0, NULL, 0) < 0) {
            if (atomic_load(&g_te) == epoch) {
                uint8_t b = 1; write(wake_w, &b, 1);
            }
            break;
        }
    }
    return NULL;
}

static int make_relay_socket(int port) {
    int rfd = socket(AF_INET, SOCK_STREAM, 0);
    if (rfd < 0) return -1;
    int one = 1;
    setsockopt(rfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    setsockopt(rfd, SOL_SOCKET, SO_REUSEPORT, &one, sizeof(one));
    fcntl(rfd, F_SETFD, FD_CLOEXEC);
    struct sockaddr_in la = {0};
    la.sin_family      = AF_INET;
    la.sin_port        = htons((uint16_t)port);
    la.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(rfd, (struct sockaddr *)&la, sizeof(la)) < 0 ||
        listen(rfd, RELAY_BACKLOG) < 0) {
        close(rfd); return -1;
    }
    fcntl(rfd, F_SETFL, fcntl(rfd, F_GETFL, 0) | O_NONBLOCK);
    return rfd;
}

static void resume_prs(int epfd) {
    for (int i = 0; i < SI_SIZE; i++) {
        lk(&g_xm[i]);
        si_hn_t *n = g_x[i];
        while (n) {
            sinfo_t *si = n->si;
            if (si->pr) {
                si->pr = 0;
                struct epoll_event cev;
                cev.events   = EPOLLIN;
                cev.data.u64 = ((uint64_t)si->sid << 32) | (uint32_t)si->cfd;
                epoll_ctl(epfd, EPOLL_CTL_MOD, si->cfd, &cev);
            }
            n = n->next;
        }
        ul(&g_xm[i]);
    }
}

static void *main_thread(void *arg) {
    int port = (int)(intptr_t)arg;
    signal(SIGPIPE, SIG_IGN);

    while (g_r) {
        atomic_store(&g_af, 0);
        int tfd = open_tunnel();
        if (tfd < 0) {
            if (!g_r) break;
            if (atomic_load(&g_af)) {
                pl("W", "stage=manual_reconnect_required");
                break;
            }
            sleep(3);
            continue;
        }

        int rfd = make_relay_socket(port);
        if (rfd < 0) {
            pl("E", "relay bind failed");
            close(tfd); sleep(2); continue;
        }

        int wfds[2] = {-1, -1};
        if (pipe(wfds) < 0) { close(rfd); close(tfd); sleep(1); continue; }
        fcntl(wfds[0], F_SETFL, O_NONBLOCK); fcntl(wfds[1], F_SETFL, O_NONBLOCK);
        fcntl(wfds[0], F_SETFD, FD_CLOEXEC); fcntl(wfds[1], F_SETFD, FD_CLOEXEC);

        int epfd = epoll_create1(EPOLL_CLOEXEC);
        if (epfd < 0) {
            close(wfds[0]); close(wfds[1]); close(rfd); close(tfd);
            sleep(1); continue;
        }

        struct epoll_event ev;
        ev.events = EPOLLIN; ev.data.fd = rfd;
        epoll_ctl(epfd, EPOLL_CTL_ADD, rfd, &ev);
        ev.events = EPOLLIN; ev.data.fd = wfds[0];
        epoll_ctl(epfd, EPOLL_CTL_ADD, wfds[0], &ev);
        ev.events = EPOLLIN; ev.data.fd = tfd;
        epoll_ctl(epfd, EPOLL_CTL_ADD, tfd, &ev);

        int epoch = atomic_fetch_add(&g_te, 1) + 1;

        lk(&g_m);
        g_tf = tfd; g_rf = rfd;
        g_ef = epfd; g_wr = wfds[0]; g_ww = wfds[1];
        ul(&g_m);

        wq_init();

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

        pl("I", "relay listo port=%d epoch=%d", port, epoch);
        struct epoll_event events[MAX_EPOLL_EVENTS];
        int dead = 0;
        long last_to_check = nms();

        while (g_r && !dead) {
            int n = epoll_wait(epfd, events, MAX_EPOLL_EVENTS, 5000);
            if (n < 0) { if (errno == EINTR) continue; break; }

            long now = nms();
            if (now - last_to_check > 15000) {
                last_to_check = now;
                for (int i = 0; i < SI_SIZE; i++) {
                    lk(&g_xm[i]);
                    si_hn_t **pp = &g_x[i];
                    while (*pp) {
                        sinfo_t *si = (*pp)->si;
                        if (now - si->la > STREAM_TIMEOUT_MS) {
                            si_hn_t *dead_n = *pp;
                            *pp = dead_n->next;
                            epoll_ctl(epfd, EPOLL_CTL_DEL, si->cfd, NULL);
                            ht_del(si->sid);
                            close(si->cfd); cq_flush(&si->lq); free(si); free(dead_n);
                            tun_enqueue(tfd, epfd, T_CLOSE, si->sid, NULL, 0);
                        } else {
                            pp = &(*pp)->next;
                        }
                    }
                    ul(&g_xm[i]);
                }
            }

            for (int i = 0; i < n && !dead; i++) {
                int      efd = events[i].data.fd;
                uint32_t evs = events[i].events;

                if (efd == wfds[0]) { dead = 1; break; }

                if (efd == tfd) {
                    if (evs & EPOLLOUT) {
                        lk(&g_wq_mu);
                        int r = try_flush_wq(tfd, epfd);
                        size_t wq_bytes = g_wq.bytes;
                        ul(&g_wq_mu);
                        if (r < 0) { dead = 1; break; }
                        if (wq_bytes < WRITE_QUEUE_LOW_WATER)
                            resume_prs(epfd);
                    }
                    if (evs & (EPOLLHUP | EPOLLERR)) { dead = 1; break; }
                    continue;
                }

                if (efd == rfd) {
                    while (1) {
                        struct sockaddr_in ca; socklen_t cl = sizeof(ca);
                        int cfd = accept4(rfd, (struct sockaddr *)&ca, &cl,
                                          SOCK_NONBLOCK | SOCK_CLOEXEC);
                        if (cfd < 0) break;

                        uint32_t sid;
                        do { sid = (uint32_t)atomic_fetch_add(&g_ns, 1) & 0x7FFFFFFF; }
                        while (!sid || ht_get(sid) != -1);

                        sinfo_t *si = calloc(1, sizeof(sinfo_t));
                        if (!si) { close(cfd); continue; }
                        si->sid            = sid;
                        si->cfd            = cfd;
                        si->la = nms();

                        ht_put(sid, cfd);
                        si_put(sid, si);

                        if (tun_enqueue(tfd, epfd, T_OPEN, sid, NULL, 0) < 0) {
                            ht_del(sid); si_del(sid); close(cfd); free(si);
                            dead = 1; break;
                        }

                        struct epoll_event cev;
                        cev.events   = EPOLLIN;
                        cev.data.u64 = ((uint64_t)sid << 32) | (uint32_t)cfd;
                        if (epoll_ctl(epfd, EPOLL_CTL_ADD, cfd, &cev) < 0) {
                            ht_del(sid); si_del(sid); close(cfd); free(si);
                        }
                    }
                    continue;
                }

                uint32_t sid = (uint32_t)(events[i].data.u64 >> 32);
                int      cfd = (int)(uint32_t)events[i].data.u64;
                sinfo_t *si  = si_get(sid);

                if (evs & (EPOLLERR | EPOLLHUP)) {
                    epoll_ctl(epfd, EPOLL_CTL_DEL, cfd, NULL);
                    ht_del(sid);
                    if (si) { cq_flush(&si->lq); si_del(sid); free(si); }
                    close(cfd);
                    tun_enqueue(tfd, epfd, T_CLOSE, sid, NULL, 0);
                    continue;
                }

                if ((evs & EPOLLOUT) && si && si->ps) {
                    int drain_done = 0;
                    while (si->lq.head) {
                        chunk_t *c = si->lq.head;
                        ssize_t ns = send(cfd, c->data + c->offset,
                                          c->len - c->offset, MSG_NOSIGNAL);
                        if (ns > 0) {
                            c->offset += (size_t)ns; si->lq.bytes -= (size_t)ns;
                            if (c->offset >= c->len) {
                                si->lq.head = c->next;
                                if (!si->lq.head) si->lq.tail = NULL;
                                free(c);
                            }
                        } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
                            break;
                        } else if (errno == EINTR) {
                            continue;
                        } else {
                            epoll_ctl(epfd, EPOLL_CTL_DEL, cfd, NULL);
                            ht_del(sid); cq_flush(&si->lq); si_del(sid); free(si);
                            close(cfd);
                            tun_enqueue(tfd, epfd, T_CLOSE, sid, NULL, 0);
                            drain_done = -1; break;
                        }
                    }
                    if (drain_done < 0) continue;

                    if (!si->lq.head) {
                        si->lq.bytes = 0; si->lq.tail = NULL;
                        si->ps = 0;
                        si->la = nms();
                        if (si->cp) {
                            shutdown(cfd, SHUT_RDWR);
                        } else {
                            struct epoll_event cev;
                            cev.events   = EPOLLIN;
                            cev.data.u64 = ((uint64_t)sid << 32) | (uint32_t)cfd;
                            epoll_ctl(epfd, EPOLL_CTL_MOD, cfd, &cev);
                        }
                    }
                }

                if (evs & EPOLLIN) {
                    uint8_t buf[MAX_PAYLOAD];
                    ssize_t nr = recv(cfd, buf, sizeof(buf), 0);
                    if (nr > 0) {
                        if (si) si->la = nms();

                        lk(&g_wq_mu);
                        size_t wq_total = g_wq.bytes + (g_wp ? g_wp->total - g_wp->offset : 0);
                        ul(&g_wq_mu);

                        if (wq_total > WRITE_QUEUE_HIGH_WATER && si) {
                            si->pr = 1;
                            struct epoll_event cev;
                            cev.events   = 0;
                            cev.data.u64 = ((uint64_t)sid << 32) | (uint32_t)cfd;
                            epoll_ctl(epfd, EPOLL_CTL_MOD, cfd, &cev);
                        } else {
                            if (tun_enqueue(tfd, epfd, T_DATA, sid, buf, (uint16_t)nr) < 0)
                                dead = 1;
                        }
                    } else if (nr == 0) {
                        epoll_ctl(epfd, EPOLL_CTL_DEL, cfd, NULL);
                        ht_del(sid);
                        if (si) { cq_flush(&si->lq); si_del(sid); free(si); }
                        close(cfd);
                        tun_enqueue(tfd, epfd, T_CLOSE, sid, NULL, 0);
                    }
                }
            }
        }

        pl("E", "tunnel caido epoch=%d", epoch);

        atomic_fetch_add(&g_te, 1);
        si_close_all(epfd);
        ht_close_all(-1);

        lk(&g_wq_mu);
        wq_flush_locked();
        ul(&g_wq_mu);

        lk(&g_m);
        g_tf = -1; g_rf = -1; g_ef = -1;
        g_wr = -1; g_ww   = -1;
        ul(&g_m);

        close(epfd);
        close(rfd);
        shutdown(tfd, SHUT_RDWR); close(tfd);
        close(wfds[0]); close(wfds[1]);

        if (g_r) sleep(3);
    }

    return NULL;
}

JNIEXPORT void JNICALL n_stop(JNIEnv *, jclass);

JNIEXPORT jint JNICALL
n_start(JNIEnv *env, jclass clazz,
                                          jint port, jobject svc, jstring iid) {
    (void)clazz;
    lk(&g_m);
    if (g_r) { ul(&g_m); return 0; }
    pthread_t old = g_mt;
    ul(&g_m);

    if (old != 0) {
        pthread_join(old, NULL);
        lk(&g_m); g_mt = 0; ul(&g_m);
    }

    lk(&g_m);
    (*env)->GetJavaVM(env, &g_j);
    g_s = (*env)->NewGlobalRef(env, svc);
    g_i[0] = 0;
    if (iid) {
        const char *s = (*env)->GetStringUTFChars(env, iid, NULL);
        if (s) { snprintf(g_i, sizeof(g_i), "%s", s);
                 (*env)->ReleaseStringUTFChars(env, iid, s); }
    }
    ht_init();
    si_init();
    g_r = 1;
    atomic_store(&g_ns, 1);
    ul(&g_m);

    pthread_t thr;
    if (pthread_create(&thr, NULL, main_thread, (void *)(intptr_t)port) != 0) {
        lk(&g_m); g_r = 0;
        (*env)->DeleteGlobalRef(env, g_s); g_s = NULL; g_j = NULL;
        ul(&g_m); return -1;
    }
    lk(&g_m); g_mt = thr; ul(&g_m);

    pl("I", "nativeStart lanzado");
    return 0;
}

JNIEXPORT void JNICALL
n_stop(JNIEnv *env, jclass clazz) {
    (void)clazz;
    lk(&g_m);
    if (!g_r && g_mt == 0) { ul(&g_m); return; }
    pthread_t th = g_mt;
    g_mt = 0;
    g_r = 0;
    g_i[0] = 0;
    jobject svc = g_s; g_s = NULL; g_j = NULL;
    int rfd  = g_rf;  g_rf  = -1;
    int tfd  = g_tf;    g_tf    = -1;
    int epfd = g_ef;  g_ef  = -1;
    int wr   = g_wr;    g_wr    = -1;
    int ww   = g_ww;    g_ww    = -1;
    ul(&g_m);

    atomic_fetch_add(&g_te, 1);

    if (ww   >= 0) { uint8_t b = 1; write(ww, &b, 1); }
    if (epfd >= 0) close(epfd);
    if (rfd  >= 0) { shutdown(rfd, SHUT_RDWR); close(rfd); }
    if (tfd  >= 0) { shutdown(tfd, SHUT_RDWR); close(tfd); }
    if (wr   >= 0) close(wr);
    if (ww   >= 0) close(ww);

    si_close_all(-1);
    ht_close_all(-1);

    lk(&g_wq_mu);
    wq_flush_locked();
    ul(&g_wq_mu);

    if (th) pthread_join(th, NULL);
    if (svc) (*env)->DeleteGlobalRef(env, svc);
}

JNIEXPORT jstring JNICALL
n_drain(JNIEnv *env, jclass c) {
    (void)c;
    lk(&g_lm);
    if (!g_ll) { ul(&g_lm); return (*env)->NewStringUTF(env, ""); }
    char out[32768];
    memcpy(out, g_lb, g_ll); out[g_ll] = 0;
    g_ll = 0; g_lb[0] = 0;
    ul(&g_lm);
    return (*env)->NewStringUTF(env, out);
}

JNIEXPORT void JNICALL
n_net(JNIEnv *e, jclass c, jlong net) {
    (void)e; (void)c;
    lk(&g_m); g_n = (net_handle_t)net; ul(&g_m);
}

static JNINativeMethod g_methods[] = {
    {"nativeStart",      "(ILandroid/net/VpnService;Ljava/lang/String;)I",
                         (void *)n_start},
    {"nativeStop",       "()V",
                         (void *)n_stop},
    {"nativeDrainLogs",  "()Ljava/lang/String;",
                         (void *)n_drain},
    {"nativeSetNetwork", "(J)V",
                         (void *)n_net},
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

static long nms(void) {
    struct timespec ts = {0};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)(ts.tv_sec * 1000L + ts.tv_nsec / 1000000L);
}
