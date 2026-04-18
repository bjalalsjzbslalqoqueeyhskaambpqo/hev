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

#define FRAME_HDR             7
#define MAX_PAYLOAD           16384
#define RELAY_BACKLOG         512
#define PONG_TIMEOUT_SEC      180
#define RECONNECT_DELAY_MIN   2
#define RECONNECT_DELAY_MAX   30
#define CONNECT_TIMEOUT_SEC   10
#define HANDSHAKE_TIMEOUT_SEC 1
#define MAX_EVENTS            64

#define PROXY_HOST_IPV6  "2606:4700::6812:16b7"
#define PROXY_HOST       "emailmarketing.personal.com.ar"
#define PROXY_PORT       80
#define TUNNEL_HOST      "2.brawlpass.com.ar"

#define MODE_DAILY  0
#define MODE_GAMING 1

/* ── Globals básicos ─────────────────────────────────────────── */
static atomic_int      g_mode        = MODE_DAILY;
static int             g_sndbuf      = 262144;
static volatile int    g_running     = 0;
static volatile int    g_started     = 0;
static int             g_relay_fd    = -1;
static int             g_tun_fd      = -1;
static int             g_epoll_fd    = -1;
static atomic_int      g_next_sid    = 1;
static atomic_int      g_tunnel_epoch = 0;
static atomic_long     g_last_pong   = 0;
static char            g_internal_id[160] = {0};
static JavaVM         *g_jvm         = NULL;
static jobject         g_svc         = NULL;
static net_handle_t    g_net         = NETWORK_UNSPECIFIED;
static pthread_mutex_t g_mu          = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_wmu         = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_log_mu      = PTHREAD_MUTEX_INITIALIZER;
static char            g_logbuf[32768];
static size_t          g_loglen      = 0;
static pthread_mutex_t g_ready_mu    = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_ready_cv    = PTHREAD_COND_INITIALIZER;
static int             g_ready_st    = 0;
static int             g_reconnect_delay = RECONNECT_DELAY_MIN;
static int             g_ht_inited   = 0;

/* ── Conexión: estado por socket cliente ────────────────────── */
/*
 * Cada conexión tiene:
 *  - cfd: socket del cliente (hev)
 *  - sid: ID de stream para identificar en el mux
 *  - sndbuf: buffer de salida (datos del servidor esperando envío)
 *
 * sndbuf existe para el caso donde send() devuelve EAGAIN —
 * guardamos los datos y los mandamos cuando epoll avisa EPOLLOUT.
 * En práctica con conexiones locales (hev en 127.0.0.1) casi nunca
 * se llena, pero hay que manejarlo correctamente.
 */
#define SNDBUF_CAP 65536

typedef struct conn_s {
    int      cfd;
    uint32_t sid;
    uint8_t  sndbuf[SNDBUF_CAP];
    int      sndbuf_len;
    int      sndbuf_off;
    int      client_closed; /* cliente cerró su lado */
} conn_t;

/* ── Hashtable sid→conn ────────────────────────────────────── */
#define HT_SIZE  4096
#define HT_MASK  (HT_SIZE - 1)

typedef struct ht_node_s {
    struct ht_node_s *next;
    uint32_t          sid;
    conn_t           *conn;
} ht_node_t;

static ht_node_t      *g_ht[HT_SIZE];
static pthread_mutex_t g_ht_mu[HT_SIZE];

static void ht_init(void) {
    for (int i = 0; i < HT_SIZE; i++) {
        g_ht[i] = NULL;
        if (!g_ht_inited) pthread_mutex_init(&g_ht_mu[i], NULL);
    }
    g_ht_inited = 1;
}

static conn_t *ht_get(uint32_t sid) {
    int slot = sid & HT_MASK;
    pthread_mutex_lock(&g_ht_mu[slot]);
    ht_node_t *n = g_ht[slot];
    while (n && n->sid != sid) n = n->next;
    conn_t *c = n ? n->conn : NULL;
    pthread_mutex_unlock(&g_ht_mu[slot]);
    return c;
}

static void ht_put(uint32_t sid, conn_t *conn) {
    ht_node_t *n = malloc(sizeof(*n));
    if (!n) return;
    n->sid = sid; n->conn = conn; n->next = NULL;
    int slot = sid & HT_MASK;
    pthread_mutex_lock(&g_ht_mu[slot]);
    n->next = g_ht[slot]; g_ht[slot] = n;
    pthread_mutex_unlock(&g_ht_mu[slot]);
}

