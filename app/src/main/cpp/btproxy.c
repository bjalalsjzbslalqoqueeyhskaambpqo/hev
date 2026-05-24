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

// Nuevo método CloudFront
#define PROXY_HOST  "recarga.personal.com.ar"
#define PROXY_PORT  80
#define CLOUDFRONT_HOST "dif2pyjxd7k7p.cloudfront.net"

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
            n = nx;
        }
        g_x[i] = NULL;
        ul(&g_xm[i]);
    }
}

typedef struct pkt_s {
    struct pkt_s *next;
    size_t        total;
    size_t        offset;
    uint8_t       buf[];
} pkt_t;

typedef struct { pkt_t *head; pkt_t *tail; size_t bytes; } pktq_t;

static pthread_mutex_t g_wq_mu = PTHREAD_MUTEX_INITIALIZER;
static pktq_t          g_wq    = {NULL, NULL, 0};
static pkt_t          *g_wp    = NULL;

static void wq_flush_locked(void) {
    pkt_t *p = g_wq.head;
    while (p) { pkt_t *nx = p->next; free(p); p = nx; }
    g_wq.head = g_wq.tail = NULL; g_wq.bytes = 0;
    if (g_wp) { free(g_wp); g_wp = NULL; }
}

static int tun_enqueue(int tfd, int epfd, uint8_t type, uint32_t sid,
                       const uint8_t *data, uint16_t len) {
    if (tfd < 0) return -1;
    uint8_t hdr[FRAME_HDR];
    hdr[0] = type;
    hdr[1] = (sid >> 24) & 0xFF; hdr[2] = (sid >> 16) & 0xFF;
    hdr[3] = (sid >> 8) & 0xFF;  hdr[4] = sid & 0xFF;
    hdr[5] = (len >> 8) & 0xFF;  hdr[6] = len & 0xFF;

    size_t total = FRAME_HDR + len;
    pkt_t *p = malloc(sizeof(pkt_t) + total);
    if (!p) return -1;
    p->next = NULL; p->total = total; p->offset = 0;
    memcpy(p->buf, hdr, FRAME_HDR);
    if (len > 0) memcpy(p->buf + FRAME_HDR, data, len);

    lk(&g_wq_mu);
    if (g_wq.tail) g_wq.tail->next = p; else g_wq.head = p;
    g_wq.tail = p; g_wq.bytes += total;
    size_t wqb = g_wq.bytes;
    ul(&g_wq_mu);

    if (wqb > WRITE_QUEUE_HIGH_WATER) {
        for (int i = 0; i < SI_SIZE; i++) {
            lk(&g_xm[i]);
            si_hn_t *n = g_x[i];
            while (n) {
                sinfo_t *si = n->si;
                if (!si->pr) {
                    si->pr = 1;
                    struct epoll_event cev;
                    cev.events   = 0;
                    cev.data.u64 = ((uint64_t)si->sid << 32) | (uint32_t)si->cfd;
                    epoll_ctl(epfd, EPOLL_CTL_MOD, si->cfd, &cev);
                }
                n = n->next;
            }
            ul(&g_xm[i]);
        }
    }
    return 0;
}

