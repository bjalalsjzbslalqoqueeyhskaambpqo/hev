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
#include <sys/socket.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>
#include <poll.h>
#include <fcntl.h>

#define LOG_TAG "btproxy"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define T_DATA              0x02
#define T_CLOSE             0x03
#define T_PING              0x04
#define T_PONG              0x05
#define T_OPEN              0x01

#define FRAME_HDR           7
#define MAX_PAYLOAD         16384
#define RELAY_BACKLOG       512
#define HOTSPOT_PORT        10810
#define PONG_TIMEOUT_SEC    180
#define RECONNECT_DELAY_MIN 2
#define RECONNECT_DELAY_MAX 30
#define CONNECT_TIMEOUT_SEC 10
#define HANDSHAKE_TIMEOUT_SEC 1
#define CONN_WORKERS        64

#define HT_SIZE             4096
#define HT_MASK             (HT_SIZE - 1)

#define SNDBUF_GAMING       32768

#define PROXY_HOST_IPV6     "2606:4700::6812:16b7"
#define PROXY_HOST          "emailmarketing.personal.com.ar"
#define PROXY_PORT          80
#define TUNNEL_HOST         "2.brawlpass.com.ar"

#define GLOBAL_MODE_DAILY   0
#define GLOBAL_MODE_GAMING  1

static atomic_int g_global_mode = GLOBAL_MODE_DAILY;
static int        g_sndbuf_daily = 262144;

static int detect_sndbuf_daily(void) {
    FILE *f = fopen("/proc/meminfo", "r");
    if (!f) return 262144;
    char line[128];
    long kb = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "MemTotal:", 9) == 0) {
            sscanf(line + 9, " %ld", &kb);
            break;
        }
    }
    fclose(f);
    long gb_x10 = (kb * 10L) / (1024L * 1024L);
    if (gb_x10 <= 35)  return  65536;
    if (gb_x10 <= 55)  return 131072;
    return 262144;
}

typedef struct stream_s {
    struct stream_s *next;
    int              fd;
    int              pfd[2];
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
    if (pipe(s->pfd) < 0) { free(s); return NULL; }
    fcntl(s->pfd[1], F_SETFL, O_NONBLOCK);
#ifdef F_SETPIPE_SZ
    int mode = atomic_load(&g_global_mode);
    int pipe_sz = (mode == GLOBAL_MODE_GAMING) ? 65536 : g_sndbuf_daily;
    fcntl(s->pfd[0], F_SETPIPE_SZ, pipe_sz);
#endif
    s->sid  = sid;
    s->fd   = fd;
    s->next = NULL;
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
            if (s->fd     >= 0) close(s->fd);
            if (s->pfd[0] >= 0) close(s->pfd[0]);
            if (s->pfd[1] >= 0) close(s->pfd[1]);
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
            if (s->fd     >= 0) close(s->fd);
            if (s->pfd[0] >= 0) close(s->pfd[0]);
            if (s->pfd[1] >= 0) close(s->pfd[1]);
            free(s);
            s = nx;
        }
        g_ht[i] = NULL;
        pthread_mutex_unlock(&g_ht_mu[i]);
    }
}

static volatile int    g_running   = 0;
static volatile int    g_started   = 0;
static int             g_relay_fd  = -1;
static int             g_tun_fd    = -1;
static int             g_hotspot_fd = -1;
static atomic_int      g_hotspot_enabled = 0;
static uint32_t        g_hotspot_ip = 0;
static atomic_int      g_next_sid  = 1;
static char            g_internal_id[160] = {0};
static pthread_t       g_main_thr;
static JavaVM         *g_jvm       = NULL;
static jobject         g_vpn_svc   = NULL;
static net_handle_t    g_network   = NETWORK_UNSPECIFIED;
static pthread_mutex_t g_state_mu  = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_tun_wmu   = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_log_mu    = PTHREAD_MUTEX_INITIALIZER;
static char            g_log_buf[32768];
static size_t          g_log_len   = 0;
static pthread_mutex_t g_start_mu  = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_start_cv  = PTHREAD_COND_INITIALIZER;
static int             g_start_st  = 0;
static atomic_long     g_last_pong = 0;
static atomic_int      g_tunnel_epoch = 0;

