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

#define LOG_TAG "btproxy"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ── Protocolo de frame: 7 bytes fijos ──────────────────────────────────────
   [ TYPE(1) | SID(4 big-endian) | LEN(2 big-endian) ] + payload(LEN bytes)  */
#define T_SYN   0x00
#define T_DATA  0x01
#define T_FIN   0x02
#define T_RST   0x03
#define T_NOP   0x04
#define T_PING  0x05
#define T_PONG  0x06

#define FRAME_HDR        7
#define MAX_PAYLOAD      65527   /* 65535 - 8; cabe en uint16_t */

/* ── Watermarks de la cola global hacia el servidor ─────────────────────── */
#define WQ_HIGH_WATER    (512 * 1024)   /* 512 KB — pausar lecturas locales  */
#define WQ_LOW_WATER     (128 * 1024)   /* 128 KB — reanudar lecturas locales */

/* ── Cola de bajada por stream ──────────────────────────────────────────── */
#define LQ_HARD_LIMIT    (512 * 1024)   /* 512 KB — RST si se supera         */

/* ── Timeouts y parámetros generales ────────────────────────────────────── */
#define MAX_STREAMS          256
#define RELAY_BACKLOG        512
#define MAX_EPOLL_EVENTS     64
#define EPOLL_TIMEOUT_MS     5000

#define KEEPALIVE_INTERVAL_MS  20000
#define KEEPALIVE_TIMEOUT_MS   60000
#define STREAM_TIMEOUT_MS      90000
#define CONNECT_TIMEOUT_MS     10000
#define HANDSHAKE_TIMEOUT_SEC  4
#define SLOW_START_GRACE_MS    5000

#define INITIAL_RECONNECT_MS   500
#define MAX_RECONNECT_MS       30000

#define PROXY_HOST  "emailmarketing.personal.com.ar"
#define PROXY_PORT  80
#define TUNNEL_HOST "2.brawlpass.com.ar"

static const char *PROXY_IPS[] = {
    "2606:4700::6812:16b7",
    "2606:4700::6812:17b7",
};
#define PROXY_IP_COUNT 2

/* ═══════════════════════════════════════════════════════════════════════════
   Utilidades de tiempo
   ═══════════════════════════════════════════════════════════════════════════ */
static long now_mono_ms(void) {
    struct timespec ts = {0};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)(ts.tv_sec * 1000L + ts.tv_nsec / 1000000L);
}

/* ═══════════════════════════════════════════════════════════════════════════
   Log circular
   ═══════════════════════════════════════════════════════════════════════════ */
static pthread_mutex_t g_log_mu  = PTHREAD_MUTEX_INITIALIZER;
static char            g_logbuf[32768];
static size_t          g_loglen  = 0;

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

/* ═══════════════════════════════════════════════════════════════════════════
   Cola de chunks genérica (lista enlazada de bloques de bytes)
   ═══════════════════════════════════════════════════════════════════════════ */
typedef struct Chunk {
    struct Chunk *next;
    size_t        len;
    size_t        offset;
    uint8_t       data[];
} Chunk;

typedef struct {
    Chunk  *head;
    Chunk  *tail;
    size_t  bytes;
} ChunkQueue;

static void cq_init(ChunkQueue *q) { q->head = q->tail = NULL; q->bytes = 0; }

static int cq_push(ChunkQueue *q, const uint8_t *data, size_t len) {
    Chunk *c = malloc(sizeof(Chunk) + len);
    if (!c) return -1;
    c->next   = NULL;
    c->len    = len;
    c->offset = 0;
    memcpy(c->data, data, len);
    if (q->tail) q->tail->next = c; else q->head = c;
    q->tail = c;
    q->bytes += len;
    return 0;
}

static void cq_flush(ChunkQueue *q) {
    Chunk *c = q->head;
    while (c) { Chunk *nx = c->next; free(c); c = nx; }
    q->head = q->tail = NULL; q->bytes = 0;
}

/* ═══════════════════════════════════════════════════════════════════════════
   Cola de frames hacia el servidor (frames completos)
   ═══════════════════════════════════════════════════════════════════════════ */
typedef struct Frame {
    struct Frame *next;
    size_t        total;
    size_t        offset;
    uint8_t       data[];
} Frame;

typedef struct {
    Frame  *head;
    Frame  *tail;
    size_t  bytes;
} FrameQueue;

static void fq_init(FrameQueue *q) { q->head = q->tail = NULL; q->bytes = 0; }

static Frame *frame_alloc(uint8_t type, uint32_t sid,
                          const uint8_t *payload, uint16_t plen) {
    size_t total = FRAME_HDR + plen;
    Frame *f = malloc(sizeof(Frame) + total);
    if (!f) return NULL;
    f->next   = NULL;
    f->total  = total;
    f->offset = 0;
    f->data[0] = type;
    f->data[1] = (sid >> 24) & 0xFF;
    f->data[2] = (sid >> 16) & 0xFF;
    f->data[3] = (sid >>  8) & 0xFF;
    f->data[4] =  sid        & 0xFF;
    f->data[5] = (plen >> 8) & 0xFF;
    f->data[6] =  plen       & 0xFF;
    if (plen && payload) memcpy(f->data + FRAME_HDR, payload, plen);
    return f;
}

