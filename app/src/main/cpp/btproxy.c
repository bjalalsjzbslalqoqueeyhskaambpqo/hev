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
#define MAX_PAYLOAD   16384
#define RELAY_BACKLOG 512
#define IDLE_SECS     120
#define WD_INTERVAL   30
#define KEEPALIVE_SEC 25

#define HT_SIZE  4096
#define HT_MASK  (HT_SIZE - 1)

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

static volatile int    g_running  = 0;
static volatile int    g_started  = 0;
static int             g_relay_fd = -1;
static int             g_tun_fd   = -1;
static atomic_int      g_next_sid = 1;
static char            g_internal_id[160] = {0};
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

static void request_tunnel_reset(const char *reason) {
    int rfd = -1, tfd = -1;
    pthread_mutex_lock(&g_state_mu);
    if (!g_running) { pthread_mutex_unlock(&g_state_mu); return; }
    rfd = g_relay_fd; g_relay_fd = -1;
    tfd = g_tun_fd;   g_tun_fd   = -1;
    g_started = 0;
    pthread_mutex_unlock(&g_state_mu);
    if (reason) push_log("E", "tunnel reset: %s", reason);
    if (rfd >= 0) close(rfd);
    if (tfd >= 0) close(tfd);
    ht_clear();
}

static void sock_tune(int fd) {
    int v;
    v = 1;     setsockopt(fd, IPPROTO_TCP, TCP_NODELAY,  &v, sizeof(v));
    v = 1;     setsockopt(fd, IPPROTO_TCP, TCP_QUICKACK, &v, sizeof(v));
    v = 65536; setsockopt(fd, SOL_SOCKET,  SO_RCVBUF,    &v, sizeof(v));
    v = 65536; setsockopt(fd, SOL_SOCKET,  SO_SNDBUF,    &v, sizeof(v));
}

static void sock_keepalive(int fd) {
    int one=1, idle=30, intvl=5, cnt=3;
    setsockopt(fd, SOL_SOCKET,  SO_KEEPALIVE,  &one,   sizeof(one));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE,  &idle,  sizeof(idle));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &intvl, sizeof(intvl));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT,   &cnt,   sizeof(cnt));
}

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

static int tun_recv(int fd, uint8_t *buf, int len) {
    int off = 0;
    while (off < len) {
        ssize_t n = recv(fd, buf + off, len - off, 0);
        if (n > 0) {
            off += (int)n;
            int qa = 1;
            setsockopt(fd, IPPROTO_TCP, TCP_QUICKACK, &qa, sizeof(qa));
        } else if (n == 0) {
            return -1;
        } else if (errno == EINTR) {
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

    char req2[1024], h2[4096];
    int r2 = snprintf(req2, sizeof(req2),
        "- / HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\n"
        "Connection: Upgrade\r\nAction: tunnel\r\nX-Internal-ID: %s\r\n\r\n",
        TUNNEL_HOST, g_internal_id[0] ? g_internal_id : "unknown");
    send(fd, req2, r2, MSG_NOSIGNAL);
    int h2_len = recv_until_eoh(fd, h2, sizeof(h2), 8);
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
                    if (rn > 0) {
                        body_len += (int)rn;
                        body[body_len] = '\0';
                    }
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
            push_log("E", "error de autenticación 403");
        } else {
            push_log("E", "error de autenticación");
        }
        close(fd); return -1;
    }

    char user_name[128] = {0};
    char user_days[32] = {0};
    if (extract_header_value(h2, "X-User-Name", user_name, sizeof(user_name))) {
        push_log("I", "user_name=%s", user_name);
    }
    if (extract_header_value(h2, "X-User-Days", user_days, sizeof(user_days))) {
        push_log("I", "user_days=%s", user_days);
    }

    push_log("I", "tunnel handshake OK");
    return fd;
}

typedef struct { int cfd; int tfd; uint32_t sid; } conn_args_t;

