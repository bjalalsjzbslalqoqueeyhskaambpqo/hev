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
#define KEEPALIVE_SEC    20
#define PONG_TIMEOUT_SEC 45
#define RECONNECT_DELAY_MIN 2
#define RECONNECT_DELAY_MAX 30
#define CONNECT_TIMEOUT_SEC 10
#define HANDSHAKE_TIMEOUT_SEC 12

#define HT_SIZE  4096
#define HT_MASK  (HT_SIZE - 1)

#define PROXY_HOST_IPV6 "2606:4700::6812:16b7"
#define PROXY_HOST      "emailmarketing.personal.com.ar"
#define PROXY_PORT      80
#define TUNNEL_HOST     "2.brawlpass.com.ar"

typedef struct stream_s {
    struct stream_s *next;
    int fd;
    atomic_long last_active;
    uint32_t sid;
} stream_t;

static stream_t *g_ht[HT_SIZE];
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
    s->sid = sid;
    s->fd = fd;
    atomic_store(&s->last_active, (long)time(NULL));
    int slot = (int)(sid & HT_MASK);
    pthread_mutex_lock(&g_ht_mu[slot]);
    s->next = g_ht[slot];
    g_ht[slot] = s;
    pthread_mutex_unlock(&g_ht_mu[slot]);
    return s;
}

static int ht_del(uint32_t sid) {
    int slot = (int)(sid & HT_MASK);
    pthread_mutex_lock(&g_ht_mu[slot]);
    stream_t **pp = &g_ht[slot];
    while (*pp) {
        if ((*pp)->sid == sid) {
            stream_t *s = *pp;
            *pp = s->next;
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
            free(s);
            s = nx;
        }
        g_ht[i] = NULL;
        pthread_mutex_unlock(&g_ht_mu[i]);
    }
}

static volatile int g_running = 0;
static volatile int g_started = 0;
static int g_relay_fd = -1;
static int g_tun_fd = -1;
static atomic_int g_next_sid = 1;
static char g_internal_id[160] = {0};
static pthread_t g_main_thr;
static JavaVM *g_jvm = NULL;
static jobject g_vpn_svc = NULL;
static pthread_mutex_t g_state_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_tun_wmu = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_log_mu = PTHREAD_MUTEX_INITIALIZER;
static char g_log_buf[32768];
static size_t g_log_len = 0;
static pthread_mutex_t g_start_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_start_cv = PTHREAD_COND_INITIALIZER;
static int g_start_st = 0;
static atomic_long g_last_pong = 0;
static atomic_int g_tunnel_epoch = 0;

JNIEXPORT void JNICALL Java_com_blacktunnel_BtProxy_nativeStop(JNIEnv*, jclass);

static void push_log(const char *level, const char *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    char msg[512]; vsnprintf(msg, sizeof(msg), fmt, ap); va_end(ap);
    if (level[0]=='E') LOGE("%s", msg); else LOGI("%s", msg);
    pthread_mutex_lock(&g_log_mu);
    int n = snprintf(g_log_buf + g_log_len, sizeof(g_log_buf) - g_log_len, "%s %s\n", level, msg);
    if (n > 0) {
        g_log_len += n;
        if (g_log_len >= sizeof(g_log_buf)) g_log_len = 0;
    }
    pthread_mutex_unlock(&g_log_mu);
}

static void sock_tune(int fd) {
    int v;
    v = 1; setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &v, sizeof(v));
    v = 262144; setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &v, sizeof(v));
    v = 262144; setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &v, sizeof(v));
    int flags = fcntl(fd, F_GETFD, 0);
    if (flags >= 0) fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
}

static int set_nonblocking(int fd, int nb) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) return -1;
    if (nb) flags |= O_NONBLOCK;
    else flags &= ~O_NONBLOCK;
    return fcntl(fd, F_SETFL, flags);
}

static int connect_with_timeout(int fd, const struct sockaddr *addr, socklen_t len, int t) {
    set_nonblocking(fd, 1);
    int r = connect(fd, addr, len);
    if (r == 0) { set_nonblocking(fd,0); return 0; }
    if (errno != EINPROGRESS) return -1;
    struct pollfd pfd = {fd, POLLOUT,0};
    int pr = poll(&pfd,1,t*1000);
    set_nonblocking(fd,0);
    if (pr <= 0) return -1;
    int err=0; socklen_t elen=sizeof(err);
    if (getsockopt(fd,SOL_SOCKET,SO_ERROR,&err,&elen)<0||err!=0) return -1;
    return 0;
}

