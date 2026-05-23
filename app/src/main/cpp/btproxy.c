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

#define PROXY_HOST  "recarga.personal.com.ar"
#define PROXY_PORT  80
#define TUNNEL_HOST "dif2pyjxd7k7p.cloudfront.net"

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
            free(si);
            free(n);
            n = nx;
        }
        g_x[i] = NULL;
        ul(&g_xm[i]);
    }
}

typedef struct wp_s {
    size_t   offset;
    size_t   total;
    uint8_t *buf;
} wp_t;

static chunkq_t        g_wq;
static pthread_mutex_t g_wq_mu = PTHREAD_MUTEX_INITIALIZER;
static wp_t           *g_wp     = NULL;

static void wq_flush_locked(void) {
    cq_flush(&g_wq);
    if (g_wp) { free(g_wp->buf); free(g_wp); g_wp = NULL; }
}

static int tun_enqueue(int tfd, int epfd, uint8_t typ, uint32_t sid,
                       const uint8_t *pay, uint16_t plen) {
    if (tfd < 0) return -1;

    uint8_t hdr[FRAME_HDR];
    hdr[0] = typ;
    hdr[1] = (uint8_t)(sid >> 24);
    hdr[2] = (uint8_t)(sid >> 16);
    hdr[3] = (uint8_t)(sid >> 8);
    hdr[4] = (uint8_t)sid;
    hdr[5] = (uint8_t)(plen >> 8);
    hdr[6] = (uint8_t)plen;

    struct iovec iov[2];
    iov[0].iov_base = hdr;
    iov[0].iov_len  = FRAME_HDR;
    iov[1].iov_base = (void *)pay;
    iov[1].iov_len  = plen;

    ssize_t nw = writev(tfd, iov, (plen > 0) ? 2 : 1);
    if (nw < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            lk(&g_wq_mu);
            size_t total = FRAME_HDR + plen;
            uint8_t *buf = malloc(total);
            if (buf) {
                memcpy(buf, hdr, FRAME_HDR);
                if (plen) memcpy(buf + FRAME_HDR, pay, plen);
                cq_push(&g_wq, buf, total);
                free(buf);
            }
            ul(&g_wq_mu);
            return 0;
        }
        return -1;
    }
    return 0;
}

static int tun_try_flush(int tfd) {
    lk(&g_wq_mu);
    if (!g_wp && !g_wq.head) { ul(&g_wq_mu); return 0; }

    if (!g_wp && g_wq.head) {
        chunk_t *c = g_wq.head;
        g_wq.head = c->next;
        if (!g_wq.head) g_wq.tail = NULL;
        g_wq.bytes -= c->len;

        g_wp = malloc(sizeof(*g_wp));
        if (!g_wp) { free(c); ul(&g_wq_mu); return -1; }
        g_wp->buf = malloc(c->len);
        if (!g_wp->buf) { free(g_wp); g_wp = NULL; free(c); ul(&g_wq_mu); return -1; }
        memcpy(g_wp->buf, c->data, c->len);
        g_wp->total = c->len;
        g_wp->offset = 0;
        free(c);
    }

    if (g_wp) {
        ssize_t nw = write(tfd, g_wp->buf + g_wp->offset, g_wp->total - g_wp->offset);
        if (nw > 0) {
            g_wp->offset += (size_t)nw;
            if (g_wp->offset >= g_wp->total) {
                free(g_wp->buf); free(g_wp); g_wp = NULL;
            }
        } else if (nw < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
            ul(&g_wq_mu);
            return -1;
        }
    }

    int has_data = (g_wp != NULL) || (g_wq.head != NULL);
    ul(&g_wq_mu);
    return has_data ? 1 : 0;
}

static int ipv4_connect(const char *host, int port, int timeout_sec) {
    struct addrinfo hints, *res = NULL, *rp = NULL;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;

    char service[8];
    snprintf(service, sizeof(service), "%d", port);

    if (getaddrinfo(host, service, &hints, &res) != 0) return -1;

    int fd = -1;
    for (rp = res; rp; rp = rp->ai_next) {
        fd = socket(rp->ai_family, rp->ai_socktype, rp->ai_protocol);
        if (fd < 0) continue;

        int flags = fcntl(fd, F_GETFL, 0);
        fcntl(fd, F_SETFL, flags | O_NONBLOCK);

        int rc = connect(fd, rp->ai_addr, rp->ai_addrlen);
        if (rc == 0) break;
        if (errno != EINPROGRESS) {
            close(fd); fd = -1; continue;
        }

        struct pollfd pfd = { .fd = fd, .events = POLLOUT };
        int pr = poll(&pfd, 1, timeout_sec * 1000);
        if (pr > 0 && (pfd.revents & POLLOUT)) {
            int err = 0; socklen_t elen = sizeof(err);
            if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &elen) == 0 && err == 0) {
                fcntl(fd, F_SETFL, flags);
                break;
            }
        }
        close(fd); fd = -1;
    }
    if (res) freeaddrinfo(res);
    return fd;
}

