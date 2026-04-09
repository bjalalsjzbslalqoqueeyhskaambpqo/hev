package com.blacktunnel;

public final class HevBridge {
    static {
        System.loadLibrary("hev-jni");
    }

    private HevBridge() {}

    public static native int start(String path, int fd);
    public static native void stop();
}