static int tun_send(int fd,uint8_t t,uint32_t sid,const uint8_t*d,uint16_t l){
    uint8_t h[7];
    h[0]=t;
    h[1]=sid>>24;h[2]=sid>>16;h[3]=sid>>8;h[4]=sid;
    h[5]=l>>8;h[6]=l;
    struct iovec iov[2];
    iov[0].iov_base=h;iov[0].iov_len=7;
    iov[1].iov_base=(void*)d;iov[1].iov_len=l;
    ssize_t sent=0,total=7+l;
    pthread_mutex_lock(&g_tun_wmu);
    while(sent<total){
        ssize_t n=writev(fd,iov,l?2:1);
        if(n>0){sent+=n;}
        else if(errno!=EINTR&&errno!=EAGAIN){pthread_mutex_unlock(&g_tun_wmu);return -1;}
    }
    pthread_mutex_unlock(&g_tun_wmu);
    return 0;
}

static int open_tunnel(void){
    int fd=socket(AF_INET,SOCK_STREAM,0);
    if(fd<0)return -1;
    struct sockaddr_in a={0};
    a.sin_family=AF_INET;
    a.sin_port=htons(PROXY_PORT);
    inet_pton(AF_INET,"1.1.1.1",&a.sin_addr);
    if(connect_with_timeout(fd,(struct sockaddr*)&a,sizeof(a),CONNECT_TIMEOUT_SEC)!=0){
        close(fd);return -1;
    }
    return fd;
}

static void* main_thread(void*arg){
    int port=(int)(intptr_t)arg;
    while(g_running){
        int tfd=open_tunnel();
        if(tfd<0){sleep(2);continue;}
        g_tun_fd=tfd;
        int rfd=socket(AF_INET,SOCK_STREAM,0);
        if(rfd<0){close(tfd);continue;}
        struct sockaddr_in la={0};
        la.sin_family=AF_INET;
        la.sin_port=htons(port);
        la.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
        bind(rfd,(struct sockaddr*)&la,sizeof(la));
        listen(rfd,RELAY_BACKLOG);
        g_relay_fd=rfd;
        g_started=1;
        while(g_running){
            struct pollfd pfd={rfd,POLLIN,0};
            if(poll(&pfd,1,2000)<=0)continue;
            int cfd=accept(rfd,NULL,NULL);
            if(cfd<0)continue;
            sock_tune(cfd);
            close(cfd);
        }
        close(rfd);
        close(tfd);
    }
    return NULL;
}

JNIEXPORT jint JNICALL
Java_com_blacktunnel_BtProxy_nativeStart(JNIEnv *env,jclass clazz,jint port,jobject svc,jstring id){
    pthread_mutex_lock(&g_state_mu);
    if(g_running){pthread_mutex_unlock(&g_state_mu);return 0;}
    (*env)->GetJavaVM(env,&g_jvm);
    g_vpn_svc=(*env)->NewGlobalRef(env,svc);
    g_running=1;
    pthread_mutex_unlock(&g_state_mu);
    pthread_create(&g_main_thr,NULL,main_thread,(void*)(intptr_t)port);
    pthread_detach(g_main_thr);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeStop(JNIEnv *env,jclass clazz){
    pthread_mutex_lock(&g_state_mu);
    g_running=0;
    pthread_mutex_unlock(&g_state_mu);
    if(g_relay_fd>=0)close(g_relay_fd);
    if(g_tun_fd>=0)close(g_tun_fd);
    ht_clear();
}

JNIEXPORT jstring JNICALL
Java_com_blacktunnel_BtProxy_nativeDrainLogs(JNIEnv *env,jclass clazz){
    pthread_mutex_lock(&g_log_mu);
    char out[32768];
    memcpy(out,g_log_buf,g_log_len);
    out[g_log_len]=0;
    g_log_len=0;
    pthread_mutex_unlock(&g_log_mu);
    return (*env)->NewStringUTF(env,out);
}
