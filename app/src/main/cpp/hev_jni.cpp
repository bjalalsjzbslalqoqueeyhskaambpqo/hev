#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#define TAG "HevJNI"

typedef int (*hev_main_t)(const char *, int);
typedef void (*hev_quit_t)(void);
typedef void (*hev_stats_t)(size_t *, size_t *, size_t *, size_t *);

static hev_main_t fn_main = nullptr;
static hev_quit_t fn_quit = nullptr;
static hev_stats_t fn_stats = nullptr;

static bool load_hev() {
    if (fn_main) return true;

    void *lib = dlopen("libhev-socks5-tunnel.so", RTLD_NOW | RTLD_GLOBAL);
    if (!lib) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "dlopen failed: %s", dlerror());
        return false;
    }

    fn_main = (hev_main_t)dlsym(lib, "hev_socks5_tunnel_main_from_file");
    fn_quit = (hev_quit_t)dlsym(lib, "hev_socks5_tunnel_quit");
    fn_stats = (hev_stats_t)dlsym(lib, "hev_socks5_tunnel_stats");
    return fn_main && fn_quit && fn_stats;
}

extern "C" JNIEXPORT jint JNICALL Java_com_blacktunnel_HevBridge_start(JNIEnv *env, jclass,
                                                                          jstring path,
                                                                          jint fd) {
    if (!load_hev()) return -1;

    const char *p = env->GetStringUTFChars(path, nullptr);
    __android_log_print(ANDROID_LOG_INFO, TAG, "start fd=%d config=%s", fd, p);
    int r = fn_main(p, fd);
    env->ReleaseStringUTFChars(path, p);
    return r;
}

extern "C" JNIEXPORT void JNICALL Java_com_blacktunnel_HevBridge_stop(JNIEnv *, jclass) {
    if (fn_quit) fn_quit();
}

extern "C" JNIEXPORT jlongArray JNICALL Java_com_blacktunnel_HevBridge_stats(JNIEnv *env,
                                                                                jclass) {
    size_t a = 0, b = 0, c = 0, d = 0;
    if (fn_stats) fn_stats(&a, &b, &c, &d);

    jlong buf[4] = {(jlong)a, (jlong)b, (jlong)c, (jlong)d};
    jlongArray arr = env->NewLongArray(4);
    env->SetLongArrayRegion(arr, 0, 4, buf);
    return arr;
}