static void ht_del(uint32_t sid) {
    int slot = sid & HT_MASK;
    pthread_mutex_lock(&g_ht_mu[slot]);
    ht_node_t **pp = &g_ht[slot];
    while (*pp) {
        if ((*pp)->sid == sid) {
            ht_node_t *n = *pp; *pp = n->next;
            free(n); break;
        }
        pp = &(*pp)->next;
    }
    pthread_mutex_unlock(&g_ht_mu[slot]);
}

static void ht_clear(void) {
    for (int i = 0; i < HT_SIZE; i++) {
        pthread_mutex_lock(&g_ht_mu[i]);
        ht_node_t *n = g_ht[i];
        while (n) { ht_node_t *nx = n->next; free(n); n = nx; }
        g_ht[i] = NULL;
        pthread_mutex_unlock(&g_ht_mu[i]);
    }
}

/* ── Logging ─────────────────────────────────────────────────── */
static void push_log(const char *lvl, const char *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    char msg[512]; vsnprintf(msg, sizeof(msg), fmt, ap); va_end(ap);
    if (lvl[0] == 'E') LOGE("%s", msg); else LOGI("%s", msg);
    pthread_mutex_lock(&g_log_mu);
    char line[560]; int n = snprintf(line, sizeof(line), "%s %s\n", lvl, msg);
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

/* ── JNI helpers ─────────────────────────────────────────────── */
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

/* ── Detección sndbuf por RAM ────────────────────────────────── */
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

/* ── Socket helpers ──────────────────────────────────────────── */
static void set_nonblocking(int fd) {
    int fl = fcntl(fd, F_GETFL, 0);
    if (fl >= 0) fcntl(fd, F_SETFL, fl | O_NONBLOCK);
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
    int intvl = (mode == MODE_GAMING) ? 3 : 5, cnt = 3;
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE,  &idle,  sizeof(idle));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &intvl, sizeof(intvl));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT,   &cnt,   sizeof(cnt));
    int fl = fcntl(fd, F_GETFD, 0); if (fl >= 0) fcntl(fd, F_SETFD, fl | FD_CLOEXEC);
}

static void tune_client(int fd) {
    int mode = atomic_load(&g_mode);
    int gaming = (mode == MODE_GAMING);
    int v = gaming ? 1 : 0;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &v, sizeof(v));
    v = gaming ? 32768 : g_sndbuf;
    setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &v, sizeof(v));
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &v, sizeof(v));
    int fl = fcntl(fd, F_GETFD, 0); if (fl >= 0) fcntl(fd, F_SETFD, fl | FD_CLOEXEC);
    set_nonblocking(fd);
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

/* ── Mux: escribir frame al servidor ────────────────────────── */
static int tun_send(int tfd, uint8_t type, uint32_t sid,
                    const uint8_t *data, uint16_t dlen) {
    uint8_t hdr[FRAME_HDR];
    hdr[0] = type;
    hdr[1] = (sid>>24)&0xFF; hdr[2] = (sid>>16)&0xFF;
    hdr[3] = (sid>>8)&0xFF;  hdr[4] =  sid&0xFF;
    hdr[5] = (dlen>>8)&0xFF; hdr[6] =  dlen&0xFF;
    struct iovec iov[2] = {{hdr,FRAME_HDR},{(void*)data,dlen}};
    int niov = dlen ? 2 : 1;
    ssize_t total = FRAME_HDR + dlen, sent = 0;
    while (sent < total) {
        pthread_mutex_lock(&g_wmu);
        ssize_t n = writev(tfd, iov, niov);
        if (n > 0) {
            pthread_mutex_unlock(&g_wmu); sent += n;
            if (sent < total) {
                size_t skip = n;
                for (int i = 0; i < niov && skip > 0; i++) {
                    if (skip >= iov[i].iov_len) { skip -= iov[i].iov_len; iov[i].iov_len = 0; }
                    else { iov[i].iov_base = (uint8_t*)iov[i].iov_base+skip; iov[i].iov_len -= skip; skip = 0; }
                }
            }
        } else if (errno == EINTR) {
            pthread_mutex_unlock(&g_wmu);
        } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
            pthread_mutex_unlock(&g_wmu);
            struct pollfd wp = {tfd, POLLOUT, 0};
            if (poll(&wp, 1, (atomic_load(&g_mode)==MODE_GAMING)?5:30) <= 0) return -1;
        } else { pthread_mutex_unlock(&g_wmu); return -1; }
    }
    return 0;
}