static void fq_push(FrameQueue *q, Frame *f) {
    if (q->tail) q->tail->next = f; else q->head = f;
    q->tail = f;
    q->bytes += f->total - f->offset;
}

static void fq_flush(FrameQueue *q) {
    Frame *f = q->head;
    while (f) { Frame *nx = f->next; free(f); f = nx; }
    q->head = q->tail = NULL; q->bytes = 0;
}

/* ═══════════════════════════════════════════════════════════════════════════
   Stream
   ═══════════════════════════════════════════════════════════════════════════ */
typedef enum { SS_OPEN, SS_LOCAL_FIN, SS_REMOTE_FIN, SS_CLOSED } StreamState;

typedef struct Stream {
    struct Stream *ht_next;
    uint32_t      id;
    int           fd_local;
    StreamState   state;
    bool          paused_read;
    bool          paused_server;
    bool          remote_fin_pending;
    long          last_activity_ms;
    ChunkQueue    lq;
    uint8_t       read_buf[MAX_PAYLOAD];
} Stream;

/* ── Tabla hash de streams ── */
#define SHT_SIZE  256
#define SHT_MASK  (SHT_SIZE - 1)

typedef struct {
    Stream *buckets[SHT_SIZE];
    int     count;
} StreamHT;

static void sht_init(StreamHT *ht) {
    memset(ht->buckets, 0, sizeof(ht->buckets));
    ht->count = 0;
}

static Stream *sht_get(StreamHT *ht, uint32_t id) {
    Stream *s = ht->buckets[id & SHT_MASK];
    while (s) { if (s->id == id) return s; s = s->ht_next; }
    return NULL;
}

static Stream *sht_get_by_fd(StreamHT *ht, int fd) {
    for (int i = 0; i < SHT_SIZE; i++) {
        Stream *s = ht->buckets[i];
        while (s) { if (s->fd_local == fd) return s; s = s->ht_next; }
    }
    return NULL;
}

static void sht_put(StreamHT *ht, Stream *s) {
    int slot = s->id & SHT_MASK;
    s->ht_next = ht->buckets[slot];
    ht->buckets[slot] = s;
    ht->count++;
}

static void sht_del(StreamHT *ht, uint32_t id) {
    int slot = id & SHT_MASK;
    Stream **pp = &ht->buckets[slot];
    while (*pp) {
        if ((*pp)->id == id) { *pp = (*pp)->ht_next; ht->count--; return; }
        pp = &(*pp)->ht_next;
    }
}

/* ═══════════════════════════════════════════════════════════════════════════
   Estado de lectura del servidor (state machine)
   ═══════════════════════════════════════════════════════════════════════════ */
typedef enum { RP_HEADER, RP_DATA } ReadPhase;

typedef struct {
    ReadPhase phase;
    uint8_t   hdr[FRAME_HDR];
    int       hdr_pos;
    uint8_t   cmd;
    uint32_t  stream_id;
    uint16_t  data_len;
    uint8_t  *data_buf;
    int       data_pos;
} ReadState;

static void rs_reset(ReadState *rs) {
    rs->phase    = RP_HEADER;
    rs->hdr_pos  = 0;
    rs->data_pos = 0;
    if (rs->data_buf) { free(rs->data_buf); rs->data_buf = NULL; }
    rs->data_len = 0;
}

/* ═══════════════════════════════════════════════════════════════════════════
   Sesión (estado global de una conexión túnel activa)
   ═══════════════════════════════════════════════════════════════════════════ */
typedef enum { ST_CONNECTING, ST_CONNECTED, ST_DRAINING, ST_CLOSED } SessState;

typedef struct Session {
    int        fd_server;
    int        fd_listen;
    int        fd_epoll;
    int        fd_wake_r;
    int        fd_wake_w;
    SessState  state;

    FrameQueue wq;
    Frame     *wp;

    ReadState  rs;
    StreamHT   streams;
    uint32_t   next_sid;

    long       last_activity_ms;
    long       connected_at_ms;
    long       ping_sent_at_ms;
    uint32_t   last_rtt_ms;
    uint32_t   reconnect_delay_ms;
} Session;

/* ═══════════════════════════════════════════════════════════════════════════
   Globals mínimos (JNI + control de vida)
   ═══════════════════════════════════════════════════════════════════════════ */
static volatile int    g_running     = 0;
static JavaVM         *g_jvm         = NULL;
static jobject         g_svc         = NULL;
static net_handle_t    g_net         = NETWORK_UNSPECIFIED;
static pthread_mutex_t g_mu          = PTHREAD_MUTEX_INITIALIZER;
static pthread_t       g_main_thread = 0;
static char            g_internal_id[160] = {0};

static int  g_stop_r = -1, g_stop_w = -1;

/* ═══════════════════════════════════════════════════════════════════════════
   protect_fd — evitar que el socket del túnel pase por la VPN
   ═══════════════════════════════════════════════════════════════════════════ */
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

/* ═══════════════════════════════════════════════════════════════════════════
   epoll helpers
   ═══════════════════════════════════════════════════════════════════════════ */
static void ep_add(int epfd, int fd, uint32_t evs, uint64_t data) {
    struct epoll_event ev;
    ev.events   = evs;
    ev.data.u64 = data;
    epoll_ctl(epfd, EPOLL_CTL_ADD, fd, &ev);
}

