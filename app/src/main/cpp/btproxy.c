#include <jni.h>
#include <android/log.h>
#include <android/multinetwork.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
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

#define T_OPEN  0x01
#define T_DATA  0x02
#define T_CLOSE 0x03
#define T_PING  0x04
#define T_PONG  0x05

#define FRAME_HDR              7
#define MAX_PAYLOAD            16384
#define MAX_SESS               1024
#define BUF                    8500
#define STACK                  65536
#define KEEPALIVE_INTERVAL_SEC 10
#define PONG_TIMEOUT_SEC       120
#define CONNECT_TIMEOUT_SEC    15
#define HANDSHAKE_TIMEOUT_SEC  4

#define PROXY_HOST  "emailmarketing.personal.com.ar"
#define PROXY_PORT  80
#define TUNNEL_HOST "2.brawlpass.com.ar"
#define PROXY_IP    "2606:4700::6812:16b7"

#define READY_PENDING  0
#define READY_OK       1
#define READY_FAIL    -1
#define READY_AUTH    -2

static volatile int    g_running      = 0;
static int             g_tfd          = -1;
static int             g_lfd          = -1;
static atomic_uint     g_sid          = 1;
static atomic_long     g_last_pong    = 0;
static atomic_int      g_tunnel_epoch = 0;
static char            g_iid[160]     = {0};
static JavaVM         *g_jvm          = NULL;
static jobject         g_svc          = NULL;
static net_handle_t    g_net          = NETWORK_UNSPECIFIED;
static pthread_t       g_main_thread  = 0;
static pthread_mutex_t g_mu           = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_wmu          = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_log_mu       = PTHREAD_MUTEX_INITIALIZER;
static char            g_logbuf[32768];
static size_t          g_loglen       = 0;
static pthread_mutex_t g_ready_mu     = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_ready_cv     = PTHREAD_COND_INITIALIZER;
static int             g_ready_st     = READY_PENDING;

typedef struct { uint32_t sid; int cfd; atomic_int on; } slot_t;
static slot_t          g_slots[MAX_SESS];
static pthread_mutex_t g_smtx = PTHREAD_MUTEX_INITIALIZER;

static slot_t *slot_alloc(uint32_t sid, int cfd) {
    pthread_mutex_lock(&g_smtx);
    for (int i = 0; i < MAX_SESS; i++) {
        if (!atomic_load(&g_slots[i].on)) {
            g_slots[i].sid = sid; g_slots[i].cfd = cfd;
            atomic_store(&g_slots[i].on, 1);
            pthread_mutex_unlock(&g_smtx);
            return &g_slots[i];
        }
    }
    pthread_mutex_unlock(&g_smtx);
    return NULL;
}

static int slot_cfd(uint32_t sid) {
    for (int i = 0; i < MAX_SESS; i++)
        if (atomic_load(&g_slots[i].on) && g_slots[i].sid == sid)
            return g_slots[i].cfd;
    return -1;
}

static void slot_free(uint32_t sid) {
    for (int i = 0; i < MAX_SESS; i++)
        if (atomic_load(&g_slots[i].on) && g_slots[i].sid == sid) {
            atomic_store(&g_slots[i].on, 0); return;
        }
}

static void slot_close_all(void) {
    pthread_mutex_lock(&g_smtx);
    for (int i = 0; i < MAX_SESS; i++)
        if (atomic_load(&g_slots[i].on)) {
            shutdown(g_slots[i].cfd, SHUT_RDWR);
            close(g_slots[i].cfd);
            atomic_store(&g_slots[i].on, 0);
        }
    pthread_mutex_unlock(&g_smtx);
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
        g_loglen += n; g_logbuf[g_loglen] = 0;
    }
    pthread_mutex_unlock(&g_log_mu);
}

static void protect_fd(int fd) {
    pthread_mutex_lock(&g_mu);
    net_handle_t net = g_net; JavaVM *jvm = g_jvm; jobject svc = g_svc;
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

static int rd(int fd, void *b, size_t n) {
    uint8_t *p = b;
    while (n) { ssize_t r = read(fd, p, n); if (r <= 0) return -1; p += r; n -= r; }
    return 0;
}

static int tsend(int tfd, uint8_t type, uint32_t sid, const void *data, uint16_t dlen) {
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
                    if (skip >= iov[i].iov_len) { skip -= iov[i].iov_len; iov[i].iov_len = 0; }
                    else { iov[i].iov_base = (uint8_t *)iov[i].iov_base + skip; iov[i].iov_len -= skip; skip = 0; }
                }
            }
        } else if (errno == EINTR) { continue; }
        else if (errno == EAGAIN || errno == EWOULDBLOCK) {
            struct pollfd wp = {tfd, POLLOUT, 0};
            if (poll(&wp, 1, 500) <= 0) return -1;
        } else { return -1; }
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