/* ── Mux: leer frame del servidor (bloqueante, en tunnel_reader) */
static int tun_recv_full(int fd, uint8_t *buf, int len, int ms) {
    int off = 0;
    while (off < len) {
        struct pollfd p = {fd, POLLIN, 0};
        int pr = poll(&p, 1, ms);
        if (pr < 0) { if (errno == EINTR) continue; return -1; }
        if (pr == 0) return -2;
        ssize_t n = recv(fd, buf+off, len-off, 0);
        if (n > 0) off += n;
        else if (n == 0) return -1;
        else if (errno == EINTR || errno == EAGAIN) continue;
        else return -1;
    }
    return 0;
}

/* ── Conn: crear y destruir ──────────────────────────────────── */
static conn_t *conn_new(int cfd, uint32_t sid) {
    conn_t *c = calloc(1, sizeof(*c));
    if (!c) return NULL;
    c->cfd = cfd; c->sid = sid;
    return c;
}

static void conn_close(conn_t *c, int epfd, int tfd) {
    if (!c) return;
    epoll_ctl(epfd, EPOLL_CTL_DEL, c->cfd, NULL);
    ht_del(c->sid);
    tun_send(tfd, T_CLOSE, c->sid, NULL, 0);
    close(c->cfd);
    free(c);
}

/* ── Conn: enviar datos al cliente (con buffer EAGAIN) ───────── */
static int conn_send(conn_t *c, int epfd, const uint8_t *data, int len) {
    /* Si hay datos pendientes en el buffer, encolar */
    if (c->sndbuf_len > 0) {
        int space = SNDBUF_CAP - (c->sndbuf_off + c->sndbuf_len);
        int copy = len < space ? len : space;
        if (copy <= 0) return -1; /* buffer lleno, conexión lenta — cerrar */
        memcpy(c->sndbuf + c->sndbuf_off + c->sndbuf_len, data, copy);
        c->sndbuf_len += copy;
        return 0;
    }

    /* Intentar envío directo */
    ssize_t sent = 0;
    while (sent < len) {
        ssize_t n = send(c->cfd, data + sent, len - sent, MSG_NOSIGNAL);
        if (n > 0) { sent += n; continue; }
        if (errno == EINTR) continue;
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            /* Buffer del kernel lleno — guardar el resto y esperar EPOLLOUT */
            int rem = len - sent;
            if (rem > SNDBUF_CAP) return -1;
            memcpy(c->sndbuf, data + sent, rem);
            c->sndbuf_off = 0;
            c->sndbuf_len = rem;
            struct epoll_event ev = {EPOLLIN|EPOLLOUT|EPOLLET, {.ptr=c}};
            epoll_ctl(epfd, EPOLL_CTL_MOD, c->cfd, &ev);
            return 0;
        }
        return -1;
    }
    return 0;
}

/* ── Conn: drenar buffer pendiente cuando EPOLLOUT ───────────── */
static int conn_drain(conn_t *c, int epfd) {
    while (c->sndbuf_len > 0) {
        ssize_t n = send(c->cfd,
                         c->sndbuf + c->sndbuf_off,
                         c->sndbuf_len, MSG_NOSIGNAL);
        if (n > 0) {
            c->sndbuf_off += n; c->sndbuf_len -= n;
            continue;
        }
        if (errno == EINTR) continue;
        if (errno == EAGAIN || errno == EWOULDBLOCK) return 0; /* seguir esperando */
        return -1;
    }
    /* Buffer drenado — quitar EPOLLOUT */
    c->sndbuf_off = 0;
    struct epoll_event ev = {EPOLLIN|EPOLLET, {.ptr=c}};
    epoll_ctl(epfd, EPOLL_CTL_MOD, c->cfd, &ev);
    return 0;
}

/* ── HTTP handshake helper ───────────────────────────────────── */
static int recv_eoh(int fd, char *buf, int cap, int sec) {
    struct timeval tv = {sec, 0};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    int used = 0, ok = 0;
    while (used < cap-1) {
        ssize_t n = recv(fd, buf+used, cap-1-used, 0);
        if (n <= 0) break;
        used += n; buf[used] = 0;
        if (strstr(buf, "\r\n\r\n")) { ok = 1; break; }
    }
    tv.tv_sec = 0; setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    return ok ? used : -1;
}

static void parse_hdr(const char *buf, const char *key, char *out, int cap) {
    const char *p = strstr(buf, key); if (!p) return;
    p += strlen(key); while (*p==' '||*p==':') p++;
    const char *e = strstr(p, "\r\n"); if (!e) return;
    int n = (int)(e-p); if (n<=0||n>=cap) return;
    memcpy(out, p, n); out[n] = 0;
}