static void ep_mod(int epfd, int fd, uint32_t evs, uint64_t data) {
    struct epoll_event ev;
    ev.events   = evs;
    ev.data.u64 = data;
    epoll_ctl(epfd, EPOLL_CTL_MOD, fd, &ev);
}

static void ep_del(int epfd, int fd) {
    epoll_ctl(epfd, EPOLL_CTL_DEL, fd, NULL);
}

/* ═══════════════════════════════════════════════════════════════════════════
   Handshake HTTP hacia el proxy/túnel (bloqueante — solo al conectar)
   ═══════════════════════════════════════════════════════════════════════════ */
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

static void parse_hdr(const char *hdrs, const char *key,
                      char *out, size_t cap) {
    if (!hdrs || !key || !out || cap == 0) return;
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
            if (vlen >= cap) vlen = cap - 1;
            memcpy(out, v, vlen); out[vlen] = 0;
            return;
        }
        if (!eol) break;
        p = eol + 2;
    }
}

static int try_connect_ip(const char *ip) {
    struct sockaddr_in6 a = {0};
    a.sin6_family = AF_INET6;
    a.sin6_port   = htons(PROXY_PORT);
    if (inet_pton(AF_INET6, ip, &a.sin6_addr) != 1) return -1;

    int fd = socket(AF_INET6, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    protect_fd(fd);
    fcntl(fd, F_SETFD, FD_CLOEXEC);

    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY,  &one, sizeof(one));
    setsockopt(fd, SOL_SOCKET,  SO_KEEPALIVE, &one, sizeof(one));
    int v;
    v = 30;  setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE,  &v, sizeof(v));
    v = 10;  setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &v, sizeof(v));
    v = 3;   setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT,   &v, sizeof(v));
    v = 524288; setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &v, sizeof(v));
    v = 524288; setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &v, sizeof(v));

    int fl = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, fl | O_NONBLOCK);

    int r = connect(fd, (struct sockaddr *)&a, sizeof(a));
    if (r == 0) { fcntl(fd, F_SETFL, fl); return fd; }
    if (errno != EINPROGRESS) { close(fd); return -1; }

    struct pollfd p = {fd, POLLOUT, 0};
    if (poll(&p, 1, CONNECT_TIMEOUT_MS) <= 0) { close(fd); return -1; }
    int e = 0; socklen_t el = sizeof(e);
    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &e, &el) < 0 || e != 0)
        { close(fd); return -1; }

    fcntl(fd, F_SETFL, fl);
    return fd;
}

static int open_tunnel(void) {
    push_log("I", "conectando...");

    int fd = -1;
    for (int i = 0; i < PROXY_IP_COUNT && fd < 0; i++) {
        push_log("I", "probando %s", PROXY_IPS[i]);
        fd = try_connect_ip(PROXY_IPS[i]);
    }
    if (fd < 0) { push_log("E", "connect failed"); return -1; }

    char buf[4096];
    snprintf(buf, sizeof(buf),
             "GET / HTTP/1.1\r\nHost: %s\r\n\r\n", PROXY_HOST);
    send(fd, buf, strlen(buf), MSG_NOSIGNAL);
    if (recv_eoh(fd, buf, sizeof(buf), HANDSHAKE_TIMEOUT_SEC) < 0) {
        push_log("E", "proxy no responde"); close(fd); return -1;
    }

    char req[1024];
    snprintf(req, sizeof(req),
        "- / HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\n"
        "Connection: Upgrade\r\nAction: tunnel\r\nX-Internal-ID: %s\r\n\r\n",
        TUNNEL_HOST, g_internal_id[0] ? g_internal_id : "unknown");
    send(fd, req, strlen(req), MSG_NOSIGNAL);

    char h2[4096];
    int hlen = recv_eoh(fd, h2, sizeof(h2), HANDSHAKE_TIMEOUT_SEC);
    int code = -1;
    sscanf(h2, "HTTP/%*d.%*d %d", &code);
    if (hlen < 0 || code != 101) {
        push_log("E", "handshake failed code=%d", code);
        close(fd); return -1;
    }

    char uname[128] = {0}, udays[32] = {0};
    parse_hdr(h2, "X-User-Name:", uname, sizeof(uname));
    parse_hdr(h2, "X-User-Days:", udays, sizeof(udays));
    if (uname[0]) push_log("I", "user_name=%s", uname);
    if (udays[0]) push_log("I", "user_days=%s", udays);
    push_log("I", "tunnel ok");

    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) | O_NONBLOCK);
    return fd;
}

/* ═══════════════════════════════════════════════════════════════════════════
   Socket de escucha local
   ═══════════════════════════════════════════════════════════════════════════ */
static int make_listen_socket(int port) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &one, sizeof(one));
    fcntl(fd, F_SETFD, FD_CLOEXEC);
    struct sockaddr_in a = {0};
    a.sin_family      = AF_INET;
    a.sin_port        = htons((uint16_t)port);
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(fd, (struct sockaddr *)&a, sizeof(a)) < 0 ||
        listen(fd, RELAY_BACKLOG) < 0) { close(fd); return -1; }
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) | O_NONBLOCK);
    return fd;
}