static int open_tunnel(int *fd_out) {
    struct sockaddr_in6 a = {0};
    a.sin6_family = AF_INET6; a.sin6_port = htons(PROXY_PORT);
    if (inet_pton(AF_INET6, PROXY_IP, &a.sin6_addr) != 1) return -1;

    int fd = socket(AF_INET6, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    protect_fd(fd); tune_tun(fd);
    int fl = fcntl(fd, F_GETFL, 0); fcntl(fd, F_SETFL, fl | O_NONBLOCK);
    int r = connect(fd, (struct sockaddr *)&a, sizeof(a));
    if (r < 0 && errno != EINPROGRESS) { close(fd); return -1; }
    struct pollfd p = {fd, POLLOUT, 0};
    if (poll(&p, 1, CONNECT_TIMEOUT_SEC * 1000) <= 0) { close(fd); return -1; }
    int e = 0; socklen_t el = sizeof(e);
    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &e, &el) < 0 || e != 0) { close(fd); return -1; }
    fcntl(fd, F_SETFL, fl);

    atomic_store(&g_last_pong, (long)time(NULL));

    char buf[2048];
    snprintf(buf, sizeof(buf), "GET / HTTP/1.1\r\nHost: %s\r\n\r\n", PROXY_HOST);
    send(fd, buf, strlen(buf), MSG_NOSIGNAL);
    if (recv_eoh(fd, buf, sizeof(buf), HANDSHAKE_TIMEOUT_SEC) < 0) {
        push_log("E", "preflight sin respuesta"); close(fd); return -1;
    }

    char buf2[4096];
    snprintf(buf2, sizeof(buf2),
        "- / HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\n"
        "Connection: Upgrade\r\nAction: tunnel\r\nX-Internal-ID: %s\r\n\r\n",
        TUNNEL_HOST, g_iid[0] ? g_iid : "unknown");
    send(fd, buf2, strlen(buf2), MSG_NOSIGNAL);

    char h2[4096];
    int hlen = recv_eoh(fd, h2, sizeof(h2), HANDSHAKE_TIMEOUT_SEC);
    int code = -1; sscanf(h2, "HTTP/%*d.%*d %d", &code);

    if (hlen < 0 || code != 101) {
        push_log("E", "handshake code=%d", code);
        if (code == 403) {
            char extra[256] = {0}; recv(fd, extra, sizeof(extra) - 1, MSG_DONTWAIT);
            if (strstr(h2, "expired") || strstr(extra, "expired"))
                { close(fd); return -3; }
            close(fd); return -2;
        }
        close(fd); return -1;
    }

    push_log("I", "tunnel ok");
    *fd_out = fd;
    return 0;
}

typedef struct { int tfd; int epoch; } thr_t;

static void *trd(void *arg) {
    thr_t *ta = (thr_t *)arg; int tfd = ta->tfd; int epoch = ta->epoch; free(ta);
    uint8_t hdr[FRAME_HDR];
    uint8_t *b = malloc(BUF);
    if (!b) return NULL;

    while (g_running && atomic_load(&g_tunnel_epoch) == epoch) {
        if (rd(tfd, hdr, FRAME_HDR) < 0) break;

        uint8_t  ft  = hdr[0];
        uint32_t sid = ((uint32_t)hdr[1] << 24) | ((uint32_t)hdr[2] << 16) |
                       ((uint32_t)hdr[3] <<  8) |  (uint32_t)hdr[4];
        uint16_t len = ((uint16_t)hdr[5] << 8) | hdr[6];

        if (len > MAX_PAYLOAD) break;
        if (len > 0 && rd(tfd, b, len) < 0) break;

        switch (ft) {
        case T_DATA: {
            int cfd = slot_cfd(sid);
            if (cfd >= 0 && len > 0) send(cfd, b, len, MSG_NOSIGNAL);
            break;
        }
        case T_CLOSE: {
            int cfd = slot_cfd(sid);
            if (cfd >= 0) { slot_free(sid); shutdown(cfd, SHUT_WR); close(cfd); }
            break;
        }
        case T_PING: tsend(tfd, T_PONG, 0, NULL, 0); break;
        case T_PONG: atomic_store(&g_last_pong, (long)time(NULL)); break;
        }
    }

    free(b);
    slot_close_all();
    return NULL;
}

static void *keepalive(void *arg) {
    thr_t *ta = (thr_t *)arg; int tfd = ta->tfd; int epoch = ta->epoch; free(ta);
    atomic_store(&g_last_pong, (long)time(NULL));
    long last = time(NULL);
    while (g_running && atomic_load(&g_tunnel_epoch) == epoch) {
        sleep(1);
        long now = time(NULL);
        if (now - atomic_load(&g_last_pong) > PONG_TIMEOUT_SEC) {
            push_log("E", "pong timeout"); break;
        }
        if (now - last < KEEPALIVE_INTERVAL_SEC) continue;
        last = now;
        if (tsend(tfd, T_PING, 0, NULL, 0) < 0) break;
        push_log("I", "ping sent");
    }
    return NULL;
}

