#include <dlfcn.h>
#include <jni.h>

typedef int (*hev_main_t)(const char *, int);
typedef void (*hev_quit_t)(void);

static hev_main_t f_m = nullptr;
static hev_quit_t f_q = nullptr;

static bool ld_h() {
    if (f_m) return true;

    void *lib = dlopen("libhev-socks5-tunnel.so", RTLD_NOW | RTLD_GLOBAL);
    if (!lib) return false;

    f_m = (hev_main_t)dlsym(lib, "hev_socks5_tunnel_main_from_file");
    f_q = (hev_quit_t)dlsym(lib, "hev_socks5_tunnel_quit");
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