/* ═══════════════════════════════════════════════════════════════════════════
   Gestión de streams
   ═══════════════════════════════════════════════════════════════════════════ */

/* forward declarations */
static void handle_local_readable(Session *ses, Stream *st);
static void handle_server_fatal(Session *ses);
static void on_remote_fin_deferred(Session *ses, Stream *st);

static Stream *stream_alloc(uint32_t id, int fd_local) {
    Stream *st = calloc(1, sizeof(Stream));
    if (!st) return NULL;
    st->id              = id;
    st->fd_local        = fd_local;
    st->state           = SS_OPEN;
    st->last_activity_ms = now_mono_ms();
    cq_init(&st->lq);
    return st;
}

static void stream_destroy(Session *ses, Stream *st) {
    ep_del(ses->fd_epoll, st->fd_local);
    close(st->fd_local);
    cq_flush(&st->lq);
    sht_del(&ses->streams, st->id);
    free(st);

    if (ses->state == ST_DRAINING && ses->streams.count == 0)
        handle_server_fatal(ses);
}

static void stream_pause_local_read(Session *ses, Stream *st) {
    if (st->paused_read) return;
    st->paused_read = true;
    ep_mod(ses->fd_epoll, st->fd_local,
           EPOLLET, (uint64_t)st->fd_local);
}

static void stream_resume_local_read(Session *ses, Stream *st) {
    if (!st->paused_read) return;
    st->paused_read = false;
    ep_mod(ses->fd_epoll, st->fd_local,
           EPOLLIN | EPOLLET, (uint64_t)st->fd_local);
    handle_local_readable(ses, st);
}

static void close_all_streams(Session *ses) {
    for (int i = 0; i < SHT_SIZE; i++) {
        Stream *st = ses->streams.buckets[i];
        while (st) {
            Stream *nx = st->ht_next;
            shutdown(st->fd_local, SHUT_RDWR);
            ep_del(ses->fd_epoll, st->fd_local);
            close(st->fd_local);
            cq_flush(&st->lq);
            free(st);
            st = nx;
        }
        ses->streams.buckets[i] = NULL;
    }
    ses->streams.count = 0;
}

/* ═══════════════════════════════════════════════════════════════════════════
   Cola de escritura hacia el servidor
   ═══════════════════════════════════════════════════════════════════════════ */

/* Intenta enviar bytes del frame desde frame->offset.
   Devuelve bytes enviados acumulados desde offset, o -1 en error fatal. */
static ssize_t try_send_frame(Session *ses, Frame *f) {
    while (f->offset < f->total) {
        ssize_t n = send(ses->fd_server,
                         f->data + f->offset,
                         f->total - f->offset,
                         MSG_NOSIGNAL);
        if (n > 0) {
            f->offset += (size_t)n;
        } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
            break;
        } else if (errno == EINTR) {
            continue;
        } else {
            handle_server_fatal(ses);
            return -1;
        }
    }
    return (ssize_t)f->offset;
}

static void resume_all_paused_reads(Session *ses) {
    for (int i = 0; i < SHT_SIZE; i++) {
        Stream *st = ses->streams.buckets[i];
        while (st) {
            if (st->paused_read) stream_resume_local_read(ses, st);
            st = st->ht_next;
        }
    }
}

/* Encola un frame y lo intenta enviar de inmediato si es posible.
   Durante CONNECTING descarta DATA/FIN/RST. */
static void enqueue_and_flush(Session *ses, Frame *f) {
    if (!f) return;

    if (ses->state != ST_CONNECTED || ses->fd_server < 0) {
        if (f->data[0] == T_DATA ||
            f->data[0] == T_FIN  ||
            f->data[0] == T_RST) { free(f); return; }
        fq_push(&ses->wq, f);
        return;
    }

    if (!ses->wq.head && !ses->wp) {
        ssize_t r = try_send_frame(ses, f);
        if (r < 0) return;
        if (f->offset >= f->total) {
            ses->last_activity_ms = now_mono_ms();
            free(f); return;
        }
        ses->wp = f;
    } else {
        fq_push(&ses->wq, f);
    }

    ep_mod(ses->fd_epoll, ses->fd_server,
           EPOLLIN | EPOLLOUT | EPOLLET, (uint64_t)ses->fd_server);
}

static void send_ctrl(Session *ses, uint8_t type, uint32_t sid) {
    Frame *f = frame_alloc(type, sid, NULL, 0);
    if (f) enqueue_and_flush(ses, f);
}

/* ═══════════════════════════════════════════════════════════════════════════
   handle_server_writable — drenar write queue
   ═══════════════════════════════════════════════════════════════════════════ */
static void handle_server_writable(Session *ses) {
    if (ses->wp) {
        if (try_send_frame(ses, ses->wp) < 0) return;
        if (ses->wp->offset >= ses->wp->total) {
            ses->last_activity_ms = now_mono_ms();
            free(ses->wp); ses->wp = NULL;
        } else return;
    }

    while (ses->wq.head) {
        Frame *f = ses->wq.head;
        ses->wq.bytes -= (f->total - f->offset);
        ses->wq.head   = f->next;
        if (!ses->wq.head) ses->wq.tail = NULL;

        if (try_send_frame(ses, f) < 0) return;
        if (f->offset >= f->total) {
            ses->last_activity_ms = now_mono_ms();
            free(f);
        } else {
            ses->wp = f;
            break;
        }

        if (ses->wq.bytes < WQ_LOW_WATER)
            resume_all_paused_reads(ses);
    }

    if (!ses->wp && !ses->wq.head) {
        if (ses->wq.bytes < WQ_LOW_WATER)
            resume_all_paused_reads(ses);
        ep_mod(ses->fd_epoll, ses->fd_server,
               EPOLLIN | EPOLLET, (uint64_t)ses->fd_server);
    }
}