typedef struct { int cfd; uint32_t sid; int tfd; } sess_t;

static void *up(void *arg) {
    sess_t  *s   = arg;
    int      cfd = s->cfd;
    uint32_t sid = s->sid;
    int      tfd = s->tfd;
    free(s);

    uint8_t *b = malloc(BUF);
    if (b) {
        ssize_t n;
        while ((n = read(cfd, b, BUF)) > 0)
            if (tsend(tfd, T_DATA, sid, b, (uint16_t)n) < 0) break;
        free(b);
    }
    tsend(tfd, T_CLOSE, sid, NULL, 0);
    shutdown(cfd, SHUT_RD);
    slot_free(sid);
    return NULL;
}

static void signal_ready(int st) {
    pthread_mutex_lock(&g_ready_mu);
    if (g_ready_st == READY_PENDING) { g_ready_st = st; pthread_cond_broadcast(&g_ready_cv); }
    pthread_mutex_unlock(&g_ready_mu);
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

static void *main_thread(void *arg) {
    int port = (int)(intptr_t)arg;
    int tfd = -1;
    int rc = open_tunnel(&tfd);

    if (rc != 0) {
        if (rc == -2 || rc == -3) {
            notify_auth_error(rc == -3);
            signal_ready(READY_AUTH);
        } else {
            signal_ready(READY_FAIL);
        }
        return NULL;
    }

    int lfd = socket(AF_INET, SOCK_STREAM, 0);
    int one = 1;
    setsockopt(lfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    setsockopt(lfd, SOL_SOCKET, SO_REUSEPORT, &one, sizeof(one));
    struct sockaddr_in la = {0};
    la.sin_family = AF_INET; la.sin_port = htons((uint16_t)port);
    la.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(lfd, (struct sockaddr *)&la, sizeof(la)) < 0 || listen(lfd, 512) < 0) {
        push_log("E", "bind failed"); close(lfd); close(tfd); signal_ready(READY_FAIL); return NULL;
    }
    fcntl(lfd, F_SETFL, fcntl(lfd, F_GETFL, 0) | O_NONBLOCK);
    fcntl(lfd, F_SETFD, FD_CLOEXEC);

    int epoch = atomic_fetch_add(&g_tunnel_epoch, 1) + 1;

    pthread_mutex_lock(&g_mu);
    g_tfd = tfd; g_lfd = lfd;
    pthread_mutex_unlock(&g_mu);

    thr_t *ta = malloc(sizeof(*ta)); ta->tfd = tfd; ta->epoch = epoch;
    thr_t *tb = malloc(sizeof(*tb)); tb->tfd = tfd; tb->epoch = epoch;
    pthread_t tr, tk;
    pthread_create(&tr, NULL, trd,       ta); pthread_detach(tr);
    pthread_create(&tk, NULL, keepalive, tb); pthread_detach(tk);

    push_log("I", "relay port=%d", port);
    signal_ready(READY_OK);

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_attr_setstacksize(&attr, STACK);

    while (g_running && atomic_load(&g_tunnel_epoch) == epoch) {
        struct pollfd pf = {lfd, POLLIN, 0};
        if (poll(&pf, 1, 1000) <= 0) continue;
        struct sockaddr_in ca; socklen_t cl = sizeof(ca);
        int cfd = accept(lfd, (struct sockaddr *)&ca, &cl);
        if (cfd < 0) continue;
        setsockopt(cfd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
        fcntl(cfd, F_SETFD, FD_CLOEXEC);

        uint32_t sid;
        do { sid = atomic_fetch_add(&g_sid, 1) & 0x7FFFFFFF; } while (!sid);

        if (!slot_alloc(sid, cfd)) { close(cfd); continue; }

        if (tsend(tfd, T_OPEN, sid, NULL, 0) < 0) {
            slot_free(sid); close(cfd); break;
        }

        sess_t *s = malloc(sizeof(*s));
        if (!s) { slot_free(sid); close(cfd); continue; }
        s->cfd = cfd; s->sid = sid; s->tfd = tfd;

        pthread_t tid;
        if (pthread_create(&tid, &attr, up, s) != 0) {
            free(s); slot_free(sid); close(cfd);
        }
    }

    push_log("E", "tunnel dropped epoch=%d", epoch);
    atomic_fetch_add(&g_tunnel_epoch, 1);
    slot_close_all();
    pthread_mutex_lock(&g_mu); g_tfd = -1; g_lfd = -1; pthread_mutex_unlock(&g_mu);
    shutdown(tfd, SHUT_RDWR); close(tfd);
    close(lfd);
    if (g_running) { g_running = 0; }
    return NULL;
}

JNIEXPORT void JNICALL Java_com_blacktunnel_BtProxy_nativeStop(JNIEnv *, jclass);

JNIEXPORT jint JNICALL
Java_com_blacktunnel_BtProxy_nativeStart(JNIEnv *env, jclass cls,
                                          jint port, jobject svc, jstring iid) {
    pthread_mutex_lock(&g_mu);
    if (g_running) { pthread_mutex_unlock(&g_mu); return 0; }
    pthread_t old = g_main_thread;
    pthread_mutex_unlock(&g_mu);

    if (old != 0) { pthread_join(old, NULL); pthread_mutex_lock(&g_mu); g_main_thread = 0; pthread_mutex_unlock(&g_mu); }

    pthread_mutex_lock(&g_mu);
    (*env)->GetJavaVM(env, &g_jvm);
    g_svc = (*env)->NewGlobalRef(env, svc);
    g_iid[0] = 0;
    if (iid) {
        const char *s = (*env)->GetStringUTFChars(env, iid, NULL);
        if (s) { snprintf(g_iid, sizeof(g_iid), "%s", s); (*env)->ReleaseStringUTFChars(env, iid, s); }
    }
    g_running = 1;
    atomic_store(&g_sid, 1);
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

    if (st == READY_OK) return 0;
    Java_com_blacktunnel_BtProxy_nativeStop(env, cls);
    return (st == READY_AUTH) ? -2 : -1;
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeStop(JNIEnv *env, jclass cls) {
    (void)cls;
    pthread_mutex_lock(&g_mu);
    if (!g_running) { pthread_mutex_unlock(&g_mu); return; }
    g_running = 0;
    g_iid[0] = 0;
    jobject svc = g_svc; g_svc = NULL; g_jvm = NULL;
    int tfd = g_tfd; g_tfd = -1;
    int lfd = g_lfd; g_lfd = -1;
    pthread_mutex_unlock(&g_mu);

    atomic_fetch_add(&g_tunnel_epoch, 1);
    if (tfd >= 0) { shutdown(tfd, SHUT_RDWR); close(tfd); }
    if (lfd >= 0) { shutdown(lfd, SHUT_RDWR); close(lfd); }

    pthread_mutex_lock(&g_ready_mu);
    if (g_ready_st == READY_PENDING) { g_ready_st = READY_FAIL; pthread_cond_broadcast(&g_ready_cv); }
    pthread_mutex_unlock(&g_ready_mu);

    slot_close_all();
    if (svc) (*env)->DeleteGlobalRef(env, svc);
}

JNIEXPORT jstring JNICALL
Java_com_blacktunnel_BtProxy_nativeDrainLogs(JNIEnv *env, jclass cls) {
    (void)cls;
    pthread_mutex_lock(&g_log_mu);
    if (!g_loglen) { pthread_mutex_unlock(&g_log_mu); return (*env)->NewStringUTF(env, ""); }
    char out[32768]; memcpy(out, g_logbuf, g_loglen); out[g_loglen] = 0;
    g_loglen = 0; g_logbuf[0] = 0;
    pthread_mutex_unlock(&g_log_mu);
    return (*env)->NewStringUTF(env, out);
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeSetNetwork(JNIEnv *env, jclass cls, jlong net) {
    (void)env; (void)cls;
    pthread_mutex_lock(&g_mu); g_net = (net_handle_t)net; pthread_mutex_unlock(&g_mu);
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeSetGamingMode(JNIEnv *env, jclass cls, jboolean enabled) {
    (void)env; (void)cls; (void)enabled;
}

static JNINativeMethod g_methods[] = {
    {"nativeStart",        "(ILandroid/net/VpnService;Ljava/lang/String;)I", (void *)Java_com_blacktunnel_BtProxy_nativeStart},
    {"nativeStop",         "()V",                                            (void *)Java_com_blacktunnel_BtProxy_nativeStop},
    {"nativeDrainLogs",    "()Ljava/lang/String;",                           (void *)Java_com_blacktunnel_BtProxy_nativeDrainLogs},
    {"nativeSetNetwork",   "(J)V",                                           (void *)Java_com_blacktunnel_BtProxy_nativeSetNetwork},
    {"nativeSetGamingMode","(Z)V",                                           (void *)Java_com_blacktunnel_BtProxy_nativeSetGamingMode},
};

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *r) {
    (void)r;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = (*env)->FindClass(env, "com/blacktunnel/BtProxy");
    if (!cls) return JNI_ERR;
    if ((*env)->RegisterNatives(env, cls, g_methods, sizeof(g_methods)/sizeof(g_methods[0])) < 0) return JNI_ERR;
    (*env)->DeleteLocalRef(env, cls);
    return JNI_VERSION_1_6;
}
