#include <jni.h>
#include <android/log.h>

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
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
#include <sys/types.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>

#define LOG_TAG "BtProxy"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)


#define T_OPEN  0x01
#define T_DATA  0x02
#define T_CLOSE 0x03
#define T_PING  0x04
#define T_PONG  0x05

#define FRAME_HDR   7
#define MAX_PAYLOAD 16384
#define FRAME_MAX   (FRAME_HDR + MAX_PAYLOAD)


#define PROXY_HOST_IPV6 "2606:4700::6812:16b7"
#define PROXY_HOST      "emailmarketing.personal.com.ar"
#define PROXY_PORT      80
#define TUNNEL_HOST     "3.brawlpass.com.ar"


#define RELAY_BACKLOG   512
#define EPOLL_MAX_EVT   64
#define IDLE_SECS       600
#define WD_INTERVAL_SEC 60
#define KEEPALIVE_SEC   90
#define MAX_STREAMS     2048


#define POOL_SIZE 256
#define BUF_SIZE  FRAME_MAX

typedef struct buf_node {
    struct buf_node *next;
    uint8_t          data[BUF_SIZE];
} buf_node_t;

static buf_node_t          *g_pool      = NULL;
static pthread_spinlock_t   g_pool_lock;

static uint8_t *pool_get(void) {
    pthread_spin_lock(&g_pool_lock);
    buf_node_t *n = g_pool;
    if (n) g_pool = n->next;
    pthread_spin_unlock(&g_pool_lock);
    if (!n) n = malloc(sizeof(buf_node_t));
    return n ? n->data : NULL;
}

static void pool_put(uint8_t *data) {
    buf_node_t *n = (buf_node_t *)data;
    pthread_spin_lock(&g_pool_lock);
    n->next = g_pool;
    g_pool  = n;
    pthread_spin_unlock(&g_pool_lock);
}


typedef struct {
    int      fd;
    int64_t  last_active;
    uint32_t sid;
    uint8_t  used;
} stream_t;

static stream_t     g_streams[MAX_STREAMS];
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
            g_streams[i].sid         = sid;
            g_streams[i].fd          = fd;
            g_streams[i].last_active = (int64_t)time(NULL);
            g_streams[i].used        = 1;
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


static volatile int   g_running    = 0;
static volatile int   g_started    = 0;
static int            g_relay_fd   = -1;
static int            g_tun_fd     = -1;
static int            g_epoll_fd   = -1;
static atomic_int     g_next_sid   = 1;
static pthread_t      g_main_thread;
static JavaVM        *g_jvm        = NULL;
static jobject        g_vpn_svc    = NULL;
static pthread_mutex_t g_logs_mu   = PTHREAD_MUTEX_INITIALIZER;
static char            g_logs_buf[32768];
static size_t          g_logs_len   = 0;

static void push_logf(const char *level, const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    char msg[512];
    vsnprintf(msg, sizeof(msg), fmt, args);
    va_end(args);

    if (strcmp(level, "E") == 0) LOGE("%s", msg);
    else LOGI("%s", msg);

    pthread_mutex_lock(&g_logs_mu);
    char line[560];
    int n = snprintf(line, sizeof(line), "%s %s\n", level, msg);
    if (n > 0) {
        size_t need = (size_t)n;
        if (need >= sizeof(g_logs_buf)) {
            memcpy(g_logs_buf, line + (need - sizeof(g_logs_buf) + 1), sizeof(g_logs_buf) - 1);
            g_logs_buf[sizeof(g_logs_buf) - 1] = '\0';
            g_logs_len = sizeof(g_logs_buf) - 1;
        } else {
            if (g_logs_len + need >= sizeof(g_logs_buf)) {
                size_t drop = (g_logs_len + need) - (sizeof(g_logs_buf) - 1);
                memmove(g_logs_buf, g_logs_buf + drop, g_logs_len - drop);
                g_logs_len -= drop;
            }
            memcpy(g_logs_buf + g_logs_len, line, need);
            g_logs_len += need;
            g_logs_buf[g_logs_len] = '\0';
        }
    }
    pthread_mutex_unlock(&g_logs_mu);
}


