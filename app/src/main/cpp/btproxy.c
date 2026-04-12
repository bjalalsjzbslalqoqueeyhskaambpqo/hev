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

#define LOG_TAG "btproxy"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define T_DATA  0x02
#define T_CLOSE 0x03
#define T_PING  0x04
#define T_PONG  0x05
#define T_OPEN  0x01

#define FRAME_HDR     7
#define MAX_PAYLOAD   32768
#define MAX_STREAMS   2048
#define RELAY_BACKLOG 512
#define IDLE_SECS     600
#define WD_INTERVAL   60
#define KEEPALIVE_SEC 90

#define PROXY_HOST_IPV6 "2606:4700::6812:16b7"
#define PROXY_HOST      "emailmarketing.personal.com.ar"
#define PROXY_PORT      80
#define TUNNEL_HOST     "2.brawlpass.com.ar"

/* ── stream table ─────────────────────────────────────────────────────── */

typedef struct {
    int      fd;
    int64_t  last_active;
    uint32_t sid;
    uint8_t  used;
} stream_t;

static stream_t        g_streams[MAX_STREAMS];
static pthread_mutex_t g_streams_mu = PTHREAD_MUTEX_INITIALIZER;

static stream_t *stream_find(uint32_t sid) {
    for (int i = 0; i < MAX_STREAMS; i++)
        if (g_streams[i].used && g_streams[i].sid == sid)
            return &g_streams[i];
    return NULL;
}

static stream_t *stream_alloc(uint32_t sid, int fd) {
    for (int i = 0; i < MAX_STREAMS; i++) {
        if (!g_streams[i].used) {
            g_streams[i] = (stream_t){ .sid = sid, .fd = fd,
                                       .last_active = (int64_t)time(NULL),
                                       .used = 1 };
            return &g_streams[i];
        }
    }
    return NULL;
}

static void stream_free(stream_t *s) {
    if (!s) return;
    if (s->fd >= 0) { close(s->fd); s->fd = -1; }
    s->used = 0;
}

/* ── globals ──────────────────────────────────────────────────────────── */

static volatile int    g_running  = 0;
static volatile int    g_started  = 0;
static int             g_relay_fd = -1;
static int             g_tun_fd   = -1;
static atomic_int      g_next_sid = 1;
static pthread_t       g_main_thr;
static JavaVM         *g_jvm      = NULL;
static jobject         g_vpn_svc  = NULL;
static pthread_mutex_t g_state_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_tun_wmu  = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_log_mu   = PTHREAD_MUTEX_INITIALIZER;
static char            g_log_buf[32768];
static size_t          g_log_len  = 0;
static pthread_mutex_t g_start_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_start_cv = PTHREAD_COND_INITIALIZER;
static int             g_start_st = 0;

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeStop(JNIEnv *env, jclass clazz);

/* ── logging ──────────────────────────────────────────────────────────── */

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

static void request_shutdown(const char *reason) {
    int rfd = -1, tfd = -1;
    pthread_mutex_lock(&g_state_mu);
    if (!g_running) { pthread_mutex_unlock(&g_state_mu); return; }
    g_running = 0;
    rfd = g_relay_fd; g_relay_fd = -1;
    tfd = g_tun_fd;   g_tun_fd   = -1;
    pthread_mutex_unlock(&g_state_mu);
    if (reason) push_log("E", "shutdown: %s", reason);
    if (rfd >= 0) close(rfd);
    if (tfd >= 0) close(tfd);
    pthread_mutex_lock(&g_start_mu);
    if (g_start_st == 0) { g_start_st = -1; pthread_cond_broadcast(&g_start_cv); }
    pthread_mutex_unlock(&g_start_mu);
}

/* ── socket helpers ───────────────────────────────────────────────────── */

static void sock_tune(int fd) {
    int v;
    v = 1;      setsockopt(fd, IPPROTO_TCP, TCP_NODELAY,  &v, sizeof(v));
    v = 131072; setsockopt(fd, SOL_SOCKET,  SO_RCVBUF,    &v, sizeof(v));
    v = 131072; setsockopt(fd, SOL_SOCKET,  SO_SNDBUF,    &v, sizeof(v));
}

static void sock_keepalive(int fd) {
    int one=1, idle=30, intvl=5, cnt=3;
    setsockopt(fd, SOL_SOCKET,  SO_KEEPALIVE,  &one,   sizeof(one));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE,  &idle,  sizeof(idle));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &intvl, sizeof(intvl));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT,   &cnt,   sizeof(cnt));
}