/* ── Abrir túnel al servidor ─────────────────────────────────── */
static int open_tunnel(void) {
    int fd = -1;
    struct sockaddr_in6 a6 = {0};
    a6.sin6_family = AF_INET6; a6.sin6_port = htons(PROXY_PORT);
    if (inet_pton(AF_INET6, PROXY_HOST_IPV6, &a6.sin6_addr) == 1) {
        fd = socket(AF_INET6, SOCK_STREAM, 0);
        if (fd >= 0) {
            protect_fd(fd); tune_tun(fd);
            if (nb_connect(fd,(struct sockaddr*)&a6,sizeof(a6),CONNECT_TIMEOUT_SEC)!=0)
                { close(fd); fd = -1; }
        }
    }
    if (fd < 0) {
        struct addrinfo hints={0},*res=NULL;
        hints.ai_family=AF_UNSPEC; hints.ai_socktype=SOCK_STREAM;
        char ps[8]; snprintf(ps,sizeof(ps),"%d",PROXY_PORT);
        if (getaddrinfo(PROXY_HOST,ps,&hints,&res)==0) {
            for (struct addrinfo *r=res;r&&fd<0;r=r->ai_next) {
                int s=socket(r->ai_family,SOCK_STREAM,0);
                if (s<0) continue;
                protect_fd(s); tune_tun(s);
                if (nb_connect(s,r->ai_addr,r->ai_addrlen,CONNECT_TIMEOUT_SEC)==0) fd=s;
                else close(s);
            }
            freeaddrinfo(res);
        }
    }
    if (fd < 0) { push_log("E","tunnel connect failed"); return -1; }

    atomic_store(&g_last_pong, (long)time(NULL));

    char req1[256], h1[2048];
    snprintf(req1,sizeof(req1),"GET / HTTP/1.1\r\nHost: %s\r\n\r\n",PROXY_HOST);
    send(fd,req1,strlen(req1),MSG_NOSIGNAL);
    if (recv_eoh(fd,h1,sizeof(h1),HANDSHAKE_TIMEOUT_SEC)<0) { close(fd); return -1; }

    char req2[1024], h2[4096];
    snprintf(req2,sizeof(req2),
        "- / HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\n"
        "Connection: Upgrade\r\nAction: tunnel\r\nX-Internal-ID: %s\r\n\r\n",
        TUNNEL_HOST, g_internal_id[0]?g_internal_id:"unknown");
    send(fd,req2,strlen(req2),MSG_NOSIGNAL);
    int hlen = recv_eoh(fd,h2,sizeof(h2),HANDSHAKE_TIMEOUT_SEC);

    int code=-1; sscanf(h2,"HTTP/%*d.%*d %d",&code);
    if (hlen<0||code!=101) {
        if (code==403) {
            char body[256]={0}; recv(fd,body,sizeof(body)-1,MSG_DONTWAIT);
            if (strstr(h2,"not_registered")||strstr(body,"not_registered"))
                push_log("E","usuario no registrado");
            else if (strstr(h2,"expired")||strstr(body,"expired"))
                push_log("E","usuario expirado");
            else push_log("E","error 403");
        } else push_log("E","handshake failed code=%d",code);
        close(fd); return -1;
    }

    char uname[128]={0}, udays[32]={0};
    parse_hdr(h2,"X-User-Name:",uname,sizeof(uname));
    parse_hdr(h2,"X-User-Days:",udays,sizeof(udays));
    if (uname[0]) push_log("I","user=%s days=%s",uname,udays);
    push_log("I","tunnel ok");
    return fd;
}

/* ── Leer frames del servidor (thread separado) ──────────────── */
/*
 * tunnel_reader corre en su propio thread porque el recv del
 * servidor es bloqueante — no podemos mezclarlo con el loop epoll
 * que maneja los clientes. Cuando llega un frame, escribe directo
 * al socket del cliente via conn_send() que es thread-safe para
 * lecturas (el único escritor del cfd es este thread o el epoll loop).
 *
 * Para evitar el race: tunnel_reader escribe al cfd directamente
 * solo si no hay datos pendientes en sndbuf. Si hay pendientes,
 * encola. El lock por conexión no es necesario porque:
 *  - tunnel_reader es el único que escribe al cfd desde el servidor
 *  - epoll loop solo drena sndbuf cuando hay EPOLLOUT
 *  - epoll loop lee del cfd (cliente→servidor) en otro path
 */
typedef struct { int tfd; int epoch; int epfd; } thr_t;

