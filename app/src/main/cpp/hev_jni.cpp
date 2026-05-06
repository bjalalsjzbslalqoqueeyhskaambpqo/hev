
typedef int (*hev_main_t)(const char *, int);
typedef void (*hev_quit_t)(void);

static hev_main_t fn_main = nullptr;
static hev_quit_t fn_quit = nullptr;

static bool load_hev() {
    if (fn_main) return true;

    void *lib = dlopen("libhev-socks5-tunnel.so", RTLD_NOW | RTLD_GLOBAL);
    if (!lib) return false;

    fn_main = (hev_main_t)dlsym(lib, "hev_socks5_tunnel_main_from_file");
    fn_quit = (hev_quit_t)dlsym(lib, "hev_socks5_tunnel_quit");
    return fn_main && fn_quit;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_blacktunnel_BtVpnService_00024HevBridge_start(JNIEnv *env, jclass, jstring path,
                                                        jint fd) {
    if (!load_hev()) return -1;

    const char *p = env->GetStringUTFChars(path, nullptr);
    int r = fn_main(p, fd);
    env->ReleaseStringUTFChars(path, p);
    return r;
}

extern "C" JNIEXPORT void JNICALL
Java_com_blacktunnel_BtVpnService_00024HevBridge_stop(JNIEnv *, jclass) {
    if (fn_quit) fn_quit();
}