static void *conn_thread(void *arg) {
    conn_args_t *ca = (conn_args_t*)arg;
    int cfd = ca->cfd, tfd = ca->tfd;
    uint32_t sid = ca->sid;
    free(ca);

    stream_t *s = ht_put(sid, cfd);
    if (!s) { close(cfd); return NULL; }

    uint8_t buf[MAX_PAYLOAD];
    ssize_t first = recv(cfd, buf, sizeof(buf), 0);
    if (first <= 0) { ht_del(sid); return NULL; }

    if (tun_send(tfd, T_OPEN, sid, buf, (uint16_t)first) < 0) {
        request_tunnel_reset("tun_send T_OPEN failed");
        ht_del(sid);
        return NULL;
    }

    while (g_running) {
        ssize_t n = recv(cfd, buf, sizeof(buf), 0);
        if (n <= 0) break;
        atomic_store(&s->last_active, (long)time(NULL));
        if (tun_send(tfd, T_DATA, sid, buf, (uint16_t)n) < 0) {
            request_tunnel_reset("tun_send T_DATA failed");
            break;
        }
    }

    tun_send(tfd, T_CLOSE, sid, NULL, 0);
    ht_del(sid);
    return NULL;
}

static void *tunnel_reader(void *arg) {
    int tfd = *(int*)arg;
    uint8_t hdr[FRAME_HDR], payload[MAX_PAYLOAD];

    while (g_running) {
        if (tun_recv(tfd, hdr, FRAME_HDR) < 0) {
            request_tunnel_reset("tunnel header read failed"); break;
        }

        uint8_t  ft  = hdr[0];
        uint32_t sid = ((uint32_t)hdr[1] << 24) | ((uint32_t)hdr[2] << 16) |
                       ((uint32_t)hdr[3] <<  8) |  (uint32_t)hdr[4];
        uint16_t len = ((uint16_t)hdr[5] << 8) | hdr[6];

        if (len > MAX_PAYLOAD) { request_tunnel_reset("payload too large"); break; }
        if (len > 0 && tun_recv(tfd, payload, len) < 0) {
            request_tunnel_reset("tunnel payload read failed"); break;
        }

        switch (ft) {
        case T_DATA: {
            stream_t *s = ht_get(sid);
            if (!s || len == 0) break;
            atomic_store(&s->last_active, (long)time(NULL));
            ssize_t off = 0;
            while (off < len) {
                ssize_t n = send(s->fd, payload + off, len - off,
                                 MSG_NOSIGNAL | MSG_DONTWAIT);
                if (n > 0) { off += n; continue; }
                if (errno == EAGAIN || errno == EWOULDBLOCK) {
                    struct timespec ts = {0, 200000};
                    nanosleep(&ts, NULL);
                    continue;
                }
                ht_del(sid);
                tun_send(tfd, T_CLOSE, sid, NULL, 0);
                break;
            }
            break;
        }
        case T_CLOSE:
            ht_del(sid);
            break;
        case T_PING:
            tun_send(tfd, T_PONG, 0, NULL, 0);
            break;
        case T_PONG:
            break;
        default:
            break;
        }
    }
    return NULL;
}

static void *keepalive_thread(void *arg) {
    int tfd = *(int*)arg;
    while (g_running) {
        sleep(KEEPALIVE_SEC);
        if (!g_running) break;
        if (tun_send(tfd, T_PING, 0, NULL, 0) < 0) {
            request_tunnel_reset("keepalive ping failed"); break;
        }
    }
    return NULL;
}

