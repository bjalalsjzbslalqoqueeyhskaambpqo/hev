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

#define FRAME_HDR        7
#define MAX_PAYLOAD      16384
#define RELAY_BACKLOG    512
#define IDLE_SECS        90
#define WD_INTERVAL      15
#define PING_INTERVAL    10
#define PONG_TIMEOUT_SEC 45
#define RECONNECT_DELAY_MIN 2
#define RECONNECT_DELAY_MAX 30
#define CONNECT_TIMEOUT_SEC 10
#define HANDSHAKE_TIMEOUT_SEC 12
#define SID_WRAP_LIMIT   0x1FFFFFF0u

#define HT_SIZE  4096
#define HT_MASK  (HT_SIZE - 1)

#define STREAM_THRESH_LO   10
#define STREAM_THRESH_MID  30
#define STREAM_THRESH_HI   60
#define STREAM_THRESH_MAX  100
#define FILL_TARGET_LO     0
#define FILL_TARGET_MID    1024
#define FILL_TARGET_HI     2048
#define FILL_TARGET_MAX    4096
#define FILL_DRAIN_MS      2
#define FIRST_RECV_TIMEOUT_MS 8000

#define NUM_LABELS       3
#define LABEL_DATA0      0
#define LABEL_DATA1      1
#define LABEL_CTRL       2
#define SID_LABEL0_MIN   0x00000001u
#define SID_LABEL0_MAX   0x1FFFFFFFu
#define SID_LABEL1_MIN   0x20000000u
#define SID_LABEL1_MAX   0x3FFFFFFFu
#define SID_CTRL_MIN     0x40000000u
#define SID_CTRL_MAX     0x7FFFFFFFu

#define PROXY_HOST_IPV6 "2606:4700::6812:16b7"
#define PROXY_HOST      "emailmarketing.personal.com.ar"
#define PROXY_PORT      80
#define TUNNEL_HOST     "2.brawlpass.com.ar"

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
    s->sid = sid; s->fd = fd;
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

typedef struct {
    pthread_mutex_t wmu;
    atomic_int      stream_count;
    atomic_int      sid_counter;
    uint32_t        sid_min;
    uint32_t        sid_max;
} label_t;

static label_t         g_label[NUM_LABELS];
static int             g_tunnel_fd      = -1;
static pthread_mutex_t g_tunnel_fd_mu   = PTHREAD_MUTEX_INITIALIZER;

static volatile int    g_running        = 0;
static volatile int    g_started        = 0;
static int             g_relay_fd       = -1;
static char            g_internal_id[160] = {0};
static pthread_t       g_main_thr;
static JavaVM         *g_jvm            = NULL;
static jobject         g_vpn_svc        = NULL;
static pthread_mutex_t g_state_mu       = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_log_mu         = PTHREAD_MUTEX_INITIALIZER;
static char            g_log_buf[32768];
static size_t          g_log_len        = 0;
static pthread_mutex_t g_start_mu       = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_start_cv       = PTHREAD_COND_INITIALIZER;
static int             g_start_st       = 0;
static atomic_long     g_last_pong      = 0;
static atomic_int      g_tunnel_epoch   = 0;
static atomic_int      g_stream_count   = 0;
static atomic_long     g_last_rtt_ms    = -1;

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeStop(JNIEnv *env, jclass clazz);

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

static void notify_rtt_updated(long rtt_ms) {
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
    jmethodID m = (*env)->GetMethodID(env, cls, "onLatencyUpdated", "(J)V");
    if (m) (*env)->CallVoidMethod(env, svc, m, (jlong)rtt_ms);
    (*env)->DeleteLocalRef(env, cls);
    if (att) (*jvm)->DetachCurrentThread(jvm);
}

static void tunnel_close(void) {
    pthread_mutex_lock(&g_tunnel_fd_mu);
    if (g_tunnel_fd >= 0) {
        shutdown(g_tunnel_fd, SHUT_RDWR);
        close(g_tunnel_fd);
        g_tunnel_fd = -1;
    }
    pthread_mutex_unlock(&g_tunnel_fd_mu);
}

