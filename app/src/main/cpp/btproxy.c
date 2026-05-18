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
#include <sys/epoll.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>
#include <poll.h>
#include <fcntl.h>
#include <netdb.h>

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
#define CONNECT_TIMEOUT_SEC    15
#define HANDSHAKE_TIMEOUT_SEC  4

#define PROXY_HOST  "emailmarketing.personal.com.ar"
#define PROXY_PORT  80
#define TUNNEL_HOST "2.brawlpass.com.ar"

#define TUNNEL_OK       0
#define TUNNEL_ERR_NET  -1
#define TUNNEL_ERR_AUTH -2

static const char *STATIC_IPS[] = {
    "2606:4700::6812:16b7",
    "2606:4700::6812:17b7",
};
#define STATIC_IP_COUNT 2

static pthread_mutex_t g_mu        = PTHREAD_MUTEX_INITIALIZER;
static volatile int    g_running   = 0;
static int             g_tfd       = -1;
static int             g_rfd       = -1;
static int             g_epfd      = -1;
static int             g_wake_r    = -1;
static int             g_wake_w    = -1;
static pthread_t       g_thread    = 0;
static atomic_int      g_next_sid  = 1;
static atomic_long     g_last_pong = 0;
static char            g_iid[160]  = {0};
static JavaVM         *g_jvm       = NULL;
static jobject         g_svc       = NULL;
static net_handle_t    g_net       = NETWORK_UNSPECIFIED;

static atomic_int      g_tunnel_ready = 0;
static pthread_mutex_t g_ready_mu     = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_ready_cv     = PTHREAD_COND_INITIALIZER;

static pthread_mutex_t g_log_mu = PTHREAD_MUTEX_INITIALIZER;
static char            g_logbuf[32768];
static size_t          g_loglen = 0;

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

static void signal_ready(int code) {
    atomic_store(&g_tunnel_ready, code);
    pthread_mutex_lock(&g_ready_mu);
    pthread_cond_broadcast(&g_ready_cv);
    pthread_mutex_unlock(&g_ready_mu);
}

#define HT_SIZE 4096
#define HT_MASK (HT_SIZE - 1)
typedef struct hn_s { struct hn_s *next; uint32_t sid; int cfd; } hn_t;
static hn_t            *g_ht[HT_SIZE];
static pthread_mutex_t  g_ht_mu[HT_SIZE];
static int              g_ht_inited = 0;

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
    hn_t *n = malloc(sizeof(*n)); if (!n) return;
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
        if ((*pp)->sid == sid) { hn_t *t = *pp; *pp = t->next; free(t); break; }
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
            close(n->cfd); free(n); n = nx;
        }
        g_ht[i] = NULL;
        pthread_mutex_unlock(&g_ht_mu[i]);
    }
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
        ssize_t n = writev(tfd, iov, niov);
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
            struct pollfd wp = {tfd, POLLOUT, 0};
            if (poll(&wp, 1, 500) <= 0) return -1;
        } else return -1;
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

static int try_connect_ip(const char *ip, int timeout_ms) {
    struct sockaddr_in6 a = {0};
    a.sin6_family = AF_INET6;
    a.sin6_port   = htons(PROXY_PORT);
    if (inet_pton(AF_INET6, ip, &a.sin6_addr) != 1) return -1;
    int fd = socket(AF_INET6, SOCK_STREAM, 0); if (fd < 0) return -1;
    protect_fd(fd);
    int one = 1, rcvbuf = 262144;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY,  &one,    sizeof(one));
    setsockopt(fd, SOL_SOCKET,  SO_KEEPALIVE, &one,    sizeof(one));
    setsockopt(fd, SOL_SOCKET,  SO_RCVBUF,    &rcvbuf, sizeof(rcvbuf));
    int fl = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, fl | O_NONBLOCK);
    fcntl(fd, F_SETFD, FD_CLOEXEC);
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