/* ═══════════════════════════════════════════════════════════════════════════
   Subida: local → servidor
   ═══════════════════════════════════════════════════════════════════════════ */
static void handle_local_readable(Session *ses, Stream *st) {
    if (ses->state != ST_CONNECTED) return;

    while (true) {
        ssize_t n = recv(st->fd_local, st->read_buf, sizeof(st->read_buf), 0);
        if (n > 0) {
            st->last_activity_ms = now_mono_ms();
            Frame *f = frame_alloc(T_DATA, st->id, st->read_buf, (uint16_t)n);
            enqueue_and_flush(ses, f);

            size_t wq_total = ses->wq.bytes + (ses->wp ? ses->wp->total - ses->wp->offset : 0);
            if (wq_total > WQ_HIGH_WATER) {
                stream_pause_local_read(ses, st);
                break;
            }
        } else if (n == 0) {
            send_ctrl(ses, T_FIN, st->id);
            st->state = SS_LOCAL_FIN;
            if (st->remote_fin_pending && !st->lq.head)
                stream_destroy(ses, st);
            break;
        } else {
            if (errno == EAGAIN || errno == EWOULDBLOCK) break;
            if (errno == EINTR) continue;
            send_ctrl(ses, T_RST, st->id);
            stream_destroy(ses, st);
            break;
        }
    }
}

/* ═══════════════════════════════════════════════════════════════════════════
   Bajada: servidor → local
   ═══════════════════════════════════════════════════════════════════════════ */
static void write_to_local(Session *ses, Stream *st,
                            const uint8_t *data, size_t len) {
    size_t off = 0;
    while (off < len) {
        ssize_t n = send(st->fd_local, data + off, len - off, MSG_NOSIGNAL);
        if (n > 0) {
            off += (size_t)n;
        } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
            cq_push(&st->lq, data + off, len - off);
            st->paused_server = true;
            ep_mod(ses->fd_epoll, st->fd_local,
                   EPOLLIN | EPOLLOUT | EPOLLET, (uint64_t)st->fd_local);
            return;
        } else if (errno == EINTR) {
            continue;
        } else {
            send_ctrl(ses, T_RST, st->id);
            stream_destroy(ses, st);
            return;
        }
    }
    st->last_activity_ms = now_mono_ms();
}

static void handle_local_writable(Session *ses, Stream *st) {
    while (st->lq.head) {
        Chunk *c = st->lq.head;
        ssize_t n = send(st->fd_local,
                         c->data + c->offset,
                         c->len  - c->offset,
                         MSG_NOSIGNAL);
        if (n > 0) {
            c->offset               += (size_t)n;
            st->lq.bytes            -= (size_t)n;
            if (c->offset >= c->len) {
                st->lq.head = c->next;
                if (!st->lq.head) st->lq.tail = NULL;
                free(c);
            }
        } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
            return;
        } else if (errno == EINTR) {
            continue;
        } else {
            send_ctrl(ses, T_RST, st->id);
            stream_destroy(ses, st);
            return;
        }
    }

    st->lq.bytes      = 0;
    st->paused_server = false;
    st->last_activity_ms = now_mono_ms();

    ep_mod(ses->fd_epoll, st->fd_local,
           EPOLLIN | EPOLLET, (uint64_t)st->fd_local);

    if (st->remote_fin_pending)
        on_remote_fin_deferred(ses, st);
}

/* ═══════════════════════════════════════════════════════════════════════════
   Dispatch de frames recibidos del servidor
   ═══════════════════════════════════════════════════════════════════════════ */
static void on_remote_fin_deferred(Session *ses, Stream *st) {
    shutdown(st->fd_local, SHUT_WR);
    st->remote_fin_pending = false;
    if (st->state == SS_LOCAL_FIN)
        stream_destroy(ses, st);
}

static void dispatch_data(Session *ses, ReadState *rs) {
    Stream *st = sht_get(&ses->streams, rs->stream_id);
    if (!st || st->state == SS_CLOSED) return;

    if (st->lq.bytes + rs->data_len > LQ_HARD_LIMIT) {
        send_ctrl(ses, T_RST, st->id);
        stream_destroy(ses, st);
        return;
    }

    if (st->paused_server) {
        cq_push(&st->lq, rs->data_buf, rs->data_len);
        st->lq.bytes += rs->data_len;
        return;
    }

    write_to_local(ses, st, rs->data_buf, rs->data_len);
}