static void request_tunnel_reset(const char *reason) {
    int rfd = -1;
    pthread_mutex_lock(&g_state_mu);
    if (!g_running) { pthread_mutex_unlock(&g_state_mu); return; }
    rfd = g_relay_fd; g_relay_fd = -1;
    g_started = 0;
    pthread_mutex_unlock(&g_state_mu);
    if (reason) push_log("E", "tunnel reset: %s", reason);
    if (rfd >= 0) close(rfd);
    tunnel_close();
    atomic_store(&g_stream_count, 0);
    ht_clear();
}

static void sock_tune(int fd) {
    int v;
    v = 1;      setsockopt(fd, IPPROTO_TCP, TCP_NODELAY,  &v, sizeof(v));
    v = 1;      setsockopt(fd, IPPROTO_TCP, TCP_QUICKACK, &v, sizeof(v));
    v = 131072; setsockopt(fd, SOL_SOCKET,  SO_RCVBUF,   &v, sizeof(v));
    v = 131072; setsockopt(fd, SOL_SOCKET,  SO_SNDBUF,   &v, sizeof(v));
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

static int label_send(int label_idx, uint8_t type, uint32_t sid,
                      const uint8_t *data, uint16_t dlen) {
    label_t *lb = &g_label[label_idx];

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
    pthread_mutex_lock(&lb->wmu);
    int tfd = g_tunnel_fd;
    if (tfd < 0) { pthread_mutex_unlock(&lb->wmu); return -1; }
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
        } else if (errno == EINTR) {
            continue;
        } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
            struct pollfd wpfd = { tfd, POLLOUT, 0 };
            int wp = poll(&wpfd, 1, 30);
            if (wp <= 0 || !(wpfd.revents & POLLOUT)) {
                pthread_mutex_unlock(&lb->wmu);
                return -1;
            }
        } else {
            pthread_mutex_unlock(&lb->wmu);
            return -1;
        }
    }
    pthread_mutex_unlock(&lb->wmu);
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
            int qa = 1;
            setsockopt(fd, IPPROTO_TCP, TCP_QUICKACK, &qa, sizeof(qa));
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

    char req1[256], h1[2048];
    int r1 = snprintf(req1, sizeof(req1),
        "GET / HTTP/1.1\r\nHost: %s\r\n\r\n", PROXY_HOST);
    send(fd, req1, r1, MSG_NOSIGNAL);
    if (recv_until_eoh(fd, h1, sizeof(h1), HANDSHAKE_TIMEOUT_SEC) < 0) { close(fd); return -1; }

    char req2[1024], h2[4096];
    int r2 = snprintf(req2, sizeof(req2),
        "- / HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\n"
        "Connection: Upgrade\r\nAction: tunnel\r\nX-Internal-ID: %s\r\n\r\n",
        TUNNEL_HOST, g_internal_id[0] ? g_internal_id : "unknown");
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
    char user_days[32]  = {0};
    if (extract_header_value(h2, "X-User-Name", user_name, sizeof(user_name)))
        push_log("I", "user_name=%s", user_name);
    if (extract_header_value(h2, "X-User-Days", user_days, sizeof(user_days)))
        push_log("I", "user_days=%s", user_days);

    push_log("I", "tunnel connected fd=%d", fd);
    return fd;
}

static uint32_t alloc_sid(int label_idx) {
    label_t *lb = &g_label[label_idx];
    uint32_t sid;
    int attempts = 0;
    do {
        uint32_t raw   = (uint32_t)atomic_fetch_add(&lb->sid_counter, 1);
        uint32_t range = lb->sid_max - lb->sid_min + 1;
        sid = lb->sid_min + (raw % range);
        if (++attempts > 1000) {
            push_log("E", "sid alloc exhausted label=%d forcing reset", label_idx);
            request_tunnel_reset("sid exhausted");
            return 0;
        }
    } while (sid == 0 || ht_get(sid) != NULL);

    uint32_t used = (uint32_t)atomic_load(&lb->sid_counter) - lb->sid_min;
    if (used >= SID_WRAP_LIMIT) {
        push_log("I", "sid wrap limit reached label=%d reconnecting", label_idx);
        request_tunnel_reset("sid wrap");
    }

    return sid;
}