typedef struct conn_task_s {
    struct conn_task_s *next;
    int cfd;
    int tfd;
    uint32_t sid;
} conn_task_t;

static pthread_mutex_t g_connq_mu   = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_connq_cv   = PTHREAD_COND_INITIALIZER;
static conn_task_t    *g_connq_head = NULL;
static conn_task_t    *g_connq_tail = NULL;
static pthread_t       g_conn_workers[CONN_WORKERS];
static int             g_conn_workers_started = 0;

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeStop(JNIEnv *env, jclass clazz);
static void connq_clear(void);

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
    int hfd = g_hotspot_fd; g_hotspot_fd = -1;
    pthread_mutex_unlock(&g_state_mu);
    if (reason) push_log("E", "tunnel reset: %s", reason);
    if (rfd >= 0) close(rfd);
    if (tfd >= 0) close(tfd);
    if (hfd >= 0) close(hfd);
    connq_clear();
    ht_clear();
}

static void tune_tunnel(int fd) {
    int v;
    v = 1;      setsockopt(fd, IPPROTO_TCP, TCP_NODELAY,   &v, sizeof(v));
    v = 1;      setsockopt(fd, IPPROTO_TCP, TCP_QUICKACK,  &v, sizeof(v));
    v = 0x10;   setsockopt(fd, IPPROTO_IP,  IP_TOS,        &v, sizeof(v));
    v = 262144; setsockopt(fd, SOL_SOCKET,  SO_SNDBUF,     &v, sizeof(v));
    v = 262144; setsockopt(fd, SOL_SOCKET,  SO_RCVBUF,     &v, sizeof(v));
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof(one));
    int mode = atomic_load(&g_global_mode);
    int idle  = (mode == GLOBAL_MODE_GAMING) ? 10 : 30;
    int intvl = (mode == GLOBAL_MODE_GAMING) ?  3 :  5;
    int cnt   = 3;
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE,  &idle,  sizeof(idle));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &intvl, sizeof(intvl));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT,   &cnt,   sizeof(cnt));
    int flags = fcntl(fd, F_GETFD, 0);
    if (flags >= 0) fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
}

static void tune_local(int fd) {
    int v = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &v, sizeof(v));
    int mode = atomic_load(&g_global_mode);
    v = (mode == GLOBAL_MODE_GAMING) ? SNDBUF_GAMING : g_sndbuf_daily;
    setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &v, sizeof(v));
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &v, sizeof(v));
    int flags = fcntl(fd, F_GETFD, 0);
    if (flags >= 0) fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
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
    while (sent < total) {
        pthread_mutex_lock(&g_tun_wmu);
        ssize_t n = writev(tfd, iov, niov);
        if (n > 0) {
            pthread_mutex_unlock(&g_tun_wmu);
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
            pthread_mutex_unlock(&g_tun_wmu);
            continue;
        } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
            pthread_mutex_unlock(&g_tun_wmu);
            int poll_ms = (atomic_load(&g_global_mode) == GLOBAL_MODE_GAMING) ? 5 : 30;
            struct pollfd wpfd = { tfd, POLLOUT, 0 };
            int wp = poll(&wpfd, 1, poll_ms);
            if (wp <= 0 || !(wpfd.revents & POLLOUT)) return -1;
        } else {
            pthread_mutex_unlock(&g_tun_wmu);
            return -1;
        }
    }
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
    
    net_handle_t net;
    pthread_mutex_lock(&g_state_mu);
    net = g_network;
    pthread_mutex_unlock(&g_state_mu);
    if (net != NETWORK_UNSPECIFIED) {
        android_setsocknetwork(net, fd);
    }

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
            jni_protect(fd);
            tune_tunnel(fd);
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
                jni_protect(s);
                tune_tunnel(s);
                if (connect_with_timeout(s, r->ai_addr, r->ai_addrlen, CONNECT_TIMEOUT_SEC) == 0)
                    fd = s;
                else close(s);
            }
            freeaddrinfo(res);
        }
    }

    if (fd < 0) { push_log("E", "tunnel connect failed"); return -1; }

    atomic_store(&g_last_pong, (long)time(NULL));

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
        if (status == 403) {
            char body[256] = {0};
            recv(fd, body, sizeof(body)-1, MSG_DONTWAIT);
            if (strstr(h2, "not_registered") || strstr(body, "not_registered"))
                push_log("E", "usuario no registrado");
            else if (strstr(h2, "expired") || strstr(body, "expired"))
                push_log("E", "usuario expirado");
            else
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

    push_log("I", "tunnel ok mode=%s sndbuf_daily=%d",
             atomic_load(&g_global_mode) == GLOBAL_MODE_GAMING ? "gaming" : "daily",
             g_sndbuf_daily);
    return fd;
}