static void *tunnel_reader(void *arg) {
    thr_t *ta = (thr_t*)arg;
    int tfd=ta->tfd, epoch=ta->epoch, epfd=ta->epfd;
    free(ta);

    uint8_t hdr[FRAME_HDR], payload[MAX_PAYLOAD];

    while (g_running && atomic_load(&g_tunnel_epoch)==epoch) {
        int rc = tun_recv_full(tfd, hdr, FRAME_HDR, 60000);
        if (!g_running || atomic_load(&g_tunnel_epoch)!=epoch) break;
        if (rc==-2) {
            if (atomic_load(&g_last_pong)>0 &&
                (long)time(NULL)-atomic_load(&g_last_pong)>PONG_TIMEOUT_SEC)
                { push_log("E","pong timeout"); break; }
            continue;
        }
        if (rc<0) { push_log("E","tunnel read failed"); break; }

        uint8_t ft = hdr[0];
        uint32_t sid = ((uint32_t)hdr[1]<<24)|((uint32_t)hdr[2]<<16)|
                       ((uint32_t)hdr[3]<<8)|(uint32_t)hdr[4];
        uint16_t len = ((uint16_t)hdr[5]<<8)|hdr[6];
        if (len>MAX_PAYLOAD) { push_log("E","payload too large"); break; }
        if (len>0 && tun_recv_full(tfd,payload,len,30000)<0)
            { push_log("E","payload read failed"); break; }

        switch (ft) {
        case T_DATA: {
            conn_t *c = ht_get(sid);
            if (!c || !len) break;
            if (conn_send(c, epfd, payload, len) < 0)
                conn_close(c, epfd, tfd);
            break;
        }
        case T_CLOSE: {
            conn_t *c = ht_get(sid);
            if (c) conn_close(c, epfd, tfd);
            break;
        }
        case T_PING: tun_send(tfd,T_PONG,0,NULL,0); break;
        case T_PONG: atomic_store(&g_last_pong,(long)time(NULL)); break;
        }
    }

    /* Señalizar reset al epoll loop cerrando el tfd */
    shutdown(tfd, SHUT_RDWR);
    return NULL;
}

/* ── Keepalive ───────────────────────────────────────────────── */
static void *keepalive(void *arg) {
    thr_t *ta=(thr_t*)arg; int tfd=ta->tfd,epoch=ta->epoch; free(ta);
    atomic_store(&g_last_pong,(long)time(NULL));
    int interval=(atomic_load(&g_mode)==MODE_GAMING)?5:20;
    long last=time(NULL);
    while (g_running && atomic_load(&g_tunnel_epoch)==epoch) {
        sleep(1);
        if (!g_running || atomic_load(&g_tunnel_epoch)!=epoch) break;
        long now=time(NULL), pong=atomic_load(&g_last_pong);
        if (pong>0&&now-pong>PONG_TIMEOUT_SEC)
            { push_log("E","pong timeout keepalive"); break; }
        if (now-last<interval) continue;
        last=now;
        struct timespec t0,t1; clock_gettime(CLOCK_MONOTONIC,&t0);
        if (tun_send(tfd,T_PING,0,NULL,0)<0) break;
        long prev=pong;
        for (int us=5000,el=0;el<3000000&&g_running;el+=us) {
            if (atomic_load(&g_last_pong)>prev) break;
            usleep(us); if(us<50000) us*=2;
        }
        clock_gettime(CLOCK_MONOTONIC,&t1);
        long rtt=(t1.tv_sec-t0.tv_sec)*1000+(t1.tv_nsec-t0.tv_nsec)/1000000;
        if (rtt>0) push_log("I","ping_ms=%ld",rtt);
    }
    return NULL;
}

/* ── Reset túnel: cierra fds y limpia estado ─────────────────── */
static void reset_tunnel(int epfd, int tfd, int rfd) {
    pthread_mutex_lock(&g_mu);
    if (g_relay_fd==rfd) g_relay_fd=-1;
    if (g_tun_fd==tfd)   g_tun_fd=-1;
    if (g_epoll_fd==epfd) g_epoll_fd=-1;
    pthread_mutex_unlock(&g_mu);

    /* Cerrar todos los clientes conectados */
    for (int i=0;i<HT_SIZE;i++) {
        pthread_mutex_lock(&g_ht_mu[i]);
        ht_node_t *n=g_ht[i];
        while (n) {
            conn_t *c=n->conn;
            epoll_ctl(epfd,EPOLL_CTL_DEL,c->cfd,NULL);
            close(c->cfd); free(c);
            ht_node_t *nx=n->next; free(n); n=nx;
        }
        g_ht[i]=NULL;
        pthread_mutex_unlock(&g_ht_mu[i]);
    }

    if (rfd>=0) close(rfd);
    if (tfd>=0) close(tfd);
    if (epfd>=0) close(epfd);
}