static void set_nonblock(int fd) {
    int fl = fcntl(fd, F_GETFL, 0);
    if (fl >= 0) fcntl(fd, F_SETFL, fl | O_NONBLOCK);
}

static void set_nodelay(int fd) {
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
}

static void set_buffers(int fd) {
    int sz = 131072;
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &sz, sizeof(sz));
    setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &sz, sizeof(sz));
}

static void set_keepalive(int fd) {
    int one = 1, idle = 30, intvl = 5, cnt = 3;
    setsockopt(fd, SOL_SOCKET,  SO_KEEPALIVE,   &one,   sizeof(one));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE,   &idle,  sizeof(idle));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL,  &intvl, sizeof(intvl));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT,    &cnt,   sizeof(cnt));
}


static inline void frame_hdr(uint8_t *buf, uint8_t type, uint32_t sid, uint16_t len) {
    buf[0] = type;
    buf[1] = (sid >> 24) & 0xFF;
    buf[2] = (sid >> 16) & 0xFF;
    buf[3] = (sid >>  8) & 0xFF;
    buf[4] =  sid        & 0xFF;
    buf[5] = (len >>  8) & 0xFF;
    buf[6] =  len        & 0xFF;
}


static int tun_send_frame(int tfd, uint8_t type, uint32_t sid, const uint8_t *data, uint16_t dlen) {
    uint8_t hdr[FRAME_HDR];
    frame_hdr(hdr, type, sid, dlen);
    struct iovec iov[2];
    iov[0].iov_base = hdr;
    iov[0].iov_len  = FRAME_HDR;
    iov[1].iov_base = (void *)data;
    iov[1].iov_len  = dlen;
    int niov = (dlen > 0) ? 2 : 1;
    ssize_t total = FRAME_HDR + dlen;
    ssize_t sent  = 0;
    while (sent < total) {
        ssize_t n = writev(tfd, iov, niov);
        if (n > 0) {
            sent += n;
            if (sent < total) {
                size_t skip = (size_t)n;
                for (int i = 0; i < niov && skip > 0; i++) {
                    if (skip >= iov[i].iov_len) {
                        skip -= iov[i].iov_len;
                        iov[i].iov_len = 0;
                    } else {
                        iov[i].iov_base = (uint8_t *)iov[i].iov_base + skip;
                        iov[i].iov_len -= skip;
                        skip = 0;
                    }
                }
            }
        } else if (n < 0 && (errno == EAGAIN || errno == EINTR)) {
            continue;
        } else {
            return -1;
        }
    }
    return 0;
}


static int read_full(int fd, uint8_t *buf, int len) {
    int off = 0;
    while (off < len) {
        ssize_t n = recv(fd, buf + off, len - off, MSG_WAITALL);
        if (n > 0)       { off += (int)n; }
        else if (n == 0) { return -1; }
        else if (errno == EINTR) { continue; }
        else { return -1; }
    }
    return 0;
}


static void jni_protect(int fd) {
    if (!g_jvm || !g_vpn_svc) return;
    JNIEnv *env = NULL;
    int attached = 0;
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
        attached = 1;
    }
    jclass   cls = (*env)->GetObjectClass(env, g_vpn_svc);
    jmethodID m  = (*env)->GetMethodID(env, cls, "protect", "(I)Z");
    if (m) (*env)->CallBooleanMethod(env, g_vpn_svc, m, fd);
    (*env)->DeleteLocalRef(env, cls);
    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
}


