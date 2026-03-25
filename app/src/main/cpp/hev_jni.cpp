#include <android/log.h>
#include <jni.h>

#define TAG "HevJNI"

extern "C" {
int hev_socks5_tunnel_main_from_file(const char *config_path, int tun_fd);
void hev_socks5_tunnel_quit(void);
void hev_socks5_tunnel_stats(size_t *tx_packets, size_t *tx_bytes, size_t *rx_packets,
                             size_t *rx_bytes);
}

extern "C" JNIEXPORT jint JNICALL Java_com_blacktunnel_HevBridge_start(JNIEnv *env, jclass,
                                                                          jstring path,
                                                                          jint fd) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    __android_log_print(ANDROID_LOG_INFO, TAG, "HEV start fd=%d", fd);
    int r = hev_socks5_tunnel_main_from_file(p, fd);
    __android_log_print(ANDROID_LOG_INFO, TAG, "HEV ended code=%d", r);
    env->ReleaseStringUTFChars(path, p);
    return r;
}

extern "C" JNIEXPORT void JNICALL Java_com_blacktunnel_HevBridge_stop(JNIEnv *, jclass) {
    hev_socks5_tunnel_quit();
}

extern "C" JNIEXPORT jlongArray JNICALL Java_com_blacktunnel_HevBridge_stats(JNIEnv *env,
                                                                                jclass) {
    size_t a = 0, b = 0, c = 0, d = 0;
    hev_socks5_tunnel_stats(&a, &b, &c, &d);
    jlong buf[4] = {(jlong)a, (jlong)b, (jlong)c, (jlong)d};
    jlongArray arr = env->NewLongArray(4);
    env->SetLongArrayRegion(arr, 0, 4, buf);
    return arr;
}