static void dispatch_ctrl(Session *ses, ReadState *rs) {
    switch (rs->cmd) {
    case T_SYN:
        break;
    case T_FIN: {
        Stream *st = sht_get(&ses->streams, rs->stream_id);
        if (!st) break;
        st->state = SS_REMOTE_FIN;
        if (st->lq.head) { st->remote_fin_pending = true; break; }
        on_remote_fin_deferred(ses, st);
        break;
    }
    case T_RST: {
        Stream *st = sht_get(&ses->streams, rs->stream_id);
        if (st) stream_destroy(ses, st);
        break;
    }
    case T_NOP:
        ses->last_activity_ms = now_mono_ms();
        break;
    case T_PING:
        send_ctrl(ses, T_PONG, 0);
        break;
    case T_PONG:
        if (ses->ping_sent_at_ms > 0) {
            ses->last_rtt_ms    = (uint32_t)(now_mono_ms() - ses->ping_sent_at_ms);
            push_log("I", "ping_ms=%u", ses->last_rtt_ms);
            ses->ping_sent_at_ms = 0;
        }
        ses->last_activity_ms = now_mono_ms();
        break;
    }
}

/* ═══════════════════════════════════════════════════════════════════════════
   handle_server_readable — state machine de lectura de frames
   ═══════════════════════════════════════════════════════════════════════════ */
static void handle_server_readable(Session *ses) {
    ReadState *rs = &ses->rs;

    while (true) {
        if (rs->phase == RP_HEADER) {
            ssize_t n = recv(ses->fd_server,
                             rs->hdr + rs->hdr_pos,
                             FRAME_HDR - rs->hdr_pos, 0);
            if (n > 0) {
                rs->hdr_pos += n;
                ses->last_activity_ms = now_mono_ms();
                if (rs->hdr_pos == FRAME_HDR) {
                    rs->cmd       = rs->hdr[0];
                    rs->stream_id = ((uint32_t)rs->hdr[1] << 24)
                                  | ((uint32_t)rs->hdr[2] << 16)
                                  | ((uint32_t)rs->hdr[3] <<  8)
                                  |  (uint32_t)rs->hdr[4];
                    rs->data_len  = ((uint16_t)rs->hdr[5] << 8)
                                  |  (uint16_t)rs->hdr[6];
                    if (rs->data_len == 0) {
                        dispatch_ctrl(ses, rs);
                        rs_reset(rs);
                    } else {
                        rs->data_buf = malloc(rs->data_len);
                        if (!rs->data_buf) { handle_server_fatal(ses); return; }
                        rs->data_pos = 0;
                        rs->phase    = RP_DATA;
                    }
                }
            } else if (n == 0) {
                handle_server_fatal(ses); return;
            } else {
                if (errno == EAGAIN || errno == EWOULDBLOCK) return;
                if (errno == EINTR) continue;
                handle_server_fatal(ses); return;
            }
        } else {
            ssize_t n = recv(ses->fd_server,
                             rs->data_buf + rs->data_pos,
                             rs->data_len - rs->data_pos, 0);
            if (n > 0) {
                rs->data_pos += n;
                ses->last_activity_ms = now_mono_ms();
                if (rs->data_pos == rs->data_len) {
                    dispatch_data(ses, rs);
                    rs_reset(rs);
                }
            } else if (n == 0) {
                handle_server_fatal(ses); return;
            } else {
                if (errno == EAGAIN || errno == EWOULDBLOCK) return;
                if (errno == EINTR) continue;
                handle_server_fatal(ses); return;
            }
        }
    }
}

/* ═══════════════════════════════════════════════════════════════════════════
   Nueva conexión local (SYN)
   ═══════════════════════════════════════════════════════════════════════════ */
static void handle_new_connections(Session *ses) {
    while (true) {
        int fd = accept4(ses->fd_listen, NULL, NULL, SOCK_NONBLOCK | SOCK_CLOEXEC);
        if (fd >= 0) {
            if (ses->state != ST_CONNECTED ||
                ses->streams.count >= MAX_STREAMS) {
                close(fd); continue;
            }
            if (ses->next_sid >= 0xFFFFFFFE) {
                if (ses->streams.count == 0)
                    handle_server_fatal(ses);
                else
                    ses->state = ST_DRAINING;
                close(fd); continue;
            }

            uint32_t id = ses->next_sid;
            ses->next_sid += 2;

            Stream *st = stream_alloc(id, fd);
            if (!st) { close(fd); continue; }

            sht_put(&ses->streams, st);
            send_ctrl(ses, T_SYN, id);

            ep_add(ses->fd_epoll, fd,
                   EPOLLIN | EPOLLET, (uint64_t)fd);
            handle_local_readable(ses, st);

        } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
            break;
        } else if (errno == EINTR) {
            continue;
        } else {
            push_log("E", "accept4: %s", strerror(errno));
            break;
        }
    }
}

/* ═══════════════════════════════════════════════════════════════════════════
   Keepalive
   ═══════════════════════════════════════════════════════════════════════════ */
static void check_keepalive(Session *ses) {
    if (ses->state != ST_CONNECTED) return;

    long now  = now_mono_ms();
    long idle = now - ses->last_activity_ms;

    if (idle > KEEPALIVE_INTERVAL_MS) {
        send_ctrl(ses, T_NOP, 0);
        if (ses->ping_sent_at_ms == 0) {
            Frame *f = frame_alloc(T_PING, 0, NULL, 0);
            enqueue_and_flush(ses, f);
            ses->ping_sent_at_ms = now;
        }
        ses->last_activity_ms = now;
    }

    if (idle > KEEPALIVE_TIMEOUT_MS) {
        push_log("E", "keepalive timeout");
        handle_server_fatal(ses);
    }
}