static int open_tunnel_socket(void) {
    int fd = -1;

    
    struct sockaddr_in6 a6 = {0};
    a6.sin6_family = AF_INET6;
    a6.sin6_port   = htons(PROXY_PORT);
    if (inet_pton(AF_INET6, PROXY_HOST_IPV6, &a6.sin6_addr) == 1) {
        fd = socket(AF_INET6, SOCK_STREAM, 0);
        if (fd >= 0) {
            jni_protect(fd);
            set_nodelay(fd); set_buffers(fd); set_keepalive(fd);
            if (connect(fd, (struct sockaddr *)&a6, sizeof(a6)) != 0) {
                close(fd); fd = -1;
            } else {
                push_logf("I", "tunnel IPv6 OK");
            }
        }
    }

    
    if (fd < 0) {
        struct addrinfo hints = {0}, *res = NULL;
        hints.ai_family   = AF_UNSPEC;
        hints.ai_socktype = SOCK_STREAM;
        char port_str[8]; snprintf(port_str, sizeof(port_str), "%d", PROXY_PORT);
        if (getaddrinfo(PROXY_HOST, port_str, &hints, &res) == 0) {
            for (struct addrinfo *r = res; r && fd < 0; r = r->ai_next) {
                int s = socket(r->ai_family, SOCK_STREAM, 0);
                if (s < 0) continue;
                jni_protect(s);
                set_nodelay(s); set_buffers(s); set_keepalive(s);
                if (connect(s, r->ai_addr, r->ai_addrlen) == 0) {
                    fd = s;
                    push_logf("I", "tunnel DNS OK");
                } else {
                    close(s);
                }
            }
            freeaddrinfo(res);
        }
    }

    if (fd < 0) { push_logf("E", "tunnel connect failed"); return -1; }

    
    char req1[256];
    int  rlen1 = snprintf(req1, sizeof(req1), "GET / HTTP/1.1\r\nHost: %s\r\n\r\n", PROXY_HOST);
    ssize_t ws1 = 0;
    while (ws1 < rlen1) {
        ssize_t n = send(fd, req1 + ws1, rlen1 - ws1, MSG_NOSIGNAL);
        if (n > 0) ws1 += n;
        else if (errno != EINTR) { close(fd); return -1; }
    }

    usleep(5000);

    char req2[256];
    int  rlen2 = snprintf(req2, sizeof(req2), "- / HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nAction: tunnel\r\n\r\n", TUNNEL_HOST);
    ssize_t ws2 = 0;
    while (ws2 < rlen2) {
        ssize_t n = send(fd, req2 + ws2, rlen2 - ws2, MSG_NOSIGNAL);
        if (n > 0) ws2 += n;
        else if (errno != EINTR) { close(fd); return -1; }
    }

    char resp[4096];
    int roff = 0;
    int blocks = 0;
    int saw_101 = 0;
    struct timeval tv = {8, 0};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    while (blocks < 2 && roff < (int)sizeof(resp) - 1) {
        ssize_t n = recv(fd, resp + roff, sizeof(resp) - 1 - roff, 0);
        if (n <= 0) break;
        roff += (int)n;
        resp[roff] = '\0';
        char *p = resp;
        while ((p = strstr(p, "\r\n\r\n")) != NULL) {
            blocks++;
            char saved = *p;
            *p = '\0';
            if (strstr(resp, "101")) saw_101 = 1;
            *p = saved;
            p += 4;
        }
    }
    tv.tv_sec = 0; setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    if (!saw_101 && !strstr(resp, "101")) { push_logf("E", "handshake without 101"); close(fd); return -1; }

    push_logf("I", "tunnel handshake OK");
    return fd;
}