static int flush_pipe_to_cfd(stream_t *s, int cfd, uint8_t *buf) {
    ssize_t n = read(s->pfd[0], buf, MAX_PAYLOAD);
    if (n > 0) {
        ssize_t off = 0;
        while (off < n) {
            ssize_t w = send(cfd, buf + off, n - off, MSG_NOSIGNAL);
            if (w > 0) { off += w; continue; }
            if (errno == EINTR) continue;
            return -1;
        }
        return 1;
    }
    if (n == 0 || (errno != EAGAIN && errno != EINTR)) return -1;
    return 0;
}

static void conn_handle(int cfd, int tfd, uint32_t sid) {
    stream_t *s = ht_put(sid, cfd);
    if (!s) { close(cfd); return; }

    uint8_t buf[MAX_PAYLOAD];

    ssize_t first = recv(cfd, buf, sizeof(buf), 0);
    if (first <= 0) { ht_del(sid); return; }

    if (tun_send(tfd, T_OPEN, sid, buf, (uint16_t)first) < 0) {
        request_tunnel_reset("tun_send T_OPEN failed");
        ht_del(sid);
        return;
    }

    int gaming   = (atomic_load(&g_global_mode) == GLOBAL_MODE_GAMING);
    int hev_done = 0;

    struct pollfd pfds[2];
    pfds[0].fd = cfd;     pfds[0].events = POLLIN;
    pfds[1].fd = s->pfd[0]; pfds[1].events = POLLIN;

    while (g_running) {
        int timeout = hev_done ? 200 : (gaming ? 100 : 500);
        int pr = poll(pfds, 2, timeout);
        if (pr < 0) { if (errno == EINTR) continue; break; }

        if (pfds[0].revents & (POLLERR | POLLHUP | POLLNVAL)) break;
        if (pfds[1].revents & (POLLERR | POLLHUP | POLLNVAL)) break;

        
        int order[2] = { gaming ? 1 : 0, gaming ? 0 : 1 };
        for (int oi = 0; oi < 2; oi++) {
            int idx = order[oi];
            if (!(pfds[idx].revents & POLLIN)) continue;

            if (idx == 1) {
                
                if (flush_pipe_to_cfd(s, cfd, buf) < 0) goto done;
            } else {
                
                if (hev_done) continue;
                ssize_t n = recv(cfd, buf, sizeof(buf), 0);
                if (n < 0) { if (errno == EINTR) continue; goto done; }
                if (n == 0) {
                    shutdown(cfd, SHUT_RD);
                    tun_send(tfd, T_CLOSE, sid, NULL, 0);
                    hev_done = 1;
                    pfds[0].events = 0;
                    continue;
                }
                if (tun_send(tfd, T_DATA, sid, buf, (uint16_t)n) < 0) {
                    request_tunnel_reset("tun_send T_DATA failed");
                    goto done;
                }
            }
        }

        
        if (hev_done && pr == 0) break;
    }

done:
    ht_del(sid);
}

