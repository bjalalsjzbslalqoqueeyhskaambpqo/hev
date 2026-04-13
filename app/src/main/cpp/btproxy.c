#include <jni.h>
#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <stdatomic.h>
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

#define T_DATA 0x02
#define T_CLOSE 0x03
#define T_PING 0x04
#define T_PONG 0x05
#define T_OPEN 0x01

#define FRAME_HDR 7
#define MAX_PAYLOAD 16384
#define RELAY_BACKLOG 256

#define POLL_TIMEOUT 300
#define KEEPALIVE_SEC 15
#define PONG_TIMEOUT 40

#define HT_SIZE 2048
#define HT_MASK (HT_SIZE - 1)

#define MAX_THREADS 48

typedef struct stream {
    struct stream *next;
    int fd;
    uint32_t sid;
    atomic_long last;
} stream;

static stream *ht[HT_SIZE];
static pthread_mutex_t ht_mu[HT_SIZE];

static atomic_int running = 0;
static atomic_int thread_count = 0;

static int tun_fd = -1;
static int relay_fd = -1;

static atomic_int next_sid = 1;
static atomic_long last_pong = 0;

static pthread_mutex_t tun_mu = PTHREAD_MUTEX_INITIALIZER;

static void ht_init() {
    for (int i = 0; i < HT_SIZE; i++) {
        ht[i] = NULL;
        pthread_mutex_init(&ht_mu[i], NULL);
    }
}

static stream *ht_get(uint32_t sid) {
    int s = sid & HT_MASK;
    pthread_mutex_lock(&ht_mu[s]);
    stream *n = ht[s];
    while (n && n->sid != sid) n = n->next;
    pthread_mutex_unlock(&ht_mu[s]);
    return n;
}

static void ht_put(uint32_t sid, int fd) {
    stream *n = malloc(sizeof(stream));
    if (!n) return;
    n->sid = sid;
    n->fd = fd;
    atomic_store(&n->last, time(NULL));
    int s = sid & HT_MASK;
    pthread_mutex_lock(&ht_mu[s]);
    n->next = ht[s];
    ht[s] = n;
    pthread_mutex_unlock(&ht_mu[s]);
}

static void ht_del(uint32_t sid) {
    int s = sid & HT_MASK;
    pthread_mutex_lock(&ht_mu[s]);
    stream **pp = &ht[s];
    while (*pp) {
        if ((*pp)->sid == sid) {
            stream *d = *pp;
            *pp = d->next;
            pthread_mutex_unlock(&ht_mu[s]);
            close(d->fd);
            free(d);
            return;
        }
        pp = &(*pp)->next;
    }
    pthread_mutex_unlock(&ht_mu[s]);
}

static void sock_tune(int fd) {
    int v = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &v, sizeof(v));
    setsockopt(fd, IPPROTO_TCP, TCP_QUICKACK, &v, sizeof(v));
    v = 262144;
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &v, sizeof(v));
    setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &v, sizeof(v));
}

static int tun_send(uint8_t type, uint32_t sid, uint8_t *data, uint16_t len) {
    uint8_t hdr[7];
    hdr[0]=type;
    hdr[1]=sid>>24; hdr[2]=sid>>16; hdr[3]=sid>>8; hdr[4]=sid;
    hdr[5]=len>>8; hdr[6]=len;

    struct iovec iov[2]={{hdr,7},{data,len}};

    pthread_mutex_lock(&tun_mu);
    writev(tun_fd, iov, len?2:1);
    pthread_mutex_unlock(&tun_mu);
    return 0;
}

typedef struct {
    int fd;
    uint32_t sid;
} conn;

static void *conn_thread(void *arg) {
    conn *c = arg;

    if (atomic_fetch_add(&thread_count,1) > MAX_THREADS) {
        close(c->fd);
        free(c);
        atomic_fetch_sub(&thread_count,1);
        return NULL;
    }

    ht_put(c->sid,c->fd);

    uint8_t buf[MAX_PAYLOAD];

    struct pollfd p={c->fd,POLLIN,0};
    if (poll(&p,1,2000)<=0) goto end;

    int n=recv(c->fd,buf,sizeof(buf),0);
    if (n<=0) goto end;

    tun_send(T_OPEN,c->sid,buf,n);

    while(running){
        struct pollfd pf={c->fd,POLLIN,0};
        int pr=poll(&pf,1,POLL_TIMEOUT);
        if(pr<=0) continue;

        n=recv(c->fd,buf,sizeof(buf),0);
        if(n<=0) break;

        tun_send(T_DATA,c->sid,buf,n);
    }

end:
    tun_send(T_CLOSE,c->sid,NULL,0);
    ht_del(c->sid);
    free(c);
    atomic_fetch_sub(&thread_count,1);
    return NULL;
}

static void *reader(void *arg) {
    uint8_t hdr[7],buf[MAX_PAYLOAD];

    while(running){
        int r=recv(tun_fd,hdr,7,0);
        if(r<=0) break;

        uint32_t sid=(hdr[1]<<24)|(hdr[2]<<16)|(hdr[3]<<8)|hdr[4];
        uint16_t len=(hdr[5]<<8)|hdr[6];

        if(len) recv(tun_fd,buf,len,0);

        if(hdr[0]==T_DATA){
            stream *s=ht_get(sid);
            if(s) send(s->fd,buf,len,MSG_DONTWAIT);
        }else if(hdr[0]==T_CLOSE){
            ht_del(sid);
        }else if(hdr[0]==T_PING){
            tun_send(T_PONG,0,NULL,0);
        }else if(hdr[0]==T_PONG){
            atomic_store(&last_pong,time(NULL));
        }
    }
    return NULL;
}

static void *keepalive(void *arg){
    while(running){
        sleep(KEEPALIVE_SEC);
        tun_send(T_PING,0,NULL,0);
    }
    return NULL;
}

JNIEXPORT jint JNICALL
Java_com_blacktunnel_BtProxy_nativeStart(JNIEnv *env,jclass c,jint port,jobject svc,jstring id){
    if(running) return 0;

    running=1;
    ht_init();

    tun_fd=socket(AF_INET,SOCK_STREAM,0);

    pthread_t t1,t2;
    pthread_create(&t1,NULL,reader,NULL);
    pthread_detach(t1);

    pthread_create(&t2,NULL,keepalive,NULL);
    pthread_detach(t2);

    relay_fd=socket(AF_INET,SOCK_STREAM,0);

    struct sockaddr_in a={0};
    a.sin_family=AF_INET;
    a.sin_port=htons(port);
    a.sin_addr.s_addr=htonl(INADDR_LOOPBACK);

    bind(relay_fd,(struct sockaddr*)&a,sizeof(a));
    listen(relay_fd,RELAY_BACKLOG);

    while(running){
        int cfd=accept(relay_fd,NULL,NULL);
        if(cfd<0) continue;

        sock_tune(cfd);

        uint32_t sid=atomic_fetch_add(&next_sid,1);

        conn *cc=malloc(sizeof(conn));
        cc->fd=cfd;
        cc->sid=sid;

        pthread_t th;
        pthread_create(&th,NULL,conn_thread,cc);
        pthread_detach(th);
    }

    return 0;
}

JNIEXPORT void JNICALL
Java_com_blacktunnel_BtProxy_nativeStop(JNIEnv *env,jclass c){
    running=0;
    if(relay_fd>=0) close(relay_fd);
    if(tun_fd>=0) close(tun_fd);
}
