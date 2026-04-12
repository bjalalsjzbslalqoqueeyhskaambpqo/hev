-keep class com.blacktunnel.BtProxy { *; }
-keep class com.blacktunnel.BtVpnService$HevBridge { *; }
-keepclassmembers class com.blacktunnel.BtVpnService {
    void onTunnelReconnected();
}