static int pick_data_label(void) {
    if (pthread_mutex_trylock(&g_label[LABEL_DATA0].wmu) == 0) {
        pthread_mutex_unlock(&g_label[LABEL_DATA0].wmu);
        return LABEL_DATA0;
    }
    return LABEL_DATA1;
}

typedef struct { int cfd; int label_idx; uint32_t sid; } conn_args_t;

static int fill_target_for(int sc) {
    if (sc >= STREAM_THRESH_MAX) return FILL_TARGET_MAX;
    if (sc >= STREAM_THRESH_HI)  return FILL_TARGET_HI;
    if (sc >= STREAM_THRESH_MID) return FILL_TARGET_MID;
    return FILL_TARGET_LO;
}

static int coalesce_recv(int cfd, uint8_t *buf, int bufsz, int sc) {
    struct pollfd pfd = { cfd, POLLIN, 0 };
    int pr = poll(&pfd, 1, 5000);
    if (pr <= 0) return (pr == 0) ? 0 : -1;
    ssize_t n = recv(cfd, buf, bufsz, 0);
    if (n <= 0) return (int)n;

    int target = fill_target_for(sc);
    if (target <= 0 || n >= bufsz) return (int)n;

    while (n < target) {
        pfd.revents = 0;
        pr = poll(&pfd, 1, FILL_DRAIN_MS);
        if (pr <= 0) break;
        ssize_t more = recv(cfd, buf + n, bufsz - n, MSG_DONTWAIT);
        if (more > 0) n += more;
        else break;
    }
    return (int)n;
}

static void *conn_thread(void *arg) {
    conn_args_t *ca = (conn_args_t*)arg;
    int      cfd       = ca->cfd;
    int      label_idx = ca->label_idx;
    uint32_t sid       = ca->sid;
    free(ca);

    if (sid == 0) { close(cfd); return NULL; }

    stream_t *s = ht_put(sid, cfd);
    if (!s) { close(cfd); return NULL; }

    atomic_fetch_add(&g_stream_count, 1);
    atomic_fetch_add(&g_label[label_idx].stream_count, 1);

    uint8_t buf[MAX_PAYLOAD];
    struct pollfd fpfd = { cfd, POLLIN, 0 };
    int fpr = poll(&fpfd, 1, FIRST_RECV_TIMEOUT_MS);
    if (fpr <= 0) {
        atomic_fetch_sub(&g_stream_count, 1);
        atomic_fetch_sub(&g_label[label_idx].stream_count, 1);
        ht_del(sid);
        return NULL;
    }
    ssize_t first = recv(cfd, buf, sizeof(buf), 0);
    if (first <= 0) {
        atomic_fetch_sub(&g_stream_count, 1);
        atomic_fetch_sub(&g_label[label_idx].stream_count, 1);
        ht_del(sid);
        return NULL;
    }

    if (label_send(label_idx, T_OPEN, sid, buf, (uint16_t)first) < 0) {
        request_tunnel_reset("label_send T_OPEN failed");
        atomic_fetch_sub(&g_stream_count, 1);
        atomic_fetch_sub(&g_label[label_idx].stream_count, 1);
        ht_del(sid);
        return NULL;
    }

    while (g_running) {
        int sc = atomic_load(&g_stream_count);
        int n  = coalesce_recv(cfd, buf, sizeof(buf), sc);
        if (n < 0) { if (errno == EINTR) continue; break; }
        if (n == 0) continue;
        atomic_store(&s->last_active, (long)time(NULL));
        if (label_send(label_idx, T_DATA, sid, buf, (uint16_t)n) < 0) {
            request_tunnel_reset("label_send T_DATA failed");
            break;
        }
    }

    label_send(label_idx, T_CLOSE, sid, NULL, 0);
    atomic_fetch_sub(&g_stream_count, 1);
    atomic_fetch_sub(&g_label[label_idx].stream_count, 1);
    ht_del(sid);
    return NULL;
}