// Nuevo método de conexión CloudFront
static int connect_tunnel_cloudfront(void) {
    struct addrinfo hints, *res = NULL, *rp;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    
    char portstr[16];
    snprintf(portstr, sizeof(portstr), "%d", PROXY_PORT);
    
    pl("I", "Resolviendo DNS para %s...", PROXY_HOST);
    if (getaddrinfo(PROXY_HOST, portstr, &hints, &res) != 0) {
        pl("E", "Fallo en getaddrinfo para %s", PROXY_HOST);
        return -1;
    }
    
    int tfd = -1;
    for (rp = res; rp; rp = rp->ai_next) {
        tfd = socket(rp->ai_family, rp->ai_socktype, rp->ai_protocol);
        if (tfd < 0) continue;
        
        int flag = 1;
        setsockopt(tfd, IPPROTO_TCP, TCP_NODELAY, &flag, sizeof(flag));
        
        struct timeval tv = {CONNECT_TIMEOUT_SEC, 0};
        setsockopt(tfd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
        setsockopt(tfd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        
        if (g_n != NETWORK_UNSPECIFIED) {
            if (android_setsocknetwork(g_n, tfd) < 0) {
                pl("E", "android_setsocknetwork falló");
            }
        }
        
        char ip[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &((struct sockaddr_in *)rp->ai_addr)->sin_addr, ip, sizeof(ip));
        pl("I", "Conectando a IP: %s", ip);
        
        if (connect(tfd, rp->ai_addr, rp->ai_addrlen) == 0) {
            pl("I", "Socket conectado");
            break;
        }
        
        pl("E", "connect() falló: %s", strerror(errno));
        close(tfd);
        tfd = -1;
    }
    freeaddrinfo(res);
    
    if (tfd < 0) {
        pl("E", "No se pudo conectar a %s", PROXY_HOST);
        return -1;
    }
    
    // BLOQUE 1: HEAD inicial
    char bloque1[256];
    int len1 = snprintf(bloque1, sizeof(bloque1),
        "HEAD http://%s HTTP/1.1\r\n"
        "Host: %s\r\n\r\n",
        PROXY_HOST, PROXY_HOST);
    
    pl("I", "Enviando Bloque Inicial...");
    if (send(tfd, bloque1, len1, 0) != len1) {
        pl("E", "Fallo al enviar bloque 1");
        close(tfd);
        return -1;
    }
    
    // Esperar respuesta intermedia
    char resp1[2048];
    ssize_t nr = recv(tfd, resp1, sizeof(resp1) - 1, 0);
    if (nr <= 0) {
        pl("E", "Canal cerrado prematuramente");
        close(tfd);
        return -1;
    }
    resp1[nr] = 0;
    pl("I", "Respuesta intermedia recibida (%zd bytes)", nr);
    
    // BLOQUE 2: Inyección con CloudFront
    char bloque2[512];
    int len2 = snprintf(bloque2, sizeof(bloque2),
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
        CLOUDFRONT_HOST, CLOUDFRONT_HOST,
        g_i);
    
    pl("I", "Inyectando Bloque con CloudFront...");
    if (send(tfd, bloque2, len2, 0) != len2) {
        pl("E", "Fallo al enviar bloque 2");
        close(tfd);
        return -1;
    }
    
    // Esperar respuesta final
    usleep(800000); // 0.8 segundos
    
    char resp2[8192];
    nr = recv(tfd, resp2, sizeof(resp2) - 1, 0);
    if (nr <= 0) {
        pl("E", "No se recibió respuesta final");
        close(tfd);
        return -1;
    }
    resp2[nr] = 0;
    
    // Verificar si recibimos 101 Switching Protocols
    if (strstr(resp2, "101 Switching Protocols") == NULL) {
        pl("E", "No se recibió 101 Switching Protocols");
        pl("E", "Respuesta: %.200s", resp2);
        close(tfd);
        return -1;
    }
    
    pl("I", "WebSocket upgrade exitoso (101)");
    
    // Configurar socket no bloqueante
    int flags = fcntl(tfd, F_GETFL, 0);
    if (flags >= 0) fcntl(tfd, F_SETFL, flags | O_NONBLOCK);
    
    return tfd;
}

static void *reader_thread(void *arg) {
    int tfd = (int)(intptr_t)arg;
    uint8_t hdr[FRAME_HDR];
    
    atomic_store(&g_lp, (long)time(NULL));
    
    while (g_r && tfd >= 0) {
        size_t off = 0;
        while (off < FRAME_HDR) {
            ssize_t nr = recv(tfd, hdr + off, FRAME_HDR - off, 0);
            if (nr > 0) {
                off += (size_t)nr;
                atomic_store(&g_lp, (long)time(NULL));
            } else if (nr == 0) {
                pl("E", "reader: canal cerrado");
                return NULL;
            } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
                usleep(10000);
            } else if (errno != EINTR) {
                pl("E", "reader: recv hdr err=%s", strerror(errno));
                return NULL;
            }
        }
        
        uint8_t  type = hdr[0];
        uint32_t sid  = ((uint32_t)hdr[1] << 24) | ((uint32_t)hdr[2] << 16) |
                        ((uint32_t)hdr[3] << 8)  | (uint32_t)hdr[4];
        uint16_t len  = ((uint16_t)hdr[5] << 8) | (uint16_t)hdr[6];
        
        uint8_t payload[MAX_PAYLOAD];
        if (len > 0) {
            off = 0;
            while (off < len) {
                ssize_t nr = recv(tfd, payload + off, len - off, 0);
                if (nr > 0) {
                    off += (size_t)nr;
                    atomic_store(&g_lp, (long)time(NULL));
                } else if (nr == 0) {
                    pl("E", "reader: canal cerrado en payload");
                    return NULL;
                } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
                    usleep(10000);
                } else if (errno != EINTR) {
                    pl("E", "reader: recv payload err=%s", strerror(errno));
                    return NULL;
                }
            }
        }
        
        if (type == T_PONG) {
            atomic_store(&g_lpt, (long)time(NULL));
        } else if (type == T_DATA) {
            int cfd = ht_get(sid);
            if (cfd >= 0) {
                sinfo_t *si = si_get(sid);
                if (si && si->lq.bytes < LOCAL_QUEUE_HARD_LIMIT) {
                    cq_push(&si->lq, payload, len);
                    if (!si->ps) {
                        si->ps = 1;
                        lk(&g_m);
                        int epfd = g_ef;
                        ul(&g_m);
                        if (epfd >= 0) {
                            struct epoll_event cev;
                            cev.events   = EPOLLIN | EPOLLOUT;
                            cev.data.u64 = ((uint64_t)sid << 32) | (uint32_t)cfd;
                            epoll_ctl(epfd, EPOLL_CTL_MOD, cfd, &cev);
                        }
                    }
                }
            }
        } else if (type == T_CLOSE) {
            int cfd = ht_get(sid);
            if (cfd >= 0) {
                sinfo_t *si = si_get(sid);
                if (si) {
                    si->cp = 1;
                    if (!si->ps) {
                        shutdown(cfd, SHUT_RDWR);
                    }
                }
            }
        } else if (type == T_PING) {
            lk(&g_m);
            int tfd2 = g_tf;
            ul(&g_m);
            if (tfd2 >= 0) {
                uint8_t phdr[FRAME_HDR];
                phdr[0] = T_PONG;
                phdr[1] = (sid >> 24) & 0xFF; phdr[2] = (sid >> 16) & 0xFF;
                phdr[3] = (sid >> 8) & 0xFF;  phdr[4] = sid & 0xFF;
                phdr[5] = 0; phdr[6] = 0;
                send(tfd2, phdr, FRAME_HDR, MSG_NOSIGNAL);
            }
        }
    }
    return NULL;
}