static int resolve_and_connect(void) {
    struct addrinfo hints = {0}, *res = NULL;
    hints.ai_family   = AF_INET6;
    hints.ai_socktype = SOCK_STREAM;
    char port_str[8];
    snprintf(port_str, sizeof(port_str), "%d", PROXY_PORT);
    if (getaddrinfo(PROXY_HOST, port_str, &hints, &res) != 0 || !res) return -1;
    int fd = -1;
    for (struct addrinfo *ai = res; ai && fd < 0; ai = ai->ai_next) {
        char ip[INET6_ADDRSTRLEN] = {0};
        inet_ntop(AF_INET6, &((struct sockaddr_in6 *)ai->ai_addr)->sin6_addr, ip, sizeof(ip));
        push_log("I", "dns: %s", ip);
        fd = try_connect_ip(ip, CONNECT_TIMEOUT_SEC * 1000);
    }
    freeaddrinfo(res);
    return fd;
}

static void parse_hdr(const char *buf, const char *key, char *out, int cap) {
    const char *p = strstr(buf, key); if (!p) return;
    p += strlen(key); while (*p == ' ' || *p == ':') p++;
    const char *e = strstr(p, "\r\n"); if (!e) return;
    int n = (int)(e - p); if (n <= 0 || n >= cap) return;
    memcpy(out, p, n); out[n] = 0;
}

static int open_tunnel(void) {
    int fd = -1;
    for (int i = 0; i < STATIC_IP_COUNT && fd < 0; i++) {
        push_log("I", "probando %s", STATIC_IPS[i]);
        fd = try_connect_ip(STATIC_IPS[i], CONNECT_TIMEOUT_SEC * 1000);
    }
    if (fd < 0) {
        push_log("I", "resolviendo DNS...");
        fd = resolve_and_connect();
    }
    if (fd < 0) { push_log("E", "sin conexion al proxy"); return TUNNEL_ERR_NET; }

    char buf[4096];
    snprintf(buf, sizeof(buf), "GET / HTTP/1.1\r\nHost: %s\r\n\r\n", PROXY_HOST);
    send(fd, buf, strlen(buf), MSG_NOSIGNAL);
    if (recv_eoh(fd, buf, sizeof(buf), HANDSHAKE_TIMEOUT_SEC) < 0)
        { push_log("E", "proxy no responde"); close(fd); return TUNNEL_ERR_NET; }

    char req[1024];
    snprintf(req, sizeof(req),
        "- / HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\n"
        "Connection: Upgrade\r\nAction: tunnel\r\nX-Internal-ID: %s\r\n\r\n",
        TUNNEL_HOST, g_iid[0] ? g_iid : "unknown");
    send(fd, req, strlen(req), MSG_NOSIGNAL);

    char h2[4096];
    if (recv_eoh(fd, h2, sizeof(h2), HANDSHAKE_TIMEOUT_SEC) < 0)
        { push_log("E", "sin respuesta del servidor"); close(fd); return TUNNEL_ERR_NET; }

    int code = -1;
    sscanf(h2, "HTTP/%*d.%*d %d", &code);

    if (code == 101) {
        char uname[128] = {0}, udays[32] = {0};
        parse_hdr(h2, "X-User-Name:", uname, sizeof(uname));
        parse_hdr(h2, "X-User-Days:", udays, sizeof(udays));
        if (uname[0]) push_log("I", "user_name=%s", uname);
        if (udays[0]) push_log("I", "user_days=%s", udays);
        push_log("I", "tunnel ok");
        atomic_store(&g_last_pong, (long)time(NULL));
        return fd;
    }

    push_log("E", "servidor rechazo code=%d", code);
    close(fd);
    return TUNNEL_ERR_AUTH;
}