static int cloudfront_handshake(int fd, const char *iid) {
    char blk1[256];
    snprintf(blk1, sizeof(blk1),
             "HEAD http://%s HTTP/1.1\r\n"
             "Host: %s\r\n\r\n",
             PROXY_HOST, PROXY_HOST);

    ssize_t ns1 = send(fd, blk1, strlen(blk1), MSG_NOSIGNAL);
    if (ns1 <= 0) return -1;

    struct pollfd pfd = { .fd = fd, .events = POLLIN };
    int pr = poll(&pfd, 1, HANDSHAKE_TIMEOUT_SEC * 1000);
    if (pr <= 0) return -1;

    char rbuf[2048];
    ssize_t nr = recv(fd, rbuf, sizeof(rbuf) - 1, 0);
    if (nr <= 0) return -1;

    char blk2[512];
    snprintf(blk2, sizeof(blk2),
             "PACHTS http://%s HTTP/1.1\r\n"
             "Host: %s\r\n"
             "\r\n"
             "GET htt://%s HTTP/1.1\r\n"
             "Host: %s\r\n"
             "Upgrade: websocket\r\n"
             "Connection: Upgrade\r\n"
             "Action: tunnel\r\n"
             "X-Internal-ID: %s\r\n\r\n",
             PROXY_HOST, PROXY_HOST,
             TUNNEL_HOST, TUNNEL_HOST, iid);

    ssize_t ns2 = send(fd, blk2, strlen(blk2), MSG_NOSIGNAL);
    if (ns2 <= 0) return -1;

    usleep(800000);

    pr = poll(&pfd, 1, HANDSHAKE_TIMEOUT_SEC * 1000);
    if (pr <= 0) return -1;

    nr = recv(fd, rbuf, sizeof(rbuf) - 1, 0);
    if (nr <= 0) return -1;

    rbuf[nr] = 0;
    if (strstr(rbuf, "101") && strstr(rbuf, "websocket")) {
        return 0;
    }

    return -1;
}

