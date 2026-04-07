package com.nexora

object HevBridge {
    init {
        System.loadLibrary("hev-jni")
    }

    external fun start(configPath: String, tunFd: Int): Int
    external fun stop()
}