typedef struct { int epoch; } thr_args_t;

static void *tunnel_reader(void *arg) {
    thr_args_t *ta = (thr_args_t*)arg;
    int epoch      = ta->epoch;
    free(ta);

    uint8_t hdr[FRAME_HDR], payload[MAX_PAYLOAD];

    while (g_running && atomic_load(&g_tunnel_epoch) == epoch) {
        int tfd;
        pthread_mutex_lock(&g_tunnel_fd_mu);
        tfd = g_tunnel_fd;
        pthread_mutex_unlock(&g_tunnel_fd_mu);
        if (tfd < 0) break;

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
            ssize_t off = 0;
            int stream_ok = 1;
            while (off < len) {
                ssize_t n = send(s->fd, payload + off, len - off,
                                 MSG_NOSIGNAL | MSG_DONTWAIT);
                if (n > 0) { off += n; continue; }
                if (errno == EAGAIN || errno == EWOULDBLOCK) {
                    struct pollfd spfd = { s->fd, POLLOUT, 0 };
                    int sp = poll(&spfd, 1, 8);
                    if (sp > 0 && (spfd.revents & POLLOUT)) continue;
                    stream_ok = 0;
                    break;
                }
                stream_ok = 0;
                break;
            }
            if (!stream_ok) {
                int sl = (sid >= SID_LABEL1_MIN && sid <= SID_LABEL1_MAX) ? LABEL_DATA1 : LABEL_DATA0;
                ht_del(sid);
                label_send(sl, T_CLOSE, sid, NULL, 0);
            }
            break;
        }
        case T_CLOSE:
            ht_del(sid);
            break;
        case T_PING:
            label_send(LABEL_CTRL, T_PONG, sid, NULL, 0);
            break;
        case T_PONG: {
            atomic_store(&g_last_pong, (long)time(NULL));
            break;
        }
        default:
            break;
        }
    }
    return NULL;
}

static void *keepalive_thread(void *arg) {
    int epoch = (int)(intptr_t)arg;

    atomic_store(&g_last_pong, (long)time(NULL));

    while (g_running && atomic_load(&g_tunnel_epoch) == epoch) {
        for (int i = 0; i < PING_INTERVAL && g_running && atomic_load(&g_tunnel_epoch) == epoch; i++)
            sleep(1);
        if (!g_running || atomic_load(&g_tunnel_epoch) != epoch) break;

        long last = atomic_load(&g_last_pong);
        if (last > 0 && (long)time(NULL) - last > PONG_TIMEOUT_SEC) {
            request_tunnel_reset("pong timeout in keepalive");
            break;
        }

        struct timespec t0, t1;
        clock_gettime(CLOCK_MONOTONIC, &t0);
        long prev_pong = atomic_load(&g_last_pong);

        if (label_send(LABEL_CTRL, T_PING, SID_CTRL_MIN, NULL, 0) < 0) {
            request_tunnel_reset("keepalive ping failed");
            break;
        }

        for (int w = 0; w < PING_INTERVAL && g_running; w++) {
            sleep(1);
            if (atomic_load(&g_last_pong) != prev_pong) break;
        }
        clock_gettime(CLOCK_MONOTONIC, &t1);
        long rtt_ms = (t1.tv_sec - t0.tv_sec) * 1000L +
                      (t1.tv_nsec - t0.tv_nsec) / 1000000L - 20L;
        if (rtt_ms < 0) rtt_ms = 0;
        atomic_store(&g_last_rtt_ms, rtt_ms);
        push_log("I", "ping_rtt=%ldms", rtt_ms);
        notify_rtt_updated(rtt_ms);
    }
    return NULL;
}