static void *writer_thread(void *arg) {
    int tfd = (int)(intptr_t)arg;
    
    while (g_r && tfd >= 0) {
        lk(&g_wq_mu);
        if (!g_wp && g_wq.head) {
            g_wp = g_wq.head;
            g_wq.head = g_wp->next;
            if (!g_wq.head) g_wq.tail = NULL;
        }
        pkt_t *p = g_wp;
        ul(&g_wq_mu);
        
        if (!p) {
            usleep(10000);
            continue;
        }
        
        while (p->offset < p->total) {
            ssize_t nw = send(tfd, p->buf + p->offset, p->total - p->offset, MSG_NOSIGNAL);
            if (nw > 0) {
                p->offset += (size_t)nw;
                lk(&g_wq_mu);
                g_wq.bytes -= (size_t)nw;
                ul(&g_wq_mu);
            } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
                usleep(10000);
            } else if (errno != EINTR) {
                pl("E", "writer: send err=%s", strerror(errno));
                lk(&g_wq_mu);
                if (g_wp) { free(g_wp); g_wp = NULL; }
                ul(&g_wq_mu);
                return NULL;
            }
        }
        
        lk(&g_wq_mu);
        free(g_wp);
        g_wp = NULL;
        size_t wqb = g_wq.bytes;
        ul(&g_wq_mu);
        
        if (wqb < WRITE_QUEUE_LOW_WATER) {
            lk(&g_m);
            int epfd = g_ef;
            ul(&g_m);
            if (epfd >= 0) {
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
        }
    }
    return NULL;
}