static void connq_push(int cfd, int tfd, uint32_t sid) {
    conn_task_t *t = malloc(sizeof(conn_task_t));
    if (!t) { close(cfd); return; }
    t->next = NULL; t->cfd = cfd; t->tfd = tfd; t->sid = sid;
    pthread_mutex_lock(&g_connq_mu);
    if (g_connq_tail) g_connq_tail->next = t;
    else g_connq_head = t;
    g_connq_tail = t;
    pthread_cond_signal(&g_connq_cv);
    pthread_mutex_unlock(&g_connq_mu);
}

static conn_task_t *connq_pop(void) {
    pthread_mutex_lock(&g_connq_mu);
    while (g_running && g_connq_head == NULL)
        pthread_cond_wait(&g_connq_cv, &g_connq_mu);
    conn_task_t *t = g_connq_head;
    if (t) {
        g_connq_head = t->next;
        if (!g_connq_head) g_connq_tail = NULL;
    }
    pthread_mutex_unlock(&g_connq_mu);
    return t;
}

static void connq_clear(void) {
    pthread_mutex_lock(&g_connq_mu);
    conn_task_t *t = g_connq_head;
    while (t) {
        conn_task_t *nx = t->next;
        if (t->cfd >= 0) close(t->cfd);
        free(t); t = nx;
    }
    g_connq_head = NULL; g_connq_tail = NULL;
    pthread_mutex_unlock(&g_connq_mu);
}

static void *conn_worker(void *arg) {
    (void)arg;
    while (g_running) {
        conn_task_t *t = connq_pop();
        if (!t) continue;
        conn_handle(t->cfd, t->tfd, t->sid);
        free(t);
    }
    return NULL;
}

static void start_conn_workers(void) {
    if (g_conn_workers_started > 0) return;
    for (int i = 0; i < CONN_WORKERS; i++) {
        if (pthread_create(&g_conn_workers[i], NULL, conn_worker, NULL) != 0) break;
        g_conn_workers_started++;
    }
}

static void stop_conn_workers(void) {
    pthread_mutex_lock(&g_connq_mu);
    pthread_cond_broadcast(&g_connq_cv);
    pthread_mutex_unlock(&g_connq_mu);
    for (int i = 0; i < g_conn_workers_started; i++) {
        pthread_join(g_conn_workers[i], NULL);
        g_conn_workers[i] = 0;
    }
    g_conn_workers_started = 0;
}

typedef struct { int tfd; int epoch; } thr_args_t;

static void *tunnel_reader(void *arg) {
    thr_args_t *ta = (thr_args_t*)arg;
    int tfd   = ta->tfd;
    int epoch = ta->epoch;
    free(ta);

    uint8_t hdr[FRAME_HDR], payload[MAX_PAYLOAD];

    while (g_running && atomic_load(&g_tunnel_epoch) == epoch) {
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
        if (rc < 0) { request_tunnel_reset("tunnel header read failed"); break; }

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
            ssize_t off = 0;
            while (off < (ssize_t)len) {
                ssize_t n = write(s->pfd[1], payload + off, len - off);
                if (n > 0) { off += n; continue; }
                if (errno == EAGAIN || errno == EWOULDBLOCK) break;
                if (errno == EINTR) continue;
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
            atomic_store(&g_last_pong, (long)time(NULL));
            break;
        default:
            break;
        }
    }
    return NULL;
}