/* ── framed write to tunnel ───────────────────────────────────────────── */

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
    pthread_mutex_lock(&g_tun_wmu);
    while (sent < total) {
        ssize_t n = writev(tfd, iov, niov);
        if (n > 0) {
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
        } else if (errno == EINTR || errno == EAGAIN) {
            continue;
        } else {
            pthread_mutex_unlock(&g_tun_wmu);
            return -1;
        }
    }
    pthread_mutex_unlock(&g_tun_wmu);
    return 0;
}

/* ── exact read from tunnel ───────────────────────────────────────────── */

static int tun_recv(int fd, uint8_t *buf, int len) {
    int off = 0;
    while (off < len) {
        ssize_t n = recv(fd, buf + off, len - off, 0);
        if (n > 0)           { off += (int)n; }
        else if (n == 0)     { return -1; }
        else if (errno == EINTR) { continue; }
        else                 { return -1; }
    }
    return 0;
}

/* ── JNI protect ──────────────────────────────────────────────────────── */

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

/* ── HTTP tunnel handshake ────────────────────────────────────────────── */

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

static int open_tunnel(void) {
    int fd = -1;

    struct sockaddr_in6 a6 = {0};
    a6.sin6_family = AF_INET6;
    a6.sin6_port   = htons(PROXY_PORT);
    if (inet_pton(AF_INET6, PROXY_HOST_IPV6, &a6.sin6_addr) == 1) {
        fd = socket(AF_INET6, SOCK_STREAM, 0);
        if (fd >= 0) {
            jni_protect(fd); sock_tune(fd); sock_keepalive(fd);
            if (connect(fd, (struct sockaddr*)&a6, sizeof(a6)) != 0)
                { close(fd); fd = -1; }
            else push_log("I", "tunnel IPv6 OK");
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
                if (connect(s, r->ai_addr, r->ai_addrlen) == 0)
                    { fd = s; push_log("I", "tunnel DNS OK"); }
                else close(s);
            }
            freeaddrinfo(res);
        }
    }

    if (fd < 0) { push_log("E", "tunnel connect failed"); return -1; }

    char req1[256], h1[2048];
    int r1 = snprintf(req1, sizeof(req1),
        "GET / HTTP/1.1\r\nHost: %s\r\n\r\n", PROXY_HOST);
    send(fd, req1, r1, MSG_NOSIGNAL);
    if (recv_until_eoh(fd, h1, sizeof(h1), 8) < 0) { close(fd); return -1; }

    usleep(5000);
    char req2[256], h2[4096];
    int r2 = snprintf(req2, sizeof(req2),
        "- / HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\n"
        "Connection: Upgrade\r\naction: tunnel\r\n\r\n", TUNNEL_HOST);
    send(fd, req2, r2, MSG_NOSIGNAL);
    if (recv_until_eoh(fd, h2, sizeof(h2), 8) < 0 || !strstr(h2, "101"))
        { close(fd); return -1; }

    push_log("I", "tunnel handshake OK");
    return fd;
}

/* ── per-connection thread ────────────────────────────────────────────── */
/*
 * Recibe datos crudos de hev (stream SOCKS5 completo sin parsear)
 * y los reenvía al servidor etiquetados con T_OPEN + T_DATA.
 * El servidor los pasa directamente a hev-socks5-server que entiende
 * el protocolo SOCKS5 nativo, incluyendo pipeline, UDP-in-TCP, etc.
 */

typedef struct { int cfd; int tfd; uint32_t sid; } conn_args_t;

static void *conn_thread(void *arg) {
    conn_args_t *ca = (conn_args_t*)arg;
    int cfd = ca->cfd, tfd = ca->tfd;
    uint32_t sid = ca->sid;
    free(ca);

    pthread_mutex_lock(&g_streams_mu);
    stream_t *s = stream_alloc(sid, cfd);
    pthread_mutex_unlock(&g_streams_mu);
    if (!s) { close(cfd); return NULL; }

    /* Primer recv: lo que hev manda al conectar (greeting SOCKS5 o más) */
    uint8_t buf[MAX_PAYLOAD];
    ssize_t first = recv(cfd, buf, sizeof(buf), 0);
    if (first <= 0) {
        pthread_mutex_lock(&g_streams_mu); stream_free(s);
        pthread_mutex_unlock(&g_streams_mu);
        return NULL;
    }

    /* T_OPEN lleva el primer bloque de datos — el servidor abre la conexión
     * a hev-socks5-server y le pasa estos bytes directamente */
    if (tun_send(tfd, T_OPEN, sid, buf, (uint16_t)first) < 0) {
        request_shutdown("tun_send T_OPEN failed");
        pthread_mutex_lock(&g_streams_mu); stream_free(s);
        pthread_mutex_unlock(&g_streams_mu);
        return NULL;
    }

    /* Seguir reenviando todo lo que llegue sin inspección */
    while (g_running) {
        ssize_t n = recv(cfd, buf, sizeof(buf), 0);
        if (n <= 0) break;
        pthread_mutex_lock(&g_streams_mu);
        if (s->used) s->last_active = (int64_t)time(NULL);
        pthread_mutex_unlock(&g_streams_mu);
        if (tun_send(tfd, T_DATA, sid, buf, (uint16_t)n) < 0) {
            request_shutdown("tun_send T_DATA failed");
            break;
        }
    }

    tun_send(tfd, T_CLOSE, sid, NULL, 0);
    pthread_mutex_lock(&g_streams_mu); stream_free(s);
    pthread_mutex_unlock(&g_streams_mu);
    return NULL;
}

