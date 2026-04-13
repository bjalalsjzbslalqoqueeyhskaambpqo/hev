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
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define T_DATA 0x02
#define T_CLOSE 0x03
#define T_PING 0x04
#define T_PONG 0x05
#define T_OPEN 0x01

#define FRAME_HDR 7
#define MAX_PAYLOAD 16384
#define RELAY_BACKLOG 256
#define IDLE_SECS 60
#define WD_INTERVAL 10
#define KEEPALIVE_SEC 15
#define PONG_TIMEOUT_SEC 40
#define CONNECT_TIMEOUT_SEC 8
#define HANDSHAKE_TIMEOUT_SEC 10

#define HT_SIZE 4096
#define HT_MASK (HT_SIZE - 1)
#define MAX_CONN_THREADS 64

typedef struct stream_s {
    struct stream_s *next;
    int fd;
    atomic_long last_active;
    uint32_t sid;
} stream_t;

static stream_t *g_ht[HT_SIZE];
static pthread_mutex_t g_ht_mu[HT_SIZE];

static atomic_int g_running = 0;
static atomic_int g_thread_count = 0;
static int g_relay_fd = -1;
static int g_tun_fd = -1;
static atomic_int g_next_sid = 1;
static atomic_long g_last_pong = 0;
static atomic_int g_tunnel_epoch = 0;

static pthread_mutex_t g_tun_wmu = PTHREAD_MUTEX_INITIALIZER;

static void ht_init() {
    for (int i = 0; i < HT_SIZE; i++) {
        g_ht[i] = NULL;
        pthread_mutex_init(&g_ht_mu[i], NULL);
    }
}

static stream_t *ht_get(uint32_t sid) {
    int slot = sid & HT_MASK;
    pthread_mutex_lock(&g_ht_mu[slot]);
    stream_t *s = g_ht[slot];
    while (s && s->sid != sid) s = s->next;
    pthread_mutex_unlock(&g_ht_mu[slot]);
    return s;
}

static stream_t *ht_put(uint32_t sid, int fd) {
    stream_t *s = malloc(sizeof(stream_t));
    if (!s) return NULL;
    s->sid = sid;
    s->fd = fd;
    atomic_store(&s->last_active, time(NULL));
    int slot = sid & HT_MASK;
    pthread_mutex_lock(&g_ht_mu[slot]);
    s->next = g_ht[slot];
    g_ht[slot] = s;
    pthread_mutex_unlock(&g_ht_mu[slot]);
    return s;
}

static void ht_del(uint32_t sid) {
    int slot = sid & HT_MASK;
    pthread_mutex_lock(&g_ht_mu[slot]);
    stream_t **pp = &g_ht[slot];
    while (*pp) {
        if ((*pp)->sid == sid) {
            stream_t *s = *pp;
            *pp = s->next;
            pthread_mutex_unlock(&g_ht_mu[slot]);
            close(s->fd);
            free(s);
            return;
        }
        pp = &(*pp)->next;
    }
    pthread_mutex_unlock(&g_ht_mu[slot]);
}

static void sock_tune(int fd) {
    int v = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &v, sizeof(v));
    setsockopt(fd, IPPROTO_TCP, TCP_QUICKACK, &v, sizeof(v));
    v = 262144;
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &v, sizeof(v));
    setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &v, sizeof(v));
}

static int connect_timeout(int fd, struct sockaddr *addr, socklen_t len) {
    fcntl(fd, F_SETFL, O_NONBLOCK);
    int r = connect(fd, addr, len);
    if (r == 0) return 0;
    struct pollfd p = {fd, POLLOUT, 0};
    if (poll(&p, 1, CONNECT_TIMEOUT_SEC * 1000) <= 0) return -1;
    int err;
    socklen_t l = sizeof(err);
    getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &l);
    return err;
}

static int tun_send(int fd, uint8_t type, uint32_t sid, uint8_t *data, uint16_t len) {
    uint8_t hdr[7];
    hdr[0] = type;
    hdr[1] = sid >> 24;
    hdr[2] = sid >> 16;
    hdr[3] = sid >> 8;
    hdr[4] = sid;
    hdr[5] = len >> 8;
    hdr[6] = len;

    struct iovec iov[2] = {
        {hdr, 7},
        {data, len}
    };

    pthread_mutex_lock(&g_tun_wmu);
    writev(fd, iov, len ? 2 : 1);
    pthread_mutex_unlock(&g_tun_wmu);
    return 0;
}

typedef struct {
    int cfd;
    int tfd;
    uint32_t sid;
} conn_t;