static int socks5_server_handshake(int cfd, uint8_t *dest_out, int *dest_len_out) {
    uint8_t buf[512];

    
    if (read_full(cfd, buf, 2) < 0) return -1;
    int nm = buf[1];
    if (nm > 0 && read_full(cfd, buf + 2, nm) < 0) return -1;
    uint8_t rep[2] = {0x05, 0x00};
    if (send(cfd, rep, 2, MSG_NOSIGNAL) != 2) return -1;

    
    if (read_full(cfd, buf, 4) < 0) return -1;
    
    int at = buf[3];
    uint8_t dest[256]; int dlen = 0;
    dest[dlen++] = (uint8_t)at;
    if (at == 0x01) {
        if (read_full(cfd, dest + dlen, 4) < 0) return -1;
        dlen += 4;
    } else if (at == 0x03) {
        if (read_full(cfd, dest + dlen, 1) < 0) return -1;
        int l = dest[dlen++];
        if (read_full(cfd, dest + dlen, l) < 0) return -1;
        dlen += l;
    } else if (at == 0x04) {
        if (read_full(cfd, dest + dlen, 16) < 0) return -1;
        dlen += 16;
    } else {
        return -1;
    }
    if (read_full(cfd, dest + dlen, 2) < 0) return -1;
    dlen += 2;

    
    uint8_t ok[10] = {0x05,0x00,0x00,0x01,0,0,0,0,0,0};
    if (send(cfd, ok, sizeof(ok), MSG_NOSIGNAL) != sizeof(ok)) return -1;

    memcpy(dest_out, dest, dlen);
    *dest_len_out = dlen;
    return 0;
}


typedef struct {
    int      cfd;
    int      tfd;
    uint32_t sid;
} conn_args_t;

static void *conn_thread(void *arg) {
    conn_args_t *ca  = (conn_args_t *)arg;
    int          cfd = ca->cfd;
    int          tfd = ca->tfd;
    uint32_t     sid = ca->sid;
    free(ca);

    
    pthread_mutex_lock(&g_streams_mu);
    stream_t *s = stream_alloc(sid, cfd);
    pthread_mutex_unlock(&g_streams_mu);
    if (!s) { close(cfd); return NULL; }

    
    uint8_t dest[260]; int dest_len = 0;
    if (socks5_server_handshake(cfd, dest, &dest_len) < 0) {
        pthread_mutex_lock(&g_streams_mu);
        stream_free(s);
        pthread_mutex_unlock(&g_streams_mu);
        return NULL;
    }

    
    if (tun_send_frame(tfd, T_OPEN, sid, dest, (uint16_t)dest_len) < 0) {
        pthread_mutex_lock(&g_streams_mu);
        stream_free(s);
        pthread_mutex_unlock(&g_streams_mu);
        return NULL;
    }

    
    uint8_t buf[MAX_PAYLOAD];
    while (g_running) {
        ssize_t n = recv(cfd, buf, sizeof(buf), 0);
        if (n <= 0) break;
        pthread_mutex_lock(&g_streams_mu);
        if (s->used) s->last_active = (int64_t)time(NULL);
        pthread_mutex_unlock(&g_streams_mu);
        if (tun_send_frame(tfd, T_DATA, sid, buf, (uint16_t)n) < 0) break;
    }

    tun_send_frame(tfd, T_CLOSE, sid, NULL, 0);
    pthread_mutex_lock(&g_streams_mu);
    stream_free(s);
    pthread_mutex_unlock(&g_streams_mu);
    return NULL;
}