/* ── tunnel reader thread ─────────────────────────────────────────────── */
/*
 * Lee frames del servidor y los entrega al socket local de hev.
 * MSG_DONTWAIT en el send: si el buffer local está lleno cerramos
 * esa stream sin bloquear las demás.
 */

static void *tunnel_reader(void *arg) {
    int tfd = *(int*)arg;
    uint8_t hdr[FRAME_HDR], payload[MAX_PAYLOAD];

    while (g_running) {
        if (tun_recv(tfd, hdr, FRAME_HDR) < 0) { request_shutdown("tunnel header read failed"); break; }

        uint8_t  ft  = hdr[0];
        uint32_t sid = ((uint32_t)hdr[1] << 24) | ((uint32_t)hdr[2] << 16) |
                       ((uint32_t)hdr[3] <<  8) |  (uint32_t)hdr[4];
        uint16_t len = ((uint16_t)hdr[5] << 8) | hdr[6];

        if (len > MAX_PAYLOAD) { request_shutdown("tunnel payload too large"); break; }
        if (len > 0 && tun_recv(tfd, payload, len) < 0) { request_shutdown("tunnel payload read failed"); break; }

        switch (ft) {
        case T_DATA: {
            pthread_mutex_lock(&g_streams_mu);
            stream_t *s = stream_find(sid);
            int cfd = s ? s->fd : -1;
            if (s && s->used) s->last_active = (int64_t)time(NULL);
            pthread_mutex_unlock(&g_streams_mu);
            if (cfd < 0 || len == 0) break;
            ssize_t off = 0;
            while (off < len) {
                ssize_t n = send(cfd, payload + off, len - off,
                                 MSG_NOSIGNAL | MSG_DONTWAIT);
                if (n > 0) { off += n; continue; }
                if (errno == EINTR) continue;
                /* Buffer lleno o error: cerrar stream, no bloquear reader */
                pthread_mutex_lock(&g_streams_mu);
                stream_t *sc = stream_find(sid);
                if (sc) stream_free(sc);
                pthread_mutex_unlock(&g_streams_mu);
                tun_send(tfd, T_CLOSE, sid, NULL, 0);
                break;
            }
            break;
        }
        case T_CLOSE: {
            pthread_mutex_lock(&g_streams_mu);
            stream_t *s = stream_find(sid);
            if (s) stream_free(s);
            pthread_mutex_unlock(&g_streams_mu);
            break;
        }
        case T_PING:
            tun_send(tfd, T_PONG, 0, NULL, 0);
            break;
        case T_PONG:
            break;
        default:
            /* Frame desconocido: payload ya consumido, seguir */
            break;
        }
    }
    return NULL;
}

/* ── keepalive & watchdog ─────────────────────────────────────────────── */

static void *keepalive_thread(void *arg) {
    int tfd = *(int*)arg;
    while (g_running) {
        sleep(KEEPALIVE_SEC);
        if (!g_running) break;
        if (tun_send(tfd, T_PING, 0, NULL, 0) < 0) {
            request_shutdown("keepalive ping failed");
            break;
        }
    }
    return NULL;
}

static void *watchdog_thread(void *arg) {
    int tfd = *(int*)arg;
    while (g_running) {
        sleep(WD_INTERVAL);
        if (!g_running) break;
        int64_t now = (int64_t)time(NULL);
        pthread_mutex_lock(&g_streams_mu);
        for (int i = 0; i < MAX_STREAMS; i++) {
            if (!g_streams[i].used) continue;
            if (now - g_streams[i].last_active > IDLE_SECS) {
                uint32_t dsid = g_streams[i].sid;
                stream_free(&g_streams[i]);
                pthread_mutex_unlock(&g_streams_mu);
                tun_send(tfd, T_CLOSE, dsid, NULL, 0);
                pthread_mutex_lock(&g_streams_mu);
            }
        }
        pthread_mutex_unlock(&g_streams_mu);
    }
    return NULL;
}

/* ── main thread ──────────────────────────────────────────────────────── */