static void *proxy_thread(void *arg) {
    int port = (int)(intptr_t)arg;

    int rfd = socket(AF_INET, SOCK_STREAM, 0);
    if (rfd < 0) { signal_ready(TUNNEL_ERR_NET); goto done; }
    {
        int one = 1;
        setsockopt(rfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
        setsockopt(rfd, SOL_SOCKET, SO_REUSEPORT, &one, sizeof(one));
        fcntl(rfd, F_SETFD, FD_CLOEXEC);
        struct sockaddr_in la = {0};
        la.sin_family      = AF_INET;
        la.sin_port        = htons((uint16_t)port);
        la.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        if (bind(rfd, (struct sockaddr *)&la, sizeof(la)) < 0 || listen(rfd, RELAY_BACKLOG) < 0)
            { push_log("E", "relay bind errno=%d", errno); close(rfd); signal_ready(TUNNEL_ERR_NET); goto done; }
        fcntl(rfd, F_SETFL, fcntl(rfd, F_GETFL, 0) | O_NONBLOCK);
    }

    int tfd = open_tunnel();
    if (tfd < 0) { signal_ready(tfd); close(rfd); goto done; }

    int wfds[2] = {-1, -1};
    if (pipe(wfds) < 0)
        { push_log("E", "pipe errno=%d", errno); signal_ready(TUNNEL_ERR_NET); close(tfd); close(rfd); goto done; }
    fcntl(wfds[0], F_SETFL, O_NONBLOCK); fcntl(wfds[1], F_SETFL, O_NONBLOCK);
    fcntl(wfds[0], F_SETFD, FD_CLOEXEC); fcntl(wfds[1], F_SETFD, FD_CLOEXEC);

    int epfd = epoll_create1(EPOLL_CLOEXEC);
    if (epfd < 0)
        { push_log("E", "epoll errno=%d", errno); signal_ready(TUNNEL_ERR_NET); close(wfds[0]); close(wfds[1]); close(tfd); close(rfd); goto done; }

    {
        struct epoll_event ev;
        ev.events = EPOLLIN; ev.data.fd = wfds[0]; epoll_ctl(epfd, EPOLL_CTL_ADD, wfds[0], &ev);
        ev.events = EPOLLIN; ev.data.fd = rfd;      epoll_ctl(epfd, EPOLL_CTL_ADD, rfd,      &ev);
        ev.events = EPOLLIN; ev.data.fd = tfd;      epoll_ctl(epfd, EPOLL_CTL_ADD, tfd,      &ev);
    }

    pthread_mutex_lock(&g_mu);
    g_tfd = tfd; g_rfd = rfd; g_epfd = epfd; g_wake_r = wfds[0]; g_wake_w = wfds[1];
    pthread_mutex_unlock(&g_mu);

    signal_ready(1);
    push_log("I", "sesion activa");

    {
        long last_ping = time(NULL);
        struct epoll_event ev;
        int has_pending = 0;

        while (1) {
            int timeout = has_pending ? 200 : 5000;
            int n = epoll_wait(epfd, &ev, 1, timeout);
            if (n < 0 && errno == EINTR) continue;

            long now = time(NULL);

            if (now - atomic_load(&g_last_pong) > PONG_TIMEOUT_SEC) {
                push_log("E", "pong timeout");
                break;
            }

            if (now - last_ping >= KEEPALIVE_INTERVAL_SEC) {
                last_ping = now;
                if (tun_send(tfd, T_PING, 0, NULL, 0) < 0) break;
            }

            if (n == 0) { has_pending = 0; continue; }
            if (n < 0) continue;

            int efd = ev.data.fd;

            if (efd == wfds[0]) break;

            if (efd == tfd) {
                for (;;) {
                uint8_t hdr[FRAME_HDR], payload[MAX_PAYLOAD];
                int off = 0;
                while (off < FRAME_HDR) {
                    ssize_t r = recv(tfd, hdr + off, FRAME_HDR - off, MSG_DONTWAIT);
                    if (r > 0) { off += r; continue; }
                    if (r == 0) goto session_end;
                    if (errno == EAGAIN || errno == EWOULDBLOCK) goto tfd_drained;
                    if (errno == EINTR) continue;
                    goto session_end;
                }
                uint8_t  ft  = hdr[0];
                uint32_t sid = ((uint32_t)hdr[1] << 24) | ((uint32_t)hdr[2] << 16) |
                               ((uint32_t)hdr[3] <<  8) |  (uint32_t)hdr[4];
                uint16_t len = ((uint16_t)hdr[5] << 8) | hdr[6];
                if (len > MAX_PAYLOAD) goto session_end;
                off = 0;
                while (off < (int)len) {
                    ssize_t r = recv(tfd, payload + off, len - off, 0);
                    if (r > 0) { off += r; continue; }
                    if (r == 0 || (errno != EINTR && errno != EAGAIN)) goto session_end;
                }
                switch (ft) {
                case T_DATA: {
                    int cfd = ht_get(sid);
                    if (cfd >= 0 && len > 0) {
                        ssize_t sent = 0;
                        while (sent < (ssize_t)len) {
                            ssize_t w = send(cfd, payload + sent, len - sent, MSG_NOSIGNAL);
                            if (w > 0) { sent += w; continue; }
                            if (w < 0 && errno == EINTR) continue;
                            break;
                        }
                    }
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
                } /* end drain loop */
                tfd_drained:
                continue;
            }

            if (efd == rfd) {
                struct sockaddr_in ca; socklen_t cl = sizeof(ca);
                int cfd = accept(rfd, (struct sockaddr *)&ca, &cl);
                if (cfd < 0) continue;
                fcntl(cfd, F_SETFD, FD_CLOEXEC);
                fcntl(cfd, F_SETFL, fcntl(cfd, F_GETFL, 0) | O_NONBLOCK);
                uint32_t sid;
                do { sid = (uint32_t)atomic_fetch_add(&g_next_sid, 1) & 0x7FFFFFFF; }
                while (!sid || ht_get(sid) != -1);
                uint8_t buf[MAX_PAYLOAD];
                ssize_t first = recv(cfd, buf, sizeof(buf), 0);
                if (first <= 0) { close(cfd); continue; }
                ht_put(sid, cfd);
                if (tun_send(tfd, T_OPEN, sid, buf, (uint16_t)first) < 0)
                    { ht_del(sid); close(cfd); goto session_end; }
                has_pending = 1;
                struct epoll_event cev;
                cev.events   = EPOLLIN | EPOLLERR | EPOLLHUP;
                cev.data.u64 = ((uint64_t)sid << 32) | (uint32_t)cfd;
                if (epoll_ctl(epfd, EPOLL_CTL_ADD, cfd, &cev) < 0)
                    { ht_del(sid); close(cfd); }
                continue;
            }

            {
                uint32_t sid = (uint32_t)(ev.data.u64 >> 32);
                int      cfd = (int)(uint32_t)ev.data.u64;
                if (ev.events & (EPOLLERR | EPOLLHUP)) {
                    epoll_ctl(epfd, EPOLL_CTL_DEL, cfd, NULL);
                    ht_del(sid); close(cfd);
                    tun_send(tfd, T_CLOSE, sid, NULL, 0);
                } else if (ev.events & EPOLLIN) {
                    uint8_t buf[MAX_PAYLOAD];
                    ssize_t nr = recv(cfd, buf, sizeof(buf), 0);
                    if (nr > 0) {
                        if (tun_send(tfd, T_DATA, sid, buf, (uint16_t)nr) < 0) goto session_end;
                        has_pending = 1;
                    } else if (nr == 0) {
                        epoll_ctl(epfd, EPOLL_CTL_DEL, cfd, NULL);
                        ht_del(sid); close(cfd);
                        tun_send(tfd, T_CLOSE, sid, NULL, 0);
                    }
                }
            }
        }
    }

session_end:
    ht_close_all(epfd);
    pthread_mutex_lock(&g_mu);
    g_tfd = -1; g_rfd = -1; g_epfd = -1; g_wake_r = -1; g_wake_w = -1;
    pthread_mutex_unlock(&g_mu);
    close(epfd);
    shutdown(tfd, SHUT_RDWR); close(tfd);
    close(wfds[0]); close(wfds[1]);
    close(rfd);

done:
    push_log("I", "proxy_thread terminado");
    pthread_mutex_lock(&g_mu);
    g_running = 0;
    pthread_mutex_unlock(&g_mu);
    return NULL;
}

JNIEXPORT jint JNICALL
Java_com_blacktunnel_BtProxy_nativeStart(JNIEnv *env, jclass clazz,
                                          jint port, jobject svc, jstring iid) {
    (void)clazz;
    pthread_mutex_lock(&g_mu);
    if (g_running) { pthread_mutex_unlock(&g_mu); return 0; }
    (*env)->GetJavaVM(env, &g_jvm);
    g_svc = (*env)->NewGlobalRef(env, svc);
    g_iid[0] = 0;
    if (iid) {
        const char *s = (*env)->GetStringUTFChars(env, iid, NULL);
        if (s) { snprintf(g_iid, sizeof(g_iid), "%s", s); (*env)->ReleaseStringUTFChars(env, iid, s); }
    }
    ht_init();
    atomic_store(&g_next_sid, 1);
    atomic_store(&g_last_pong, (long)time(NULL));
    atomic_store(&g_tunnel_ready, 0);
    g_tfd = g_rfd = g_epfd = g_wake_r = g_wake_w = -1;
    g_running = 1;
    pthread_mutex_unlock(&g_mu);

    pthread_t thr;
    if (pthread_create(&thr, NULL, proxy_thread, (void *)(intptr_t)port) != 0) {
        pthread_mutex_lock(&g_mu);
        g_running = 0;
        (*env)->DeleteGlobalRef(env, g_svc); g_svc = NULL; g_jvm = NULL;
        pthread_mutex_unlock(&g_mu);
        return TUNNEL_ERR_NET;
    }
    pthread_mutex_lock(&g_mu); g_thread = thr; pthread_mutex_unlock(&g_mu);

    pthread_mutex_lock(&g_ready_mu);
    while (atomic_load(&g_tunnel_ready) == 0)
        pthread_cond_wait(&g_ready_cv, &g_ready_mu);
    pthread_mutex_unlock(&g_ready_mu);

    int ready = atomic_load(&g_tunnel_ready);
    if (ready < 0) {
        pthread_join(thr, NULL);
        pthread_mutex_lock(&g_mu);
        jobject old = g_svc; g_svc = NULL; g_jvm = NULL;
        pthread_mutex_unlock(&g_mu);
        if (old) (*env)->DeleteGlobalRef(env, old);
        return ready;
    }

    push_log("I", "nativeStart ok");
    return 0;
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeStop(JNIEnv *env, jclass clazz) {
    (void)clazz;
    pthread_mutex_lock(&g_mu);
    if (!g_running) { pthread_mutex_unlock(&g_mu); return; }
    g_running = 0;
    g_iid[0] = 0;
    pthread_t thr = g_thread; g_thread = 0;
    int ww = g_wake_w; g_wake_w = -1;
    pthread_mutex_unlock(&g_mu);

    if (ww >= 0) { uint8_t b = 1; write(ww, &b, 1); close(ww); }
    if (thr != 0) pthread_join(thr, NULL);

    pthread_mutex_lock(&g_mu);
    jobject svc = g_svc; g_svc = NULL; g_jvm = NULL;
    pthread_mutex_unlock(&g_mu);
    if (svc) (*env)->DeleteGlobalRef(env, svc);

    ht_close_all(-1);
    atomic_store(&g_tunnel_ready, 0);
    push_log("I", "nativeStop ok");
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

static JNINativeMethod g_methods[] = {
    {"nativeStart",      "(ILandroid/net/VpnService;Ljava/lang/String;)I", (void *)Java_com_blacktunnel_BtProxy_nativeStart},
    {"nativeStop",       "()V",                                             (void *)Java_com_blacktunnel_BtProxy_nativeStop},
    {"nativeDrainLogs",  "()Ljava/lang/String;",                           (void *)Java_com_blacktunnel_BtProxy_nativeDrainLogs},
    {"nativeSetNetwork", "(J)V",                                            (void *)Java_com_blacktunnel_BtProxy_nativeSetNetwork},
};

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *r) {
    (void)r;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = (*env)->FindClass(env, "com/blacktunnel/BtProxy");
    if (!cls) return JNI_ERR;
    if ((*env)->RegisterNatives(env, cls, g_methods, sizeof(g_methods) / sizeof(g_methods[0])) < 0) return JNI_ERR;
    (*env)->DeleteLocalRef(env, cls);
    return JNI_VERSION_1_6;
}