static void *tunnel_reader(void *arg) {
    int tfd = *(int *)arg;
    uint8_t hdr[FRAME_HDR];
    uint8_t payload[MAX_PAYLOAD];

    while (g_running) {
        if (read_full(tfd, hdr, FRAME_HDR) < 0) break;
        uint8_t  ft  = hdr[0];
        uint32_t sid = ((uint32_t)hdr[1] << 24) | ((uint32_t)hdr[2] << 16)
                     | ((uint32_t)hdr[3] <<  8) |  (uint32_t)hdr[4];
        uint16_t len = ((uint16_t)hdr[5] << 8) | hdr[6];
        if (len > MAX_PAYLOAD) break;
        if (len > 0 && read_full(tfd, payload, len) < 0) break;

        if (ft == T_DATA) {
            pthread_mutex_lock(&g_streams_mu);
            stream_t *s = stream_find(sid);
            int cfd = s ? s->fd : -1;
            if (s && s->used) s->last_active = (int64_t)time(NULL);
            pthread_mutex_unlock(&g_streams_mu);
            if (cfd >= 0 && len > 0) {
                
                ssize_t off = 0;
                while (off < len) {
                    ssize_t n = send(cfd, payload + off, len - off, MSG_NOSIGNAL);
                    if (n > 0) off += n;
                    else if (errno == EINTR) continue;
                    else break;
                }
            }
        } else if (ft == T_CLOSE) {
            pthread_mutex_lock(&g_streams_mu);
            stream_t *s = stream_find(sid);
            if (s) stream_free(s);
            pthread_mutex_unlock(&g_streams_mu);
        } else if (ft == T_PONG) {
            
        }
    }
    return NULL;
}


static void *keepalive_thread(void *arg) {
    int tfd = *(int *)arg;
    while (g_running) {
        sleep(KEEPALIVE_SEC);
        if (!g_running) break;
        uint8_t hdr[FRAME_HDR];
        frame_hdr(hdr, T_PING, 0, 0);
        ssize_t n = send(tfd, hdr, FRAME_HDR, MSG_NOSIGNAL);
        if (n < 0) break;
    }
    return NULL;
}


static void *watchdog_thread(void *arg) {
    int tfd = *(int *)arg;
    while (g_running) {
        sleep(WD_INTERVAL_SEC);
        if (!g_running) break;
        int64_t now = (int64_t)time(NULL);
        pthread_mutex_lock(&g_streams_mu);
        for (int i = 0; i < MAX_STREAMS; i++) {
            if (!g_streams[i].used) continue;
            if (now - g_streams[i].last_active > IDLE_SECS) {
                uint32_t sid = g_streams[i].sid;
                stream_free(&g_streams[i]);
                pthread_mutex_unlock(&g_streams_mu);
                tun_send_frame(tfd, T_CLOSE, sid, NULL, 0);
                pthread_mutex_lock(&g_streams_mu);
            }
        }
        pthread_mutex_unlock(&g_streams_mu);
    }
    return NULL;
}


static void *main_thread(void *arg) {
    int socks5_port = (int)(intptr_t)arg;

    
    int tfd = open_tunnel_socket();
    if (tfd < 0) return NULL;
    g_tun_fd = tfd;

    
    int rfd = socket(AF_INET, SOCK_STREAM, 0);
    if (rfd < 0) { close(tfd); return NULL; }
    int one = 1;
    setsockopt(rfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    set_nodelay(rfd);
    struct sockaddr_in la = {0};
    la.sin_family      = AF_INET;
    la.sin_port        = htons(socks5_port);
    la.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(rfd, (struct sockaddr *)&la, sizeof(la)) < 0 ||
        listen(rfd, RELAY_BACKLOG) < 0) {
        close(rfd); close(tfd); return NULL;
    }
    g_relay_fd = rfd;
    g_started = 1;
    push_logf("I", "relay listening on 127.0.0.1:%d", socks5_port);

    
    pthread_t trd, tka, twd;
    pthread_create(&trd, NULL, tunnel_reader,  &g_tun_fd);
    pthread_create(&tka, NULL, keepalive_thread, &g_tun_fd);
    pthread_create(&twd, NULL, watchdog_thread,  &g_tun_fd);
    pthread_detach(trd); pthread_detach(tka); pthread_detach(twd);

    
    while (g_running) {
        struct sockaddr_in ca; socklen_t cl = sizeof(ca);
        int cfd = accept(rfd, (struct sockaddr *)&ca, &cl);
        if (cfd < 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            break;
        }
        set_nodelay(cfd);
        set_buffers(cfd);

        uint32_t sid;
        do { sid = (uint32_t)atomic_fetch_add(&g_next_sid, 1) & 0x7FFFFFFF; } while (sid == 0);

        conn_args_t *ca2 = malloc(sizeof(conn_args_t));
        if (!ca2) { close(cfd); continue; }
        ca2->cfd = cfd;
        ca2->tfd = tfd;
        ca2->sid = sid;

        pthread_t ct;
        if (pthread_create(&ct, NULL, conn_thread, ca2) != 0) {
            free(ca2); close(cfd);
        } else {
            pthread_detach(ct);
        }
    }

    close(rfd); g_relay_fd = -1;
    close(tfd); g_tun_fd   = -1;
    g_started = 0;
    return NULL;
}