static void *main_thread(void *arg) {
    int port = (int)(intptr_t)arg;
    int epoch = 0;
    
    while (g_r) {
        epoch++;
        pl("I", "Iniciando epoch=%d", epoch);
        
        int tfd = connect_tunnel_cloudfront();
        if (tfd < 0) {
            pl("E", "connect_tunnel_cloudfront falló, reintentando...");
            if (g_r) sleep(3);
            continue;
        }
        
        lk(&g_m);
        g_tf = tfd;
        ul(&g_m);
        
        int wfds[2];
        if (pipe(wfds) < 0) {
            pl("E", "pipe falló");
            close(tfd);
            lk(&g_m); g_tf = -1; ul(&g_m);
            if (g_r) sleep(3);
            continue;
        }
        
        lk(&g_m);
        g_wr = wfds[0]; g_ww = wfds[1];
        ul(&g_m);
        
        pthread_t rthr, wthr;
        if (pthread_create(&rthr, NULL, reader_thread, (void *)(intptr_t)tfd) != 0) {
            pl("E", "pthread_create reader falló");
            close(wfds[0]); close(wfds[1]); close(tfd);
            lk(&g_m); g_tf = -1; g_wr = -1; g_ww = -1; ul(&g_m);
            if (g_r) sleep(3);
            continue;
        }
        
        if (pthread_create(&wthr, NULL, writer_thread, (void *)(intptr_t)tfd) != 0) {
            pl("E", "pthread_create writer falló");
            pthread_cancel(rthr);
            pthread_join(rthr, NULL);
            close(wfds[0]); close(wfds[1]); close(tfd);
            lk(&g_m); g_tf = -1; g_wr = -1; g_ww = -1; ul(&g_m);
            if (g_r) sleep(3);
            continue;
        }
        
        int rfd = socket(AF_INET, SOCK_STREAM, 0);
        if (rfd < 0) {
            pl("E", "socket relay falló");
            pthread_cancel(rthr); pthread_cancel(wthr);
            pthread_join(rthr, NULL); pthread_join(wthr, NULL);
            close(wfds[0]); close(wfds[1]); close(tfd);
            lk(&g_m); g_tf = -1; g_wr = -1; g_ww = -1; ul(&g_m);
            if (g_r) sleep(3);
            continue;
        }
        
        int yes = 1;
        setsockopt(rfd, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
        
        struct sockaddr_in sa;
        memset(&sa, 0, sizeof(sa));
        sa.sin_family      = AF_INET;
        sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        sa.sin_port        = htons((uint16_t)port);
        
        if (bind(rfd, (struct sockaddr *)&sa, sizeof(sa)) < 0) {
            pl("E", "bind falló: %s", strerror(errno));
            close(rfd);
            pthread_cancel(rthr); pthread_cancel(wthr);
            pthread_join(rthr, NULL); pthread_join(wthr, NULL);
            close(wfds[0]); close(wfds[1]); close(tfd);
            lk(&g_m); g_tf = -1; g_wr = -1; g_ww = -1; ul(&g_m);
            if (g_r) sleep(3);
            continue;
        }
        
        if (listen(rfd, RELAY_BACKLOG) < 0) {
            pl("E", "listen falló");
            close(rfd);
            pthread_cancel(rthr); pthread_cancel(wthr);
            pthread_join(rthr, NULL); pthread_join(wthr, NULL);
            close(wfds[0]); close(wfds[1]); close(tfd);
            lk(&g_m); g_tf = -1; g_wr = -1; g_ww = -1; ul(&g_m);
            if (g_r) sleep(3);
            continue;
        }
        
        lk(&g_m);
        g_rf = rfd;
        ul(&g_m);
        
        int epfd = epoll_create1(0);
        if (epfd < 0) {
            pl("E", "epoll_create1 falló");
            close(rfd);
            pthread_cancel(rthr); pthread_cancel(wthr);
            pthread_join(rthr, NULL); pthread_join(wthr, NULL);
            close(wfds[0]); close(wfds[1]); close(tfd);
            lk(&g_m); g_tf = -1; g_rf = -1; g_wr = -1; g_ww = -1; ul(&g_m);
            if (g_r) sleep(3);
            continue;
        }
        
        lk(&g_m);
        g_ef = epfd;
        ul(&g_m);
        
        struct epoll_event rev;
        rev.events   = EPOLLIN;
        rev.data.u64 = (uint64_t)rfd;
        epoll_ctl(epfd, EPOLL_CTL_ADD, rfd, &rev);
        
        struct epoll_event wev;
        wev.events   = EPOLLIN;
        wev.data.u64 = ((uint64_t)1 << 63) | (uint64_t)wfds[0];
        epoll_ctl(epfd, EPOLL_CTL_ADD, wfds[0], &wev);
        
        pl("I", "Túnel establecido, escuchando en puerto %d", port);
        
        long last_ping      = nms();
        long last_pong_chk  = nms();
        atomic_store(&g_lp, (long)time(NULL));
        atomic_store(&g_lpt, (long)time(NULL));
        
        int dead = 0;
        struct epoll_event events[MAX_EPOLL_EVENTS];
        
        while (g_r && !dead) {
            int nfds = epoll_wait(epfd, events, MAX_EPOLL_EVENTS, 1000);
            long now = nms();
            
            if (now - last_ping >= KEEPALIVE_INTERVAL_SEC * 1000L) {
                tun_enqueue(tfd, epfd, T_PING, 0, NULL, 0);
                last_ping = now;
            }
            
            if (now - last_pong_chk >= 5000L) {
                long lp  = atomic_load(&g_lp);
                long lpt = atomic_load(&g_lpt);
                if ((time(NULL) - lp) > PONG_TIMEOUT_SEC ||
                    (time(NULL) - lpt) > PONG_TIMEOUT_SEC) {
                    pl("E", "PONG timeout");
                    dead = 1;
                }
                last_pong_chk = now;
            }
            
            for (int i = 0; i < nfds; i++) {
                uint64_t d   = events[i].data.u64;
                uint32_t evs = events[i].events;
                
                if (d & ((uint64_t)1 << 63)) {
                    uint8_t b;
                    read(wfds[0], &b, 1);
                    dead = 1;
                    break;
                }
                
                if ((int)d == rfd) {
                    while (1) {
                        struct sockaddr_in ca;
                        socklen_t clen = sizeof(ca);
                        int cfd = accept(rfd, (struct sockaddr *)&ca, &clen);
                        if (cfd < 0) break;
                        
                        int flags = fcntl(cfd, F_GETFL, 0);
                        if (flags >= 0) fcntl(cfd, F_SETFL, flags | O_NONBLOCK);
                        
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