/* ── Main thread: loop epoll ─────────────────────────────────── */
static void *main_thread(void *arg) {
    int port=(int)(intptr_t)arg;
    int is_first=1;

    while (g_running) {
        /* Conectar al servidor */
        int tfd=open_tunnel();
        if (tfd<0) {
            pthread_mutex_lock(&g_ready_mu);
            if (g_ready_st==0) { g_ready_st=-1; pthread_cond_broadcast(&g_ready_cv); }
            pthread_mutex_unlock(&g_ready_mu);
            if (!g_running) break;
            push_log("E","retry in %ds",g_reconnect_delay);
            for (int i=0;i<g_reconnect_delay&&g_running;i++) sleep(1);
            if (g_reconnect_delay<RECONNECT_DELAY_MAX) g_reconnect_delay*=2;
            if (g_reconnect_delay>RECONNECT_DELAY_MAX) g_reconnect_delay=RECONNECT_DELAY_MAX;
            continue;
        }
        g_reconnect_delay=RECONNECT_DELAY_MIN;

        /* Crear relay local */
        int rfd=socket(AF_INET,SOCK_STREAM,0);
        if (rfd<0) { close(tfd); sleep(1); continue; }
        int one=1; setsockopt(rfd,SOL_SOCKET,SO_REUSEADDR,&one,sizeof(one));
        int fl=fcntl(rfd,F_GETFD,0); if(fl>=0) fcntl(rfd,F_SETFD,fl|FD_CLOEXEC);
        struct sockaddr_in la={0};
        la.sin_family=AF_INET; la.sin_port=htons((uint16_t)port);
        la.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
        if (bind(rfd,(struct sockaddr*)&la,sizeof(la))<0||listen(rfd,RELAY_BACKLOG)<0) {
            close(rfd); close(tfd);
            push_log("E","relay bind failed"); sleep(2); continue;
        }
        set_nonblocking(rfd);

        /* Crear epoll */
        int epfd=epoll_create1(EPOLL_CLOEXEC);
        if (epfd<0) { close(rfd); close(tfd); sleep(1); continue; }

        /* Registrar relay y tfd en epoll */
        struct epoll_event ev;
        ev.events=EPOLLIN; ev.data.fd=rfd;
        epoll_ctl(epfd,EPOLL_CTL_ADD,rfd,&ev);
        ev.events=EPOLLIN; ev.data.fd=tfd;
        epoll_ctl(epfd,EPOLL_CTL_ADD,tfd,&ev);

        int epoch=atomic_fetch_add(&g_tunnel_epoch,1)+1;
        pthread_mutex_lock(&g_mu);
        g_tun_fd=tfd; g_relay_fd=rfd; g_epoll_fd=epfd;
        pthread_mutex_unlock(&g_mu);
        g_started=1;

        push_log("I","relay port=%d epoch=%d",port,epoch);

        pthread_mutex_lock(&g_ready_mu);
        if (g_ready_st==0) { g_ready_st=1; pthread_cond_broadcast(&g_ready_cv); }
        pthread_mutex_unlock(&g_ready_mu);

        if (!is_first) notify_reconnected();
        is_first=0;

        /* Arrancar tunnel_reader y keepalive en threads separados */
        thr_t *ta=malloc(sizeof(*ta)); if(ta){ta->tfd=tfd;ta->epoch=epoch;ta->epfd=epfd;}
        thr_t *tb=malloc(sizeof(*tb)); if(tb){tb->tfd=tfd;tb->epoch=epoch;tb->epfd=epfd;}
        pthread_t tr,tk;
        if(ta){pthread_create(&tr,NULL,tunnel_reader,ta);pthread_detach(tr);}else free(ta);
        if(tb){pthread_create(&tk,NULL,keepalive,tb);pthread_detach(tk);}else free(tb);

        /* ── Loop epoll principal ── */
        struct epoll_event events[MAX_EVENTS];
        int tunnel_dead=0;

        while (g_running && !tunnel_dead) {
            int n=epoll_wait(epfd,events,MAX_EVENTS,
                             atomic_load(&g_mode)==MODE_GAMING ? 1000 : 5000);
            if (n<0) { if(errno==EINTR) continue; break; }

            for (int i=0;i<n&&g_running;i++) {
                int fd=events[i].data.fd;
                uint32_t ev_=events[i].events;

                /* ── Nuevo cliente desde hev ── */
                if (fd==rfd) {
                    struct sockaddr_in ca; socklen_t cl=sizeof(ca);
                    int cfd=accept(rfd,(struct sockaddr*)&ca,&cl);
                    if (cfd<0) continue;
                    tune_client(cfd);

                    uint32_t sid;
                    do { sid=(uint32_t)atomic_fetch_add(&g_next_sid,1)&0x7FFFFFFF; }
                    while (!sid||ht_get(sid));

                    conn_t *c=conn_new(cfd,sid);
                    if (!c) { close(cfd); continue; }
                    ht_put(sid,c);

                    struct epoll_event cev={EPOLLIN|EPOLLET,{.ptr=c}};
                    if (epoll_ctl(epfd,EPOLL_CTL_ADD,cfd,&cev)<0)
                        { conn_close(c,epfd,tfd); continue; }
                    continue;
                }

                /* ── Evento en el túnel: tunnel_reader murió ── */
                if (fd==tfd) {
                    tunnel_dead=1; break;
                }

                /* ── Evento en socket de cliente ── */
                conn_t *c=(conn_t*)events[i].data.ptr;
                if (!c) continue;

                /* Drenar buffer pendiente si EPOLLOUT */
                if (ev_ & EPOLLOUT) {
                    if (conn_drain(c,epfd)<0)
                        { conn_close(c,epfd,tfd); continue; }
                }

                /* Leer datos del cliente → mandar al servidor */
                if (ev_ & EPOLLIN) {
                    uint8_t buf[MAX_PAYLOAD];
                    ssize_t nr=recv(c->cfd,buf,sizeof(buf),0);
                    if (nr>0) {
                        if (tun_send(tfd,T_DATA,c->sid,buf,(uint16_t)nr)<0)
                            { tunnel_dead=1; break; }
                    } else if (nr==0) {
                        /* Cliente cerró — mandar T_CLOSE y esperar T_CLOSE del server */
                        shutdown(c->cfd,SHUT_RD);
                        tun_send(tfd,T_CLOSE,c->sid,NULL,0);
                        c->client_closed=1;
                        struct epoll_event cev={EPOLLOUT|EPOLLET,{.ptr=c}};
                        epoll_ctl(epfd,EPOLL_CTL_MOD,c->cfd,&cev);
                    } else if (errno!=EAGAIN&&errno!=EINTR) {
                        conn_close(c,epfd,tfd); continue;
                    }
                }

                /* Error en el cliente */
                if (ev_ & (EPOLLERR|EPOLLHUP))
                    conn_close(c,epfd,tfd);
            }
        }

        push_log("E","tunnel dropped, reconnecting");
        reset_tunnel(epfd,tfd,rfd);
        g_started=0;
        if (g_running) sleep(1);
    }

    g_started=0;
    return NULL;
}