static void *conn_thread(void *arg) {
    conn_t *c = arg;
    if (atomic_fetch_add(&g_thread_count, 1) > MAX_CONN_THREADS) {
        close(c->cfd);
        free(c);
        atomic_fetch_sub(&g_thread_count, 1);
        return NULL;
    }

    stream_t *s = ht_put(c->sid, c->cfd);
    if (!s) {
        close(c->cfd);
        free(c);
        atomic_fetch_sub(&g_thread_count, 1);
        return NULL;
    }

    uint8_t buf[MAX_PAYLOAD];

    struct pollfd p = {c->cfd, POLLIN, 0};
    if (poll(&p, 1, 2000) <= 0) {
        ht_del(c->sid);
        free(c);
        atomic_fetch_sub(&g_thread_count, 1);
        return NULL;
    }

    ssize_t n = recv(c->cfd, buf, sizeof(buf), 0);
    if (n <= 0) {
        ht_del(c->sid);
        free(c);
        atomic_fetch_sub(&g_thread_count, 1);
        return NULL;
    }

    tun_send(c->tfd, T_OPEN, c->sid, buf, n);

    while (g_running) {
        struct pollfd pfd = {c->cfd, POLLIN, 0};
        int pr = poll(&pfd, 1, 500);
        if (pr <= 0) continue;

        n = recv(c->cfd, buf, sizeof(buf), 0);
        if (n <= 0) break;

        tun_send(c->tfd, T_DATA, c->sid, buf, n);
    }

    tun_send(c->tfd, T_CLOSE, c->sid, NULL, 0);
    ht_del(c->sid);
    free(c);
    atomic_fetch_sub(&g_thread_count, 1);
    return NULL;
}

static void *tunnel_reader(void *arg) {
    int tfd = *(int*)arg;
    uint8_t hdr[7], payload[MAX_PAYLOAD];

    while (g_running) {
        if (recv(tfd, hdr, 7, 0) <= 0) break;

        uint32_t sid = (hdr[1]<<24)|(hdr[2]<<16)|(hdr[3]<<8)|hdr[4];
        uint16_t len = (hdr[5]<<8)|hdr[6];

        if (len) recv(tfd, payload, len, 0);

        if (hdr[0] == T_DATA) {
            stream_t *s = ht_get(sid);
            if (s) send(s->fd, payload, len, MSG_DONTWAIT);
        } else if (hdr[0] == T_CLOSE) {
            ht_del(sid);
        } else if (hdr[0] == T_PING) {
            tun_send(tfd, T_PONG, 0, NULL, 0);
        } else if (hdr[0] == T_PONG) {
            atomic_store(&g_last_pong, time(NULL));
        }
    }

    return NULL;
}

static void *keepalive(void *arg) {
    int tfd = *(int*)arg;

    while (g_running) {
        sleep(KEEPALIVE_SEC);
        tun_send(tfd, T_PING, 0, NULL, 0);
    }

    return NULL;
}

JNIEXPORT jint JNICALL
Java_com_blacktunnel_BtProxy_nativeStart(JNIEnv *env, jclass cls, jint port, jobject svc, jstring id) {
    if (g_running) return 0;

    g_running = 1;
    ht_init();

    int tfd = socket(AF_INET, SOCK_STREAM, 0);
    g_tun_fd = tfd;

    pthread_t tr, tk;
    pthread_create(&tr, NULL, tunnel_reader, &tfd);
    pthread_detach(tr);

    pthread_create(&tk, NULL, keepalive, &tfd);
    pthread_detach(tk);

    int rfd = socket(AF_INET, SOCK_STREAM, 0);
    g_relay_fd = rfd;

    struct sockaddr_in a = {0};
    a.sin_family = AF_INET;
    a.sin_port = htons(port);
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    bind(rfd, (struct sockaddr*)&a, sizeof(a));
    listen(rfd, RELAY_BACKLOG);

    while (g_running) {
        int cfd = accept(rfd, NULL, NULL);
        if (cfd < 0) continue;

        sock_tune(cfd);

        uint32_t sid = atomic_fetch_add(&g_next_sid, 1);

        conn_t *c = malloc(sizeof(conn_t));
        c->cfd = cfd;
        c->tfd = tfd;
        c->sid = sid;

        pthread_t th;
        pthread_create(&th, NULL, conn_thread, c);
        pthread_detach(th);
    }

    return 0;
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeStop(JNIEnv *env, jclass cls) {
    g_running = 0;
    if (g_relay_fd >= 0) close(g_relay_fd);
    if (g_tun_fd >= 0) close(g_tun_fd);
}