static void *keepalive_thread(void *arg) {
    thr_args_t *ta = (thr_args_t*)arg;
    int tfd   = ta->tfd;
    int epoch = ta->epoch;
    free(ta);

    atomic_store(&g_last_pong, (long)time(NULL));

    int ping_interval = atomic_load(&g_global_mode) == GLOBAL_MODE_GAMING ? 5 : 20;
    long last_ping = (long)time(NULL);

    while (g_running && atomic_load(&g_tunnel_epoch) == epoch) {
        sleep(1);
        if (!g_running || atomic_load(&g_tunnel_epoch) != epoch) break;

        long now       = (long)time(NULL);
        long last_pong = atomic_load(&g_last_pong);

        if (last_pong > 0 && now - last_pong > PONG_TIMEOUT_SEC) {
            request_tunnel_reset("pong timeout in keepalive");
            break;
        }

        if (now - last_ping < ping_interval) continue;
        last_ping = now;

        struct timespec tp0, tp1;
        clock_gettime(CLOCK_MONOTONIC, &tp0);

        if (tun_send(tfd, T_PING, 0, NULL, 0) < 0) {
            request_tunnel_reset("keepalive ping failed");
            break;
        }

        int wait_us = 5000;
        for (int elapsed_us = 0; elapsed_us < 3000000 && g_running; elapsed_us += wait_us) {
            long new_pong = atomic_load(&g_last_pong);
            if (new_pong > last_pong) break;
            usleep(wait_us);
            if (wait_us < 50000) wait_us *= 2;
        }

        clock_gettime(CLOCK_MONOTONIC, &tp1);
        long rtt = (tp1.tv_sec - tp0.tv_sec) * 1000L + (tp1.tv_nsec - tp0.tv_nsec) / 1000000L;
        if (rtt > 0) push_log("I", "ping_ms=%ld", rtt);
    }
    return NULL;
}

static int g_reconnect_delay = RECONNECT_DELAY_MIN;