static void *watchdog_thread(void *arg) {
    int epoch = (int)(intptr_t)arg;
    while (g_running && atomic_load(&g_tunnel_epoch) == epoch) {
        for (int i = 0; i < WD_INTERVAL && g_running && atomic_load(&g_tunnel_epoch) == epoch; i++)
            sleep(1);
        if (!g_running || atomic_load(&g_tunnel_epoch) != epoch) break;

        long now = (long)time(NULL);
        for (int i = 0; i < HT_SIZE; i++) {
            pthread_mutex_lock(&g_ht_mu[i]);
            stream_t **pp = &g_ht[i];
            while (*pp) {
                stream_t *s = *pp;
                if (now - atomic_load(&s->last_active) > IDLE_SECS) {
                    *pp = s->next;
                    uint32_t dsid = s->sid;
                    if (s->fd >= 0) close(s->fd);
                    free(s);
                    pthread_mutex_unlock(&g_ht_mu[i]);
                    int sl = (dsid >= SID_LABEL1_MIN && dsid <= SID_LABEL1_MAX) ? LABEL_DATA1 : LABEL_DATA0;
                    label_send(sl, T_CLOSE, dsid, NULL, 0);
                    pthread_mutex_lock(&g_ht_mu[i]);
                } else {
                    pp = &s->next;
                }
            }
            pthread_mutex_unlock(&g_ht_mu[i]);
        }
    }
    return NULL;
}

static int g_reconnect_delay = RECONNECT_DELAY_MIN;