static void *main_thread(void *arg) {
    int port = (int)(intptr_t)arg;

    int tfd = open_tunnel();
    if (tfd < 0) {
        pthread_mutex_lock(&g_start_mu); g_start_st = -1;
        pthread_cond_broadcast(&g_start_cv); pthread_mutex_unlock(&g_start_mu);
        return NULL;
    }
    g_tun_fd = tfd;

    int rfd = socket(AF_INET, SOCK_STREAM, 0);
    if (rfd < 0) { close(tfd); return NULL; }
    int one = 1; setsockopt(rfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    int v = 1; setsockopt(rfd, IPPROTO_TCP, TCP_NODELAY, &v, sizeof(v));
    struct sockaddr_in la = {0};
    la.sin_family = AF_INET; la.sin_port = htons(port);
    la.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(rfd, (struct sockaddr*)&la, sizeof(la)) < 0 ||
        listen(rfd, RELAY_BACKLOG) < 0) {
        pthread_mutex_lock(&g_start_mu); g_start_st = -1;
        pthread_cond_broadcast(&g_start_cv); pthread_mutex_unlock(&g_start_mu);
        close(rfd); close(tfd); return NULL;
    }

    g_relay_fd = rfd; g_started = 1;
    push_log("I", "relay listening on 127.0.0.1:%d", port);
    pthread_mutex_lock(&g_start_mu); g_start_st = 1;
    pthread_cond_broadcast(&g_start_cv); pthread_mutex_unlock(&g_start_mu);

    pthread_t trd, tka, twd;
    pthread_create(&trd, NULL, tunnel_reader,    &g_tun_fd);
    pthread_create(&tka, NULL, keepalive_thread, &g_tun_fd);
    pthread_create(&twd, NULL, watchdog_thread,  &g_tun_fd);
    pthread_detach(trd); pthread_detach(tka); pthread_detach(twd);

    while (g_running) {
        struct sockaddr_in ca; socklen_t cl = sizeof(ca);
        int cfd = accept(rfd, (struct sockaddr*)&ca, &cl);
        if (cfd < 0) {
            if (!g_running) break;
            if (errno == EINTR || errno == EAGAIN) continue;
            request_shutdown("accept failed");
            break;
        }
        sock_tune(cfd);

        uint32_t sid;
        do { sid = (uint32_t)atomic_fetch_add(&g_next_sid, 1) & 0x7FFFFFFF; }
        while (sid == 0);

        conn_args_t *ca2 = malloc(sizeof(conn_args_t));
        if (!ca2) { close(cfd); continue; }
        ca2->cfd = cfd; ca2->tfd = tfd; ca2->sid = sid;

        pthread_t ct;
        if (pthread_create(&ct, NULL, conn_thread, ca2) != 0)
            { free(ca2); close(cfd); }
        else pthread_detach(ct);
    }

    close(rfd); g_relay_fd = -1;
    close(tfd); g_tun_fd   = -1;
    g_started = 0;
    return NULL;
}

/* ── JNI exports ──────────────────────────────────────────────────────── */

JNIEXPORT jint JNICALL
Java_com_blacktunnel_BtProxy_nativeStart(JNIEnv *env, jclass clazz,
                                          jint port, jobject svc) {
    pthread_mutex_lock(&g_state_mu);
    if (g_running) { pthread_mutex_unlock(&g_state_mu); return 0; }
    (*env)->GetJavaVM(env, &g_jvm);
    g_vpn_svc = (*env)->NewGlobalRef(env, svc);
    memset(g_streams, 0, sizeof(g_streams));
    for (int i = 0; i < MAX_STREAMS; i++) g_streams[i].fd = -1;
    g_running = 1; g_started = 0;
    atomic_store(&g_next_sid, 1);
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

    struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts); ts.tv_sec += 10;
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
    jobject svc = g_vpn_svc; g_vpn_svc = NULL; g_jvm = NULL;
    pthread_mutex_unlock(&g_state_mu);
    if (g_relay_fd >= 0) { close(g_relay_fd); g_relay_fd = -1; }
    if (g_tun_fd   >= 0) { close(g_tun_fd);   g_tun_fd   = -1; }
    pthread_mutex_lock(&g_start_mu);
    if (g_start_st == 0) { g_start_st = -1; pthread_cond_broadcast(&g_start_cv); }
    pthread_mutex_unlock(&g_start_mu);
    for (int i = 0; i < 10 && g_started; i++) usleep(10000);
    pthread_mutex_lock(&g_streams_mu);
    for (int i = 0; i < MAX_STREAMS; i++)
        if (g_streams[i].used) stream_free(&g_streams[i]);
    pthread_mutex_unlock(&g_streams_mu);
    if (svc) (*env)->DeleteGlobalRef(env, svc);
    g_started = 0;
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