static void check_stream_timeouts(Session *ses) {
    long now = now_mono_ms();
    for (int i = 0; i < SHT_SIZE; i++) {
        Stream *st = ses->streams.buckets[i];
        while (st) {
            Stream *nx = st->ht_next;
            if (now - st->last_activity_ms > STREAM_TIMEOUT_MS) {
                push_log("I", "stream %u timeout", st->id);
                send_ctrl(ses, T_RST, st->id);
                stream_destroy(ses, st);
            }
            st = nx;
        }
    }
}

/* ═══════════════════════════════════════════════════════════════════════════
   Error fatal del servidor — prepara reconexión
   ═══════════════════════════════════════════════════════════════════════════ */
static void handle_server_fatal(Session *ses) {
    if (ses->state == ST_CLOSED) return;

    close_all_streams(ses);

    if (ses->wp) { free(ses->wp); ses->wp = NULL; }
    fq_flush(&ses->wq);
    rs_reset(&ses->rs);

    if (ses->fd_server >= 0) {
        ep_del(ses->fd_epoll, ses->fd_server);
        shutdown(ses->fd_server, SHUT_RDWR);
        close(ses->fd_server);
        ses->fd_server = -1;
    }

    ses->ping_sent_at_ms = 0;
    ses->state           = ST_CLOSED;

    push_log("E", "tunnel caido, reconectando...");
}

/* ═══════════════════════════════════════════════════════════════════════════
   Loop principal de la sesión — epoll event loop
   ═══════════════════════════════════════════════════════════════════════════ */
static void session_run(Session *ses) {
    struct epoll_event events[MAX_EPOLL_EVENTS];

    while (ses->state != ST_CLOSED && g_running) {
        int n = epoll_wait(ses->fd_epoll, events, MAX_EPOLL_EVENTS, EPOLL_TIMEOUT_MS);
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }

        for (int i = 0; i < n && ses->state != ST_CLOSED; i++) {
            int      fd  = (int)(uint32_t)events[i].data.u64;
            uint32_t evs = events[i].events;

            if (fd == ses->fd_wake_r) {
                ses->state = ST_CLOSED;
                break;
            }

            if (fd == ses->fd_listen) {
                handle_new_connections(ses);
                continue;
            }

            if (fd == ses->fd_server) {
                if (evs & EPOLLOUT) handle_server_writable(ses);
                if (evs & EPOLLIN  && ses->state == ST_CONNECTED)
                    handle_server_readable(ses);
                if (evs & (EPOLLHUP | EPOLLERR)) handle_server_fatal(ses);
                continue;
            }

            Stream *st = sht_get_by_fd(&ses->streams, fd);
            if (!st) continue;

            if (evs & (EPOLLHUP | EPOLLERR)) {
                send_ctrl(ses, T_RST, st->id);
                stream_destroy(ses, st);
                continue;
            }
            if (evs & EPOLLOUT) handle_local_writable(ses, st);
            if (evs & EPOLLIN)  handle_local_readable(ses, st);
        }

        check_keepalive(ses);
        check_stream_timeouts(ses);
    }
}

/* ═══════════════════════════════════════════════════════════════════════════
   Hilo principal — reconexión con backoff exponencial
   ═══════════════════════════════════════════════════════════════════════════ */
static void *main_thread(void *arg) {
    int port = (int)(intptr_t)arg;

    signal(SIGPIPE, SIG_IGN);

    uint32_t reconnect_delay = INITIAL_RECONNECT_MS;

    while (g_running) {
        int tfd = open_tunnel();
        if (tfd < 0) {
            if (!g_running) break;
            push_log("I", "reintento en %ums", reconnect_delay);
            usleep(reconnect_delay * 1000u);
            reconnect_delay = reconnect_delay < (uint32_t)MAX_RECONNECT_MS / 2
                ? reconnect_delay * 2 : MAX_RECONNECT_MS;
            continue;
        }
        reconnect_delay = INITIAL_RECONNECT_MS;

        int lfd = make_listen_socket(port);
        if (lfd < 0) {
            push_log("E", "listen bind failed");
            close(tfd); usleep(2000000); continue;
        }

        int epfd = epoll_create1(EPOLL_CLOEXEC);
        if (epfd < 0) { close(lfd); close(tfd); usleep(1000000); continue; }

        pthread_mutex_lock(&g_mu);
        int wr = g_stop_r, ww = g_stop_w;
        pthread_mutex_unlock(&g_mu);

        Session ses = {0};
        ses.fd_server        = tfd;
        ses.fd_listen        = lfd;
        ses.fd_epoll         = epfd;
        ses.fd_wake_r        = wr;
        ses.fd_wake_w        = ww;
        ses.state            = ST_CONNECTED;
        ses.next_sid         = 0;
        ses.last_activity_ms = now_mono_ms();
        ses.connected_at_ms  = now_mono_ms();
        ses.ping_sent_at_ms  = 0;
        ses.reconnect_delay_ms = reconnect_delay;
        fq_init(&ses.wq);
        ses.wp = NULL;
        rs_reset(&ses.rs);
        sht_init(&ses.streams);

        ep_add(epfd, tfd, EPOLLIN | EPOLLET, (uint64_t)tfd);
        ep_add(epfd, lfd, EPOLLIN | EPOLLET, (uint64_t)lfd);
        ep_add(epfd, wr,  EPOLLIN,            (uint64_t)wr);

        push_log("I", "relay listo port=%d", port);
        session_run(&ses);

        close_all_streams(&ses);
        if (ses.wp) free(ses.wp);
        fq_flush(&ses.wq);
        rs_reset(&ses.rs);

        close(epfd);
        close(lfd);
        shutdown(tfd, SHUT_RDWR); close(tfd);

        if (!g_running) break;

        push_log("I", "reintento en %ums", reconnect_delay);
        usleep(reconnect_delay * 1000u);
        reconnect_delay = reconnect_delay < (uint32_t)MAX_RECONNECT_MS / 2
            ? reconnect_delay * 2 : MAX_RECONNECT_MS;
    }

    return NULL;
}