static void *watchdog_thread(void *arg) {
    (void)arg;
    while (g_running) {
        sleep(WD_INTERVAL);
        if (!g_running) break;
        long now = (long)time(NULL);
        int tfd = g_tun_fd;
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
                    if (tfd >= 0) tun_send(tfd, T_CLOSE, dsid, NULL, 0);
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

static void *main_thread(void *arg) {
    int port = (int)(intptr_t)arg;
    static int first_start = 1;

    while (g_running) {
        int tfd = open_tunnel();
        if (tfd < 0) {
            pthread_mutex_lock(&g_start_mu);
            if (g_start_st == 0) { g_start_st = -1; pthread_cond_broadcast(&g_start_cv); }
            pthread_mutex_unlock(&g_start_mu);
            if (!g_running) break;
            push_log("E", "reconnect in 2s");
            sleep(2); continue;
        }
        g_tun_fd = tfd;

        int rfd = socket(AF_INET, SOCK_STREAM, 0);
        if (rfd < 0) { close(tfd); g_tun_fd = -1; sleep(1); continue; }
        int one = 1; setsockopt(rfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
        int v = 1;   setsockopt(rfd, IPPROTO_TCP, TCP_NODELAY, &v,   sizeof(v));
        struct sockaddr_in la = {0};
        la.sin_family = AF_INET; la.sin_port = htons((uint16_t)port);
        la.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        if (bind(rfd, (struct sockaddr*)&la, sizeof(la)) < 0 ||
            listen(rfd, RELAY_BACKLOG) < 0) {
            close(rfd); close(tfd); g_relay_fd = -1; g_tun_fd = -1;
            pthread_mutex_lock(&g_start_mu);
            if (g_start_st == 0) { g_start_st = -1; pthread_cond_broadcast(&g_start_cv); }
            pthread_mutex_unlock(&g_start_mu);
            push_log("E", "relay bind/listen failed");
            sleep(2); continue;
        }

        g_relay_fd = rfd; g_started = 1;
        push_log("I", "relay listening on 127.0.0.1:%d", port);

        pthread_mutex_lock(&g_start_mu);
        if (g_start_st == 0) { g_start_st = 1; pthread_cond_broadcast(&g_start_cv); }
        pthread_mutex_unlock(&g_start_mu);

        if (first_start) {
            first_start = 0;
        } else {
            notify_tunnel_reconnected();
        }

        pthread_t trd, tka, twd;
        pthread_create(&trd, NULL, tunnel_reader,    &g_tun_fd);
        pthread_create(&tka, NULL, keepalive_thread, &g_tun_fd);
        pthread_create(&twd, NULL, watchdog_thread,  NULL);
        pthread_detach(trd); pthread_detach(tka); pthread_detach(twd);

        while (g_running) {
            struct sockaddr_in ca; socklen_t cl = sizeof(ca);
            int cfd = accept(rfd, (struct sockaddr*)&ca, &cl);
            if (cfd < 0) {
                if (!g_running) break;
                if (errno == EINTR || errno == EAGAIN) continue;
                request_tunnel_reset("accept failed"); break;
            }
            sock_tune(cfd);

            uint32_t sid;
            do {
                sid = (uint32_t)atomic_fetch_add(&g_next_sid, 1) & 0x7FFFFFFF;
            } while (sid == 0 || ht_get(sid) != NULL);

            conn_args_t *ca2 = malloc(sizeof(conn_args_t));
            if (!ca2) { close(cfd); continue; }
            ca2->cfd = cfd; ca2->tfd = tfd; ca2->sid = sid;

            pthread_t ct;
            if (pthread_create(&ct, NULL, conn_thread, ca2) != 0)
                { free(ca2); close(cfd); }
            else pthread_detach(ct);
        }

        request_tunnel_reset(NULL);
        if (g_running) { push_log("E", "tunnel dropped, reconnecting in 2s"); sleep(2); }
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
    ht_init();
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
    g_internal_id[0] = '\0';
    jobject svc = g_vpn_svc; g_vpn_svc = NULL; g_jvm = NULL;
    pthread_mutex_unlock(&g_state_mu);
    if (g_relay_fd >= 0) { close(g_relay_fd); g_relay_fd = -1; }
    if (g_tun_fd   >= 0) { close(g_tun_fd);   g_tun_fd   = -1; }
    pthread_mutex_lock(&g_start_mu);
    if (g_start_st == 0) { g_start_st = -1; pthread_cond_broadcast(&g_start_cv); }
    pthread_mutex_unlock(&g_start_mu);
    for (int i = 0; i < 10 && g_started; i++) usleep(10000);
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
