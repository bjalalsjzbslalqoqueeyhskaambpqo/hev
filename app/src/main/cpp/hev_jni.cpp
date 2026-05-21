#include <dlfcn.h>
#include <jni.h>

typedef int (*hev_main_t)(const char *, int);
typedef void (*hev_quit_t)(void);
typedef void (*hev_stats_t)(size_t *, size_t *, size_t *, size_t *);

static hev_main_t f_m = nullptr;
static hev_quit_t f_q = nullptr;
static hev_stats_t f_s = nullptr;

static bool ld_h() {
    if (f_m) return true;

    void *lib = dlopen("libhev-socks5-tunnel.so", RTLD_NOW | RTLD_GLOBAL);
    if (!lib) return false;

    f_m = (hev_main_t)dlsym(lib, "hev_socks5_tunnel_main_from_file");
    f_q = (hev_quit_t)dlsym(lib, "hev_socks5_tunnel_quit");
    f_s = (hev_stats_t)dlsym(lib, "hev_socks5_tunnel_stats");
    return f_m && f_q;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_blacktunnel_BtVpnService_00024HevBridge_start(JNIEnv *env, jclass, jstring path,
                                                        jint fd) {
    if (!ld_h()) return -1;

    const char *p = env->GetStringUTFChars(path, nullptr);
    int r = f_m(p, fd);
    env->ReleaseStringUTFChars(path, p);
    return r;
}

extern "C" JNIEXPORT void JNICALL
Java_com_blacktunnel_BtVpnService_00024HevBridge_stop(JNIEnv *, jclass) {
    if (f_q) f_q();
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_blacktunnel_BtVpnService_00024HevBridge_stats(JNIEnv *env, jclass) {
    if (!ld_h() || !f_s) return nullptr;
    size_t txp = 0, txb = 0, rxp = 0, rxb = 0;
    f_s(&txp, &txb, &rxp, &rxb);
    jlong out[4] = {(jlong)txp, (jlong)txb, (jlong)rxp, (jlong)rxb};
    jlongArray arr = env->NewLongArray(4);
    if (!arr) return nullptr;
    env->SetLongArrayRegion(arr, 0, 4, out);
    return arr;
}