static void *main_thread(void *arg) {
    int port = (int)(intptr_t)arg;
    static int first_start = 1;
    start_conn_workers();

    while (g_running) {
        int tfd = open_tunnel();
        if (tfd < 0) {
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
        g_reconnect_delay = RECONNECT_DELAY_MIN;
        g_tun_fd = tfd;

        int rfd = socket(AF_INET, SOCK_STREAM, 0);
        if (rfd < 0) { close(tfd); g_tun_fd = -1; sleep(1); continue; }
        int one = 1; setsockopt(rfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
        int fl = fcntl(rfd, F_GETFD, 0);
        if (fl >= 0) fcntl(rfd, F_SETFD, fl | FD_CLOEXEC);
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

        
        int hfd = -1;
        if (atomic_load(&g_hotspot_enabled)) {
            hfd = socket(AF_INET, SOCK_STREAM, 0);
            if (hfd >= 0) {
                int one = 1;
                setsockopt(hfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
                int fl = fcntl(hfd, F_GETFD, 0);
                if (fl >= 0) fcntl(hfd, F_SETFD, fl | FD_CLOEXEC);
                struct sockaddr_in ha = {0};
                ha.sin_family = AF_INET;
                ha.sin_port   = htons(HOTSPOT_PORT);
                ha.sin_addr.s_addr = g_hotspot_ip ? g_hotspot_ip : htonl(INADDR_ANY);
                if (bind(hfd, (struct sockaddr*)&ha, sizeof(ha)) < 0 ||
                    listen(hfd, RELAY_BACKLOG) < 0) {
                    close(hfd); hfd = -1;
                    push_log("E", "hotspot relay bind failed");
                } else {
                    g_hotspot_fd = hfd;
                    push_log("I", "hotspot relay listening on port %d", HOTSPOT_PORT);
                }
            }
        }

        int cur_epoch = atomic_fetch_add(&g_tunnel_epoch, 1) + 1;
        g_relay_fd = rfd; g_started = 1;
        push_log("I", "relay listening on 127.0.0.1:%d epoch=%d", port, cur_epoch);

        pthread_mutex_lock(&g_start_mu);
        if (g_start_st == 0) { g_start_st = 1; pthread_cond_broadcast(&g_start_cv); }
        pthread_mutex_unlock(&g_start_mu);

        if (first_start) {
            first_start = 0;
        } else {
            notify_tunnel_reconnected();
        }

        thr_args_t *ta_rd = malloc(sizeof(thr_args_t));
        thr_args_t *ta_ka = malloc(sizeof(thr_args_t));
        if (ta_rd) { ta_rd->tfd = tfd; ta_rd->epoch = cur_epoch; }
        if (ta_ka) { ta_ka->tfd = tfd; ta_ka->epoch = cur_epoch; }

        pthread_t trd, tka;
        if (ta_rd) { pthread_create(&trd, NULL, tunnel_reader, ta_rd); pthread_detach(trd); }
        else free(ta_rd);
        if (ta_ka) { pthread_create(&tka, NULL, keepalive_thread, ta_ka); pthread_detach(tka); }
        else free(ta_ka);

        while (g_running) {
            
            struct pollfd pfds[2];
            pfds[0].fd = rfd; pfds[0].events = POLLIN;
            pfds[1].fd = hfd >= 0 ? hfd : -1; pfds[1].events = POLLIN;
            int nfds = hfd >= 0 ? 2 : 1;

            int pr = poll(pfds, nfds, 1000);
            if (!g_running) break;
            if (pr < 0) {
                if (errno == EINTR) continue;
                request_tunnel_reset("accept poll failed"); break;
            }
            if (pr > 0 && (pfds[0].revents & (POLLERR | POLLHUP | POLLNVAL))) break;

            
            if (pr == 0) {
                pthread_mutex_lock(&g_state_mu);
                int still_same = (g_tun_fd == tfd && g_relay_fd == rfd);
                pthread_mutex_unlock(&g_state_mu);
                if (!still_same) break;
                continue;
            }

            
            for (int i = 0; i < nfds; i++) {
                if (!(pfds[i].revents & POLLIN)) continue;
                struct sockaddr_in ca; socklen_t cl = sizeof(ca);
                int cfd = accept(pfds[i].fd, (struct sockaddr*)&ca, &cl);
                if (cfd < 0) {
                    if (!g_running) goto tunnel_done;
                    if (errno == EINTR || errno == EAGAIN) continue;
                    if (i == 0) { request_tunnel_reset("accept failed"); goto tunnel_done; }
                    continue;
                }
                tune_local(cfd);
                uint32_t sid;
                do {
                    sid = (uint32_t)atomic_fetch_add(&g_next_sid, 1) & 0x7FFFFFFF;
                } while (sid == 0 || ht_get(sid) != NULL);
                connq_push(cfd, tfd, sid);
            }
        }
        tunnel_done:;

        request_tunnel_reset(NULL);
        if (g_running) { push_log("E", "tunnel dropped, reconnecting"); sleep(1); }
    }
    connq_clear();
    stop_conn_workers();
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
    g_sndbuf_daily = detect_sndbuf_daily();
    push_log("I", "sndbuf_daily=%d", g_sndbuf_daily);
    ht_init();
    g_running = 1; g_started = 0;
    g_reconnect_delay = RECONNECT_DELAY_MIN;
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
    pthread_mutex_lock(&g_state_mu);
    int rfd = g_relay_fd; g_relay_fd = -1;
    int tfd = g_tun_fd;   g_tun_fd   = -1;
    int hfd = g_hotspot_fd; g_hotspot_fd = -1;
    pthread_mutex_unlock(&g_state_mu);
    if (rfd >= 0) { shutdown(rfd, SHUT_RDWR); close(rfd); }
    if (tfd >= 0) { shutdown(tfd, SHUT_RDWR); close(tfd); }
    if (hfd >= 0) { shutdown(hfd, SHUT_RDWR); close(hfd); }
    pthread_mutex_lock(&g_connq_mu);
    pthread_cond_broadcast(&g_connq_cv);
    pthread_mutex_unlock(&g_connq_mu);
    pthread_mutex_lock(&g_start_mu);
    if (g_start_st == 0) { g_start_st = -1; pthread_cond_broadcast(&g_start_cv); }
    pthread_mutex_unlock(&g_start_mu);
    for (int i = 0; i < 20 && g_started; i++) usleep(10000);
    ht_clear();
    if (svc) (*env)->DeleteGlobalRef(env, svc);
    g_started = 0;
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeSetGamingMode(JNIEnv *env, jclass clazz, jboolean enabled) {
    int mode = enabled ? GLOBAL_MODE_GAMING : GLOBAL_MODE_DAILY;
    atomic_store(&g_global_mode, mode);
    push_log("I", "global_mode=%s", enabled ? "gaming" : "daily");
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeApplyMode(JNIEnv *env, jclass clazz, jboolean enabled) {
    int mode = enabled ? GLOBAL_MODE_GAMING : GLOBAL_MODE_DAILY;
    atomic_store(&g_global_mode, mode);
    connq_clear();
    ht_clear();
    push_log("I", "apply_mode=%s", enabled ? "gaming" : "daily");
}

JNIEXPORT jint JNICALL
Java_com_blacktunnel_BtProxy_nativeGetGamingMode(JNIEnv *env, jclass clazz) {
    return (jint)atomic_load(&g_global_mode);
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

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeSetHotspot(JNIEnv *env, jclass clazz,
                                               jboolean enabled, jint ip_int) {
    int was = atomic_exchange(&g_hotspot_enabled, enabled ? 1 : 0);
    pthread_mutex_lock(&g_state_mu);
    g_hotspot_ip = enabled ? (uint32_t)ip_int : 0;
    int hfd = g_hotspot_fd;
    pthread_mutex_unlock(&g_state_mu);

    if (!enabled && hfd >= 0) {
        pthread_mutex_lock(&g_state_mu);
        g_hotspot_fd = -1;
        pthread_mutex_unlock(&g_state_mu);
        shutdown(hfd, SHUT_RDWR);
        close(hfd);
        push_log("I", "hotspot relay stopped");
    } else if (enabled && !was) {
        push_log("I", "hotspot enabled ip=%u port=%d", (uint32_t)ip_int, HOTSPOT_PORT);
        
        request_tunnel_reset("hotspot enabled");
    }
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeSetNetwork(JNIEnv *env, jclass clazz, jlong network_handle) {
    pthread_mutex_lock(&g_state_mu);
    g_network = (net_handle_t)network_handle;
    pthread_mutex_unlock(&g_state_mu);
    push_log("I", "network_handle=%lld", (long long)network_handle);
}

static JNINativeMethod g_methods[] = {
    { "nativeStart",       "(ILandroid/net/VpnService;Ljava/lang/String;)I",
                           (void*)Java_com_blacktunnel_BtProxy_nativeStart       },
    { "nativeStop",        "()V",
                           (void*)Java_com_blacktunnel_BtProxy_nativeStop        },
    { "nativeDrainLogs",   "()Ljava/lang/String;",
                           (void*)Java_com_blacktunnel_BtProxy_nativeDrainLogs   },
    { "nativeSetGamingMode","(Z)V",
                           (void*)Java_com_blacktunnel_BtProxy_nativeSetGamingMode },
    { "nativeApplyMode",   "(Z)V",
                           (void*)Java_com_blacktunnel_BtProxy_nativeApplyMode   },
    { "nativeGetGamingMode","()I",
                           (void*)Java_com_blacktunnel_BtProxy_nativeGetGamingMode },
    { "nativeSetNetwork",  "(J)V",
                           (void*)Java_com_blacktunnel_BtProxy_nativeSetNetwork  },
    { "nativeSetHotspot",  "(ZI)V",
                           (void*)Java_com_blacktunnel_BtProxy_nativeSetHotspot  },
};

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = (*env)->FindClass(env, "com/blacktunnel/BtProxy");
    if (!cls) return JNI_ERR;
    if ((*env)->RegisterNatives(env, cls,
            g_methods, sizeof(g_methods)/sizeof(g_methods[0])) < 0) return JNI_ERR;
    (*env)->DeleteLocalRef(env, cls);
    return JNI_VERSION_1_6;
}