JNIEXPORT jint JNICALL
Java_com_blacktunnel_BtProxy_nativeStart(JNIEnv *env, jclass clazz,
                                          jint socks5_port, jobject vpn_svc) {
    if (g_running) {
        push_logf("I", "nativeStart ignored: already running");
        return 0;
    }

    (*env)->GetJavaVM(env, &g_jvm);
    g_vpn_svc = (*env)->NewGlobalRef(env, vpn_svc);

    pthread_spin_init(&g_pool_lock, PTHREAD_PROCESS_PRIVATE);
    
    for (int i = 0; i < POOL_SIZE; i++) {
        buf_node_t *n = malloc(sizeof(buf_node_t));
        if (n) { n->next = g_pool; g_pool = n; }
    }

    memset(g_streams, 0, sizeof(g_streams));
    for (int i = 0; i < MAX_STREAMS; i++) g_streams[i].fd = -1;

    g_running = 1;
    g_started = 0;
    atomic_store(&g_next_sid, 1);

    if (pthread_create(&g_main_thread, NULL, main_thread, (void *)(intptr_t)socks5_port) != 0) {
        g_running = 0;
        if (g_vpn_svc) { (*env)->DeleteGlobalRef(env, g_vpn_svc); g_vpn_svc = NULL; }
        push_logf("E", "failed to create main thread");
        return -1;
    }
    pthread_detach(g_main_thread);
    push_logf("I", "nativeStart ok");
    return 0;
}


JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeStop(JNIEnv *env, jclass clazz) {
    push_logf("I", "nativeStop begin");
    g_running = 0;
    if (g_relay_fd >= 0) { close(g_relay_fd); g_relay_fd = -1; }
    if (g_tun_fd   >= 0) { close(g_tun_fd);   g_tun_fd   = -1; }
    for (int i = 0; i < 50 && g_started; i++) usleep(20000);

    pthread_mutex_lock(&g_streams_mu);
    for (int i = 0; i < MAX_STREAMS; i++)
        if (g_streams[i].used) stream_free(&g_streams[i]);
    pthread_mutex_unlock(&g_streams_mu);

    if (g_vpn_svc) { (*env)->DeleteGlobalRef(env, g_vpn_svc); g_vpn_svc = NULL; }
    g_jvm = NULL;

    
    pthread_spin_lock(&g_pool_lock);
    buf_node_t *n = g_pool; g_pool = NULL;
    pthread_spin_unlock(&g_pool_lock);
    while (n) { buf_node_t *nx = n->next; free(n); n = nx; }
    pthread_spin_destroy(&g_pool_lock);
    push_logf("I", "nativeStop done");
}

JNIEXPORT jstring JNICALL
Java_com_blacktunnel_BtProxy_nativeDrainLogs(JNIEnv *env, jclass clazz) {
    pthread_mutex_lock(&g_logs_mu);
    if (g_logs_len == 0) {
        pthread_mutex_unlock(&g_logs_mu);
        return (*env)->NewStringUTF(env, "");
    }
    char out[32768];
    memcpy(out, g_logs_buf, g_logs_len);
    out[g_logs_len] = '\0';
    g_logs_len = 0;
    g_logs_buf[0] = '\0';
    pthread_mutex_unlock(&g_logs_mu);
    return (*env)->NewStringUTF(env, out);
}