static void *main_thread(void *arg) {
    int port = (int)(intptr_t)arg;
    static int first_start = 1;

    while (g_running) {
        int fd = open_tunnel();
        if (fd < 0) {
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

        pthread_mutex_lock(&g_tunnel_fd_mu);
        g_tunnel_fd = fd;
        pthread_mutex_unlock(&g_tunnel_fd_mu);

        atomic_store(&g_label[LABEL_DATA0].sid_counter, 1);
        atomic_store(&g_label[LABEL_DATA1].sid_counter, 0);
        atomic_store(&g_label[LABEL_CTRL].sid_counter,  0);

        g_reconnect_delay = RECONNECT_DELAY_MIN;

        int rfd = socket(AF_INET, SOCK_STREAM, 0);
        if (rfd < 0) { tunnel_close(); sleep(1); continue; }
        int one = 1; setsockopt(rfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
        int v = 1;   setsockopt(rfd, IPPROTO_TCP, TCP_NODELAY,  &v,   sizeof(v));
        int fl = fcntl(rfd, F_GETFD, 0);
        if (fl >= 0) fcntl(rfd, F_SETFD, fl | FD_CLOEXEC);
        struct sockaddr_in la = {0};
        la.sin_family = AF_INET; la.sin_port = htons((uint16_t)port);
        la.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        if (bind(rfd, (struct sockaddr*)&la, sizeof(la)) < 0 ||
            listen(rfd, RELAY_BACKLOG) < 0) {
            close(rfd); tunnel_close(); g_relay_fd = -1;
            pthread_mutex_lock(&g_start_mu);
            if (g_start_st == 0) { g_start_st = -1; pthread_cond_broadcast(&g_start_cv); }
            pthread_mutex_unlock(&g_start_mu);
            push_log("E", "relay bind/listen failed");
            sleep(2); continue;
        }

        int cur_epoch = atomic_fetch_add(&g_tunnel_epoch, 1) + 1;
        g_relay_fd = rfd; g_started = 1;
        push_log("I", "relay on 127.0.0.1:%d epoch=%d", port, cur_epoch);

        pthread_mutex_lock(&g_start_mu);
        if (g_start_st == 0) { g_start_st = 1; pthread_cond_broadcast(&g_start_cv); }
        pthread_mutex_unlock(&g_start_mu);

        if (first_start) {
            first_start = 0;
        } else {
            notify_tunnel_reconnected();
        }

        thr_args_t *ta = malloc(sizeof(thr_args_t));
        if (ta) {
            ta->epoch = cur_epoch;
            pthread_t trd;
            pthread_create(&trd, NULL, tunnel_reader, ta);
            pthread_detach(trd);
        }

        pthread_t tka, twd;
        pthread_create(&tka, NULL, keepalive_thread, (void*)(intptr_t)cur_epoch);
        pthread_detach(tka);
        pthread_create(&twd, NULL, watchdog_thread, (void*)(intptr_t)cur_epoch);
        pthread_detach(twd);

        while (g_running) {
            struct pollfd pfd = { rfd, POLLIN, 0 };
            int pr = poll(&pfd, 1, 2000);
            if (!g_running) break;
            if (pr < 0) {
                if (errno == EINTR) continue;
                request_tunnel_reset("accept poll failed"); break;
            }
            if (pr > 0 && (pfd.revents & (POLLERR | POLLHUP | POLLNVAL))) break;
            if (pr == 0) {
                pthread_mutex_lock(&g_state_mu);
                int still_same = (g_relay_fd == rfd);
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

            int label_idx = pick_data_label();
            uint32_t sid  = alloc_sid(label_idx);

            conn_args_t *ca2 = malloc(sizeof(conn_args_t));
            if (!ca2) { close(cfd); continue; }
            ca2->cfd       = cfd;
            ca2->label_idx = label_idx;
            ca2->sid       = sid;

            pthread_t ct;
            if (pthread_create(&ct, NULL, conn_thread, ca2) != 0)
                { free(ca2); close(cfd); }
            else pthread_detach(ct);
        }

        request_tunnel_reset(NULL);
        if (g_running) { push_log("E", "tunnel dropped, reconnecting"); sleep(1); }
    }
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

    pthread_mutex_init(&g_label[LABEL_DATA0].wmu, NULL);
    g_label[LABEL_DATA0].sid_min = SID_LABEL0_MIN;
    g_label[LABEL_DATA0].sid_max = SID_LABEL0_MAX;
    atomic_store(&g_label[LABEL_DATA0].sid_counter, 1);
    atomic_store(&g_label[LABEL_DATA0].stream_count, 0);

    pthread_mutex_init(&g_label[LABEL_DATA1].wmu, NULL);
    g_label[LABEL_DATA1].sid_min = SID_LABEL1_MIN;
    g_label[LABEL_DATA1].sid_max = SID_LABEL1_MAX;
    atomic_store(&g_label[LABEL_DATA1].sid_counter, 0);
    atomic_store(&g_label[LABEL_DATA1].stream_count, 0);

    pthread_mutex_init(&g_label[LABEL_CTRL].wmu, NULL);
    g_label[LABEL_CTRL].sid_min = SID_CTRL_MIN;
    g_label[LABEL_CTRL].sid_max = SID_CTRL_MAX;
    atomic_store(&g_label[LABEL_CTRL].sid_counter, 0);
    atomic_store(&g_label[LABEL_CTRL].stream_count, 0);

    g_tunnel_fd = -1;
    ht_init();
    g_running = 1; g_started = 0;
    g_reconnect_delay = RECONNECT_DELAY_MIN;
    atomic_store(&g_stream_count, 0);
    atomic_store(&g_last_rtt_ms, -1);
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
    tunnel_close();
    pthread_mutex_lock(&g_start_mu);
    if (g_start_st == 0) { g_start_st = -1; pthread_cond_broadcast(&g_start_cv); }
    pthread_mutex_unlock(&g_start_mu);
    for (int i = 0; i < 20 && g_started; i++) usleep(10000);
    ht_clear();
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

JNIEXPORT jlong JNICALL
Java_com_blacktunnel_BtProxy_nativeGetLastRtt(JNIEnv *env, jclass clazz) {
    return (jlong)atomic_load(&g_last_rtt_ms);
}