static void *main_thread(void *arg) {
    int srvport = (int)(intptr_t)arg;
    int epoch   = 0;

    while (g_r) {
        epoch++;
        pl("I", "epoch=%d conectando cloudfront", epoch);

        int tfd = ipv4_connect(PROXY_HOST, PROXY_PORT, CONNECT_TIMEOUT_SEC);
        if (tfd < 0) {
            pl("E", "fallo connect ipv4");
            if (g_r) sleep(3);
            continue;
        }

        char iid[160];
        lk(&g_m);
        snprintf(iid, sizeof(iid), "%s", g_i);
        ul(&g_m);

        if (cloudfront_handshake(tfd, iid) < 0) {
            pl("E", "handshake cloudfront fallo");
            close(tfd);
            if (g_r) sleep(3);
            continue;
        }

        pl("I", "handshake cloudfront ok");

        int flags = fcntl(tfd, F_GETFL, 0);
        fcntl(tfd, F_SETFL, flags | O_NONBLOCK);

        int on = 1;
        setsockopt(tfd, IPPROTO_TCP, TCP_NODELAY, &on, sizeof(on));

        lk(&g_m); g_tf = tfd; ul(&g_m);

        int rfd = socket(AF_INET, SOCK_STREAM, 0);
        if (rfd < 0) {
            close(tfd);
            lk(&g_m); g_tf = -1; ul(&g_m);
            if (g_r) sleep(3);
            continue;
        }

        setsockopt(rfd, SOL_SOCKET, SO_REUSEADDR, &on, sizeof(on));

        struct sockaddr_in sa;
        memset(&sa, 0, sizeof(sa));
        sa.sin_family = AF_INET;
        sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        sa.sin_port = htons((uint16_t)srvport);

        if (bind(rfd, (struct sockaddr *)&sa, sizeof(sa)) < 0) {
            close(rfd); close(tfd);
            lk(&g_m); g_tf = -1; ul(&g_m);
            if (g_r) sleep(3);
            continue;
        }

        if (listen(rfd, RELAY_BACKLOG) < 0) {
            close(rfd); close(tfd);
            lk(&g_m); g_tf = -1; ul(&g_m);
            if (g_r) sleep(3);
            continue;
        }

        fcntl(rfd, F_SETFL, fcntl(rfd, F_GETFL, 0) | O_NONBLOCK);
        lk(&g_m); g_rf = rfd; ul(&g_m);

        int epfd = epoll_create1(0);
        if (epfd < 0) {
            close(rfd); close(tfd);
            lk(&g_m); g_tf = -1; g_rf = -1; ul(&g_m);
            if (g_r) sleep(3);
            continue;
        }
        lk(&g_m); g_ef = epfd; ul(&g_m);

        int wfds[2];
        if (pipe(wfds) < 0) {
            close(epfd); close(rfd); close(tfd);
            lk(&g_m); g_tf = -1; g_rf = -1; g_ef = -1; ul(&g_m);
            if (g_r) sleep(3);
            continue;
        }
        lk(&g_m); g_wr = wfds[0]; g_ww = wfds[1]; ul(&g_m);

        fcntl(wfds[0], F_SETFL, fcntl(wfds[0], F_GETFL, 0) | O_NONBLOCK);
        fcntl(wfds[1], F_SETFL, fcntl(wfds[1], F_GETFL, 0) | O_NONBLOCK);

        struct epoll_event ev;
        ev.events = EPOLLIN;
        ev.data.fd = rfd;
        epoll_ctl(epfd, EPOLL_CTL_ADD, rfd, &ev);

        ev.events = EPOLLIN;
        ev.data.fd = tfd;
        epoll_ctl(epfd, EPOLL_CTL_ADD, tfd, &ev);

        ev.events = EPOLLIN;
        ev.data.fd = wfds[0];
        epoll_ctl(epfd, EPOLL_CTL_ADD, wfds[0], &ev);

        int dead = 0;
        long last_ka = nms();
        long last_pong = nms();

        atomic_store(&g_lp, nms());
        atomic_store(&g_lpt, 0);

        uint8_t rbuf[FRAME_HDR + MAX_PAYLOAD];
        size_t rbuf_fill = 0;

        while (g_r && !dead) {
            struct epoll_event events[MAX_EPOLL_EVENTS];
            int nev = epoll_wait(epfd, events, MAX_EPOLL_EVENTS, 1000);

            if (nev < 0 && errno != EINTR) break;

            long now = nms();

            if (now - last_pong > PONG_TIMEOUT_SEC * 1000L) {
                pl("E", "pong timeout");
                dead = 1; break;
            }

            if (now - last_ka > KEEPALIVE_INTERVAL_SEC * 1000L) {
                tun_enqueue(tfd, epfd, T_PING, 0, NULL, 0);
                last_ka = now;
            }

            lk(&g_wq_mu);
            size_t wq_tot = g_wq.bytes + (g_wp ? g_wp->total - g_wp->offset : 0);
            ul(&g_wq_mu);

            if (wq_tot < WRITE_QUEUE_LOW_WATER) {
                for (int i = 0; i < SI_SIZE; i++) {
                    lk(&g_xm[i]);
                    si_hn_t *n = g_x[i];
                    while (n) {
                        sinfo_t *si = n->si;
                        if (si->pr) {
                            si->pr = 0;
                            struct epoll_event cev;
                            cev.events = EPOLLIN;
                            cev.data.u64 = ((uint64_t)si->sid << 32) | (uint32_t)si->cfd;
                            epoll_ctl(epfd, EPOLL_CTL_MOD, si->cfd, &cev);
                        }
                        n = n->next;
                    }
                    ul(&g_xm[i]);
                }
            }

            for (int i = 0; i < SI_SIZE; i++) {
                lk(&g_xm[i]);
                si_hn_t *n = g_x[i];
                while (n) {
                    sinfo_t *si = n->si;
                    if ((now - si->la) > STREAM_TIMEOUT_MS && !si->ps && si->lq.bytes == 0) {
                        int cfd = si->cfd; uint32_t sid = si->sid;
                        epoll_ctl(epfd, EPOLL_CTL_DEL, cfd, NULL);
                        ht_del(sid); si_del(sid);
                        close(cfd); free(si);
                        tun_enqueue(tfd, epfd, T_CLOSE, sid, NULL, 0);
                    }
                    n = n->next;
                }
                ul(&g_xm[i]);
            }

            for (int i = 0; i < nev; i++) {
                uint32_t evs = events[i].events;
                int      efd = events[i].data.fd;

                if (efd == wfds[0]) {
                    uint8_t b; while (read(wfds[0], &b, 1) > 0);
                    continue;
                }

                if (efd == tfd) {
                    if (evs & (EPOLLERR | EPOLLHUP)) { dead = 1; break; }

                    if (evs & EPOLLOUT) {
                        int fr = tun_try_flush(tfd);
                        if (fr < 0) { dead = 1; break; }
                        if (fr == 0) {
                            struct epoll_event tev;
                            tev.events = EPOLLIN;
                            tev.data.fd = tfd;
                            epoll_ctl(epfd, EPOLL_CTL_MOD, tfd, &tev);
                        }
                    }

                    if (evs & EPOLLIN) {
                        ssize_t nr = recv(tfd, rbuf + rbuf_fill,
                                          sizeof(rbuf) - rbuf_fill, 0);
                        if (nr > 0) {
                            rbuf_fill += (size_t)nr;

                            while (rbuf_fill >= FRAME_HDR) {
                                uint8_t typ = rbuf[0];
                                uint32_t sid = ((uint32_t)rbuf[1] << 24) |
                                               ((uint32_t)rbuf[2] << 16) |
                                               ((uint32_t)rbuf[3] << 8) | rbuf[4];
                                uint16_t plen = ((uint16_t)rbuf[5] << 8) | rbuf[6];

                                if (rbuf_fill < FRAME_HDR + plen) break;

                                uint8_t *pay = rbuf + FRAME_HDR;

                                if (typ == T_PONG) {
                                    last_pong = nms();
                                    atomic_store(&g_lp, last_pong);
                                    atomic_store(&g_lpt, now - last_pong);
                                } else if (typ == T_DATA) {
                                    int cfd = ht_get(sid);
                                    if (cfd >= 0) {
                                        sinfo_t *si = si_get(sid);
                                        if (si) {
                                            if (si->lq.bytes < LOCAL_QUEUE_HARD_LIMIT) {
                                                cq_push(&si->lq, pay, plen);
                                                if (!si->ps) {
                                                    si->ps = 1;
                                                    struct epoll_event cev;
                                                    cev.events = EPOLLIN | EPOLLOUT;
                                                    cev.data.u64 = ((uint64_t)sid << 32) | (uint32_t)cfd;
                                                    epoll_ctl(epfd, EPOLL_CTL_MOD, cfd, &cev);
                                                }
                                            }
                                        }
                                    }
                                } else if (typ == T_CLOSE) {
                                    int cfd = ht_get(sid);
                                    if (cfd >= 0) {
                                        sinfo_t *si = si_get(sid);
                                        if (si) {
                                            if (si->lq.head) {
                                                si->cp = 1;
                                            } else {
                                                epoll_ctl(epfd, EPOLL_CTL_DEL, cfd, NULL);
                                                shutdown(cfd, SHUT_RDWR);
                                                ht_del(sid); cq_flush(&si->lq); si_del(sid); free(si);
                                                close(cfd);
                                            }
                                        }
                                    }
                                }

                                memmove(rbuf, rbuf + FRAME_HDR + plen,
                                        rbuf_fill - FRAME_HDR - plen);
                                rbuf_fill -= FRAME_HDR + plen;
                            }
                        } else if (nr == 0) {
                            dead = 1; break;
                        }
                    }
                    continue;
                }

                if (efd == rfd) {
                    while (1) {
                        struct sockaddr_in ca;
                        socklen_t clen = sizeof(ca);
                        int cfd = accept(rfd, (struct sockaddr *)&ca, &clen);
                        if (cfd < 0) break;

                        fcntl(cfd, F_SETFL, fcntl(cfd, F_GETFL, 0) | O_NONBLOCK);
                        int on = 1;
                        setsockopt(cfd, IPPROTO_TCP, TCP_NODELAY, &on, sizeof(on));

                        uint32_t sid = (uint32_t)atomic_fetch_add(&g_ns, 1);

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
