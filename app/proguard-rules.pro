-keep class com.blacktunnel.BtProxy { *; }
-keep class com.blacktunnel.BtVpnService$HevBridge { *; }
-keepclassmembers class com.blacktunnel.BtVpnService {
    public void onTunnelEvent(int, java.lang.String, java.lang.String);
}
