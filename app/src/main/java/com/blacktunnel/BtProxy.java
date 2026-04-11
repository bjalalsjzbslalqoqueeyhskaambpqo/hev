package com.blacktunnel;

import android.net.VpnService;
import java.net.Socket;

public final class BtProxy {
    static final int SOCKS5_PORT = 10809;

    static { System.loadLibrary("btproxy"); }

    static int start(VpnService svc) {
        return nativeStart(SOCKS5_PORT, svc);
    }

    static void stop() {
        nativeStop();
    }

    private static native int  nativeStart(int socks5Port, VpnService svc);
    private static native void nativeStop();
}