/* ═══════════════════════════════════════════════════════════════════════════
   JNI
   ═══════════════════════════════════════════════════════════════════════════ */
JNIEXPORT void JNICALL Java_com_blacktunnel_BtProxy_nativeStop(JNIEnv *, jclass);

JNIEXPORT jint JNICALL
Java_com_blacktunnel_BtProxy_nativeStart(JNIEnv *env, jclass clazz,
                                          jint port, jobject svc, jstring iid) {
    (void)clazz;
    pthread_mutex_lock(&g_mu);
    if (g_running) { pthread_mutex_unlock(&g_mu); return 0; }
    pthread_t old = g_main_thread;
    pthread_mutex_unlock(&g_mu);

    if (old != 0) {
        pthread_join(old, NULL);
        pthread_mutex_lock(&g_mu); g_main_thread = 0; pthread_mutex_unlock(&g_mu);
    }

    int wfds[2] = {-1, -1};
    if (pipe(wfds) < 0) return -1;
    fcntl(wfds[0], F_SETFL, O_NONBLOCK); fcntl(wfds[1], F_SETFL, O_NONBLOCK);
    fcntl(wfds[0], F_SETFD, FD_CLOEXEC); fcntl(wfds[1], F_SETFD, FD_CLOEXEC);

    pthread_mutex_lock(&g_mu);
    (*env)->GetJavaVM(env, &g_jvm);
    g_svc = (*env)->NewGlobalRef(env, svc);
    g_internal_id[0] = 0;
    if (iid) {
        const char *s = (*env)->GetStringUTFChars(env, iid, NULL);
        if (s) { snprintf(g_internal_id, sizeof(g_internal_id), "%s", s);
                 (*env)->ReleaseStringUTFChars(env, iid, s); }
    }
    g_stop_r = wfds[0];
    g_stop_w = wfds[1];
    g_running = 1;
    pthread_mutex_unlock(&g_mu);

    pthread_t thr;
    if (pthread_create(&thr, NULL, main_thread, (void *)(intptr_t)port) != 0) {
        pthread_mutex_lock(&g_mu);
        g_running = 0;
        close(wfds[0]); close(wfds[1]);
        g_stop_r = g_stop_w = -1;
        (*env)->DeleteGlobalRef(env, g_svc); g_svc = NULL; g_jvm = NULL;
        pthread_mutex_unlock(&g_mu);
        return -1;
    }
    pthread_mutex_lock(&g_mu); g_main_thread = thr; pthread_mutex_unlock(&g_mu);
    pthread_detach(thr);

    push_log("I", "nativeStart lanzado");
    return 0;
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeStop(JNIEnv *env, jclass clazz) {
    (void)clazz;
    pthread_mutex_lock(&g_mu);
    if (!g_running) { pthread_mutex_unlock(&g_mu); return; }
    g_running = 0;
    g_internal_id[0] = 0;
    jobject svc = g_svc; g_svc = NULL; g_jvm = NULL;
    int ww = g_stop_w; g_stop_w = -1;
    int wr = g_stop_r; g_stop_r = -1;
    pthread_mutex_unlock(&g_mu);

    if (ww >= 0) { uint8_t b = 1; write(ww, &b, 1); close(ww); }
    if (wr >= 0) close(wr);
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

static JNINativeMethod g_methods[] = {
    {"nativeStart",      "(ILandroid/net/VpnService;Ljava/lang/String;)I",
                         (void *)Java_com_blacktunnel_BtProxy_nativeStart},
    {"nativeStop",       "()V",
                         (void *)Java_com_blacktunnel_BtProxy_nativeStop},
    {"nativeDrainLogs",  "()Ljava/lang/String;",
                         (void *)Java_com_blacktunnel_BtProxy_nativeDrainLogs},
    {"nativeSetNetwork", "(J)V",
                         (void *)Java_com_blacktunnel_BtProxy_nativeSetNetwork},
};

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *r) {
    (void)r;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = (*env)->FindClass(env, "com/blacktunnel/BtProxy");
    if (!cls) return JNI_ERR;
    if ((*env)->RegisterNatives(env, cls, g_methods,
            sizeof(g_methods)/sizeof(g_methods[0])) < 0) return JNI_ERR;
    (*env)->DeleteLocalRef(env, cls);
    return JNI_VERSION_1_6;
}