/* ── JNI exports ─────────────────────────────────────────────── */
JNIEXPORT void JNICALL Java_com_blacktunnel_BtProxy_nativeStop(JNIEnv*,jclass);

JNIEXPORT jint JNICALL
Java_com_blacktunnel_BtProxy_nativeStart(JNIEnv *env, jclass clazz,
                                          jint port, jobject svc, jstring iid) {
    pthread_mutex_lock(&g_mu);
    if (g_running) { pthread_mutex_unlock(&g_mu); return 0; }
    (*env)->GetJavaVM(env,&g_jvm);
    g_svc=(*env)->NewGlobalRef(env,svc);
    g_internal_id[0]=0;
    if (iid) {
        const char *s=(*env)->GetStringUTFChars(env,iid,NULL);
        if(s){snprintf(g_internal_id,sizeof(g_internal_id),"%s",s);
              (*env)->ReleaseStringUTFChars(env,iid,s);}
    }
    g_sndbuf=detect_sndbuf();
    ht_init();
    g_running=1; g_started=0;
    g_reconnect_delay=RECONNECT_DELAY_MIN;
    atomic_store(&g_next_sid,1);
    pthread_mutex_unlock(&g_mu);

    pthread_mutex_lock(&g_ready_mu); g_ready_st=0; pthread_mutex_unlock(&g_ready_mu);

    pthread_t thr;
    if (pthread_create(&thr,NULL,main_thread,(void*)(intptr_t)port)!=0) {
        pthread_mutex_lock(&g_mu); g_running=0;
        (*env)->DeleteGlobalRef(env,g_svc); g_svc=NULL; g_jvm=NULL;
        pthread_mutex_unlock(&g_mu); return -1;
    }
    pthread_detach(thr);

    struct timespec ts; clock_gettime(CLOCK_REALTIME,&ts); ts.tv_sec+=12;
    pthread_mutex_lock(&g_ready_mu);
    while (g_ready_st==0)
        if (pthread_cond_timedwait(&g_ready_cv,&g_ready_mu,&ts)!=0) break;
    int st=g_ready_st;
    pthread_mutex_unlock(&g_ready_mu);

    if (st!=1) { Java_com_blacktunnel_BtProxy_nativeStop(env,clazz); return -1; }
    push_log("I","nativeStart ok");
    return 0;
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeStop(JNIEnv *env, jclass clazz) {
    pthread_mutex_lock(&g_mu);
    if (!g_running) { pthread_mutex_unlock(&g_mu); return; }
    g_running=0;
    g_internal_id[0]=0;
    jobject svc=g_svc; g_svc=NULL; g_jvm=NULL;
    int rfd=g_relay_fd; g_relay_fd=-1;
    int tfd=g_tun_fd;   g_tun_fd=-1;
    int epfd=g_epoll_fd; g_epoll_fd=-1;
    pthread_mutex_unlock(&g_mu);

    atomic_fetch_add(&g_tunnel_epoch,1);
    if (epfd>=0) close(epfd);
    if (rfd>=0) { shutdown(rfd,SHUT_RDWR); close(rfd); }
    if (tfd>=0) { shutdown(tfd,SHUT_RDWR); close(tfd); }

    pthread_mutex_lock(&g_ready_mu);
    g_ready_st=-1; pthread_cond_broadcast(&g_ready_cv);
    pthread_mutex_unlock(&g_ready_mu);

    for (int i=0;i<50&&g_started;i++) usleep(10000);
    ht_clear();
    if (svc) (*env)->DeleteGlobalRef(env,svc);
    g_started=0;
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeSetGamingMode(JNIEnv *e,jclass c,jboolean en) {
    atomic_store(&g_mode,en?MODE_GAMING:MODE_DAILY);
    push_log("I","mode=%s",en?"gaming":"daily");
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeApplyMode(JNIEnv *e,jclass c,jboolean en) {
    atomic_store(&g_mode,en?MODE_GAMING:MODE_DAILY);
    push_log("I","apply_mode=%s",en?"gaming":"daily");
}

JNIEXPORT jint JNICALL
Java_com_blacktunnel_BtProxy_nativeGetGamingMode(JNIEnv *e,jclass c) {
    return atomic_load(&g_mode);
}

JNIEXPORT jstring JNICALL
Java_com_blacktunnel_BtProxy_nativeDrainLogs(JNIEnv *env,jclass c) {
    pthread_mutex_lock(&g_log_mu);
    if (!g_loglen) { pthread_mutex_unlock(&g_log_mu); return (*env)->NewStringUTF(env,""); }
    char out[32768]; memcpy(out,g_logbuf,g_loglen); out[g_loglen]=0;
    g_loglen=0; g_logbuf[0]=0;
    pthread_mutex_unlock(&g_log_mu);
    return (*env)->NewStringUTF(env,out);
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeSetNetwork(JNIEnv *e,jclass c,jlong net) {
    pthread_mutex_lock(&g_mu); g_net=(net_handle_t)net; pthread_mutex_unlock(&g_mu);
}

static JNINativeMethod g_methods[]={
    {"nativeStart","(ILandroid/net/VpnService;Ljava/lang/String;)I",
                   (void*)Java_com_blacktunnel_BtProxy_nativeStart},
    {"nativeStop","()V",(void*)Java_com_blacktunnel_BtProxy_nativeStop},
    {"nativeDrainLogs","()Ljava/lang/String;",
                       (void*)Java_com_blacktunnel_BtProxy_nativeDrainLogs},
    {"nativeSetGamingMode","(Z)V",(void*)Java_com_blacktunnel_BtProxy_nativeSetGamingMode},
    {"nativeApplyMode","(Z)V",(void*)Java_com_blacktunnel_BtProxy_nativeApplyMode},
    {"nativeGetGamingMode","()I",(void*)Java_com_blacktunnel_BtProxy_nativeGetGamingMode},
    {"nativeSetNetwork","(J)V",(void*)Java_com_blacktunnel_BtProxy_nativeSetNetwork},
};

JNIEXPORT jint JNI_OnLoad(JavaVM *vm,void *r) {
    JNIEnv *env=NULL;
    if ((*vm)->GetEnv(vm,(void**)&env,JNI_VERSION_1_6)!=JNI_OK) return JNI_ERR;
    jclass cls=(*env)->FindClass(env,"com/blacktunnel/BtProxy");
    if (!cls) return JNI_ERR;
    if ((*env)->RegisterNatives(env,cls,g_methods,
            sizeof(g_methods)/sizeof(g_methods[0]))<0) return JNI_ERR;
    (*env)->DeleteLocalRef(env,cls);
    return JNI_VERSION_1_6;
}
