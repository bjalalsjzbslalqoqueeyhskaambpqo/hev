package com.blacktunnel;

import android.net.VpnService;

public final class BtProxy {
    static final int SOCKS5_PORT = 10809;

    static { System.loadLibrary("btproxy"); }

    private BtProxy() {}

    static int start(VpnService svc) {
        return nativeStart(SOCKS5_PORT, svc);
    }

    static void stop() {
        nativeStop();
    }

    static String drainLogs() {
        return nativeDrainLogs();
    }

    private static native int nativeStart(int socks5Port, VpnService svc);
    private static native void nativeStop();
    private static native String nativeDrainLogs();
}
