package com.blacktunnel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class BtVpnService extends VpnService {
    public static final String ACTION_START = "com.blacktunnel.START";
    public static final String ACTION_STOP  = "com.blacktunnel.STOP";
    public static final String ACTION_APPLY = "com.blacktunnel.APPLY";
    private static final String CH_ID        = "bt_vpn";
    private static final int    NF_ID        = 33;
    private static final AtomicBoolean runningState     = new AtomicBoolean(false);
    private static final AtomicBoolean proxyStarted     = new AtomicBoolean(false);
    private static volatile Thread     localProxyThread = null;
    private static final Object        LOG_LOCK         = new Object();
    private static final StringBuilder LOGS             = new StringBuilder(8192);
    private static final int           MAX_LOG_CHARS    = 24000;

    private final AtomicBoolean   running  = new AtomicBoolean(false);
    private final AtomicBoolean   stopping = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile ParcelFileDescriptor                tunPfd;
    private volatile Thread                              hevThread;
    private volatile int                                 hevTunFd = -1;
    private volatile File                                hevCfgFile;
    private volatile ConnectivityManager.NetworkCallback netCallback;

    public static boolean isRunningState() { return runningState.get(); }

    public static void log(String message) {
        String line = "[" + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())
                    + "] " + message + "\n";
        synchronized (LOG_LOCK) {
            LOGS.append(line);
            if (LOGS.length() > MAX_LOG_CHARS)
                LOGS.delete(0, LOGS.length() - MAX_LOG_CHARS);
        }
    }

    public static String dumpLogs() {
        synchronized (LOG_LOCK) {
            String native_ = BtProxy.drainLogs();
            if (native_ != null && !native_.isBlank()) {
                LOGS.append(native_);
                if (LOGS.length() > MAX_LOG_CHARS)
                    LOGS.delete(0, LOGS.length() - MAX_LOG_CHARS);
            }
            return LOGS.toString();
        }
    }

    // ── Proxy local hotspot ──────────────────────────────────────

    public static void startLocalProxy(int port) {
        if (proxyStarted.getAndSet(true)) return;
        Thread t = new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))) {
                ss.setReuseAddress(true);
                while (!Thread.currentThread().isInterrupted()) {
                    Socket client;
                    try { client = ss.accept(); }
                    catch (Exception e) { break; }
                    Thread relay = new Thread(() -> handleClient(client));
                    relay.setDaemon(true);
                    relay.start();
                }
            } catch (Exception ignored) {}
            proxyStarted.set(false);
        }, "local-proxy");
        t.setDaemon(true);
        localProxyThread = t;
        t.start();
    }

    public static void stopLocalProxy() {
        Thread t = localProxyThread;
        if (t != null) { t.interrupt(); localProxyThread = null; }
        proxyStarted.set(false);
    }

    // Detecta protocolo por primer byte y despacha
    private static void handleClient(Socket client) {
        try {
            client.setTcpNoDelay(true);
            client.setSoTimeout(60000);
            InputStream  ci = client.getInputStream();
            OutputStream co = client.getOutputStream();
            int first = ci.read();
            if (first < 0) return;
            if (first == 0x05) {
                // SOCKS5 directo — relay puro al motor
                handleSocks5Direct(client, ci, co, (byte) first);
            } else {
                // HTTP — loop de requests keep-alive
                handleHttpLoop(client, ci, co, (byte) first);
            }
        } catch (Exception ignored) {
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    // SOCKS5 directo: reenvía todo al motor sin tocar nada
    private static void handleSocks5Direct(Socket client, InputStream ci, OutputStream co, byte firstByte) throws Exception {
        try (Socket motor = new Socket("127.0.0.1", BtProxy.SOCKS5_PORT)) {
            motor.setTcpNoDelay(true);
            InputStream  mi = motor.getInputStream();
            OutputStream mo = motor.getOutputStream();
            mo.write(firstByte);
            Thread r = new Thread(() -> {
                try { pipe(mi, co); } catch (Exception ignored) {}
                try { client.close(); } catch (Exception ignored) {}
            });
            r.setDaemon(true);
            r.start();
            pipe(ci, mo);
        }
    }

    // HTTP loop — maneja múltiples requests keep-alive en la misma conexión
    private static void handleHttpLoop(Socket client, InputStream ci, OutputStream co, byte firstByte) throws Exception {
        byte[] pending = new byte[]{(byte) firstByte};
        while (true) {
            // Leer primera línea del request
            String firstLine = readLineWithPrefix(ci, pending);
            pending = null;
            if (firstLine == null || firstLine.isEmpty()) return;

            // Leer headers
            StringBuilder headers = new StringBuilder();
            String line;
            while (!(line = readLine(ci)).isEmpty()) {
                headers.append(line).append("\r\n");
            }
            String hdrs = headers.toString();

            boolean isConnect = firstLine.startsWith("CONNECT ");
            boolean keepAlive = !firstLine.contains("HTTP/1.0") &&
                                !hdrs.toLowerCase(Locale.ROOT).contains("connection: close");

            if (isConnect) {
                // HTTPS CONNECT — tunnel puro, no hay más requests después
                handleConnect(firstLine, ci, co);
                return;
            } else {
                // HTTP plano — procesar request y continuar si keep-alive
                boolean ok = handleHttpRequest(firstLine, hdrs, ci, co);
                if (!ok || !keepAlive) return;
            }
        }
    }

    // HTTPS CONNECT: negocia SOCKS5 y hace relay puro
    private static void handleConnect(String firstLine, InputStream ci, OutputStream co) throws Exception {
        String[] parts = firstLine.split(" ");
        if (parts.length < 2) return;
        String hostPort = parts[1];
        int colon = hostPort.lastIndexOf(':');
        String host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
        int    port = colon >= 0 ? parsePort(hostPort.substring(colon + 1), 443) : 443;

        try (Socket motor = new Socket("127.0.0.1", BtProxy.SOCKS5_PORT)) {
            motor.setTcpNoDelay(true);
            InputStream  mi = motor.getInputStream();
            OutputStream mo = motor.getOutputStream();

            if (!socks5Connect(mi, mo, host, port)) return;

            co.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            co.flush();

            // Relay bidireccional hasta que cualquiera cierre
            Thread r = new Thread(() -> {
                try { pipe(mi, co); } catch (Exception ignored) {}
                try { motor.close(); } catch (Exception ignored) {}
            });
            r.setDaemon(true);
            r.start();
            pipe(ci, mo);
        }
    }

    // HTTP plano: abre conexión SOCKS5, manda request, devuelve response
    private static boolean handleHttpRequest(String firstLine, String hdrs, InputStream ci, OutputStream co) throws Exception {
        String[] parts = firstLine.split(" ");
        if (parts.length < 2) return false;
        String method = parts[0];
        String url    = parts[1];
        String version = parts.length > 2 ? parts[2] : "HTTP/1.1";

        String host;
        int    port;
        String path;

        if (url.startsWith("http://")) {
            String without = url.substring(7);
            int slash = without.indexOf('/');
            String hostPort = slash >= 0 ? without.substring(0, slash) : without;
            path = slash >= 0 ? without.substring(slash) : "/";
            int colon = hostPort.lastIndexOf(':');
            host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
            port = colon >= 0 ? parsePort(hostPort.substring(colon + 1), 80) : 80;
        } else {
            // URL relativa — usar Host header
            String hostHeader = extractHeader(hdrs, "Host:");
            int colon = hostHeader.lastIndexOf(':');
            host = colon >= 0 ? hostHeader.substring(0, colon) : hostHeader;
            port = colon >= 0 ? parsePort(hostHeader.substring(colon + 1), 80) : 80;
            path = url;
        }

        if (host.isEmpty()) return false;

        // Leer body si hay Content-Length
        byte[] body = readBody(hdrs, ci);

        try (Socket motor = new Socket("127.0.0.1", BtProxy.SOCKS5_PORT)) {
            motor.setTcpNoDelay(true);
            InputStream  mi = motor.getInputStream();
            OutputStream mo = motor.getOutputStream();

            if (!socks5Connect(mi, mo, host, port)) return false;

            // Reconstruir request con path relativo (no URL absoluta)
            StringBuilder req = new StringBuilder();
            req.append(method).append(' ').append(path).append(' ').append(version).append("\r\n");

            // Reenviar headers limpiando Proxy-Connection
            for (String h : hdrs.split("\r\n")) {
                if (h.toLowerCase(Locale.ROOT).startsWith("proxy-connection:")) continue;
                req.append(h).append("\r\n");
            }
            req.append("\r\n");
            mo.write(req.toString().getBytes(StandardCharsets.UTF_8));
            if (body != null) mo.write(body);
            mo.flush();

            // Leer y reenviar response completa
            pipeResponse(mi, co);
        }
        return true;
    }

    // Handshake SOCKS5 con el motor — retorna true si ok
    private static boolean socks5Connect(InputStream mi, OutputStream mo, String host, int port) throws Exception {
        // Saludo
        mo.write(new byte[]{0x05, 0x01, 0x00});
        mo.flush();
        byte[] resp = new byte[2];
        readFully(mi, resp);
        if (resp[0] != 0x05 || resp[1] != 0x00) return false;

        // Request CONNECT con nombre de dominio (tipo 0x03)
        byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
        byte[] req = new byte[7 + hostBytes.length];
        req[0] = 0x05; req[1] = 0x01; req[2] = 0x00;
        req[3] = 0x03; req[4] = (byte) hostBytes.length;
        System.arraycopy(hostBytes, 0, req, 5, hostBytes.length);
        req[5 + hostBytes.length] = (byte) ((port >> 8) & 0xFF);
        req[6 + hostBytes.length] = (byte) (port & 0xFF);
        mo.write(req); mo.flush();

        // Respuesta variable según tipo de dirección
        byte[] shead = new byte[4];
        readFully(mi, shead);
        if (shead[1] != 0x00) return false;
        switch (shead[3]) {
            case 0x01: { byte[] skip = new byte[6];            readFully(mi, skip); break; }
            case 0x04: { byte[] skip = new byte[18];           readFully(mi, skip); break; }
            case 0x03: {
                int len = mi.read() & 0xFF;
                byte[] skip = new byte[len + 2];
                readFully(mi, skip);
                break;
            }
            default: return false;
        }
        return true;
    }

    // Lee body según Content-Length
    private static byte[] readBody(String hdrs, InputStream in) throws Exception {
        String cl = extractHeader(hdrs, "Content-Length:");
        if (cl.isEmpty()) return null;
        try {
            int len = Integer.parseInt(cl.trim());
            if (len <= 0) return null;
            byte[] buf = new byte[len];
            readFully(in, buf);
            return buf;
        } catch (Exception ignored) { return null; }
    }

    // Lee y reenvía response HTTP completa incluyendo body
    private static void pipeResponse(InputStream in, OutputStream out) throws Exception {
        // Leer status line
        String status = readLine(in);
        if (status == null || status.isEmpty()) return;
        out.write((status + "\r\n").getBytes(StandardCharsets.UTF_8));

        // Leer headers de response
        int contentLength = -1;
        boolean chunked   = false;
        StringBuilder respHdrs = new StringBuilder();
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            respHdrs.append(line).append("\r\n");
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-length:")) {
                try { contentLength = Integer.parseInt(line.substring(15).trim()); } catch (Exception ignored) {}
            }
            if (lower.contains("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true;
            }
        }
        out.write((respHdrs + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();

        if (chunked) {
            // Chunked transfer
            pipeChunked(in, out);
        } else if (contentLength > 0) {
            byte[] buf = new byte[contentLength];
            readFully(in, buf);
            out.write(buf);
            out.flush();
        } else if (contentLength == 0) {
            // Sin body
        } else {
            // Sin Content-Length — pipe hasta cierre
            pipe(in, out);
        }
    }

    private static void pipeChunked(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[8192];
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null || sizeLine.isEmpty()) break;
            out.write((sizeLine + "\r\n").getBytes(StandardCharsets.UTF_8));
            int chunkSize;
            try { chunkSize = Integer.parseInt(sizeLine.trim().split(";")[0], 16); }
            catch (Exception e) { break; }
            if (chunkSize == 0) {
                readLine(in); // CRLF final
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                break;
            }
            int remaining = chunkSize;
            while (remaining > 0) {
                int toRead = Math.min(remaining, buf.length);
                int n = in.read(buf, 0, toRead);
                if (n < 0) return;
                out.write(buf, 0, n);
                remaining -= n;
            }
            readLine(in); // CRLF tras chunk
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    // Lee una línea con un byte ya leído como prefijo
    private static String readLineWithPrefix(InputStream in, byte[] prefix) throws Exception {
        StringBuilder sb = new StringBuilder();
        if (prefix != null) for (byte b : prefix) sb.append((char)(b & 0xFF));
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
        }
        return b < 0 && sb.length() == 0 ? null : sb.toString().trim();
    }

    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
        }
        return sb.toString();
    }

    private static String extractHeader(String headers, String name) {
        String nameLower = name.toLowerCase(Locale.ROOT);
        for (String l : headers.split("\r\n")) {
            if (l.toLowerCase(Locale.ROOT).startsWith(nameLower))
                return l.substring(name.length()).trim();
        }
        return "";
    }

    private static int parsePort(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static void readFully(InputStream in, byte[] buf) throws Exception {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
    }

    private static void pipe(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); out.flush(); }
    }

    // ── Detección IP hotspot ─────────────────────────────────────

    public static String getHotspotIp() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return null;
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                String name = iface.getName().toLowerCase();
                if (name.equals("lo")         ||
                    name.startsWith("rmnet")  ||
                    name.startsWith("ccmni")  ||
                    name.startsWith("dummy")  ||
                    name.startsWith("p2p")    ||
                    name.startsWith("bt-pan") ||
                    name.startsWith("sit")    ||
                    name.startsWith("ip6tnl")) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isSiteLocalAddress()) continue;
                    if (!(addr instanceof Inet4Address)) continue;
                    String ip = addr.getHostAddress();
                    if (ip == null) continue;
                    if (ip.startsWith("198.18.")) continue;
                    if (ip.startsWith("172.")) continue;
                    return ip;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ── Ciclo de vida del servicio ───────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            executor.execute(() -> stopAll());
            return START_NOT_STICKY;
        }
        if (ACTION_APPLY.equals(action)) {
            executor.execute(this::applyRuntimeChanges);
            return START_STICKY;
        }
        executor.execute(this::startAll);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopAll();
        executor.shutdown();
        super.onDestroy();
    }

    public void onTunnelReconnected() {
        if (!running.get() || stopping.get()) return;
        executor.execute(this::rebuildTunnel);
    }

    private void rebuildTunnel() {
        if (!running.get() || stopping.get()) return;
        log("rebuildTunnel");
        ParcelFileDescriptor old = tunPfd; tunPfd = null;
        stopHev();
        if (old != null) try { old.close(); } catch (Exception ignored) {}
        if (!running.get() || stopping.get()) return;
        boolean gaming = BtProxy.isGamingMode(this);
        hevCfgFile = writeHevCfg(gaming);
        ParcelFileDescriptor pfd = buildTun(gaming);
        if (pfd == null) { stopAll(); return; }
        tunPfd = pfd;
        startHev(pfd.getFd());
    }

    private void startAll() {
        if (running.getAndSet(true)) return;
        stopping.set(false);
        runningState.set(true);
        createChannel();
        startForeground(NF_ID, buildNotif());
        boolean gaming = BtProxy.isGamingMode(this);
        hevCfgFile = writeHevCfg(gaming);
        if (BtProxy.start(this, BtProxy.getOrCreateInternalId(this)) != 0) {
            log("btproxy start failed"); stopAll(); return;
        }
        ParcelFileDescriptor pfd = buildTun(gaming);
        if (pfd == null) { stopAll(); return; }
        tunPfd = pfd;
        startHev(pfd.getFd());
        registerNet();
    }

    private void stopAll() {
        if (!running.getAndSet(false)) return;
        stopping.set(true);
        unregisterNet();
        stopHev();
        BtProxy.stop();
        ParcelFileDescriptor pfd = tunPfd; tunPfd = null;
        if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
        runningState.set(false);
        stopForeground(true);
        stopSelf();
    }

    private void applyRuntimeChanges() {
        if (!running.get() || stopping.get()) return;
        rebuildTunnel();
    }

    private void startHev(int fd) {
        if (hevCfgFile == null) return;
        hevTunFd = fd;
        hevThread = new Thread(() -> {
            try { HevBridge.start(hevCfgFile.getAbsolutePath(), hevTunFd); }
            catch (Exception e) { log("hev error: " + e.getMessage()); }
        }, "hev-tunnel");
        hevThread.setDaemon(true);
        hevThread.start();
    }

    private void stopHev() {
        HevBridge.stop();
        Thread t = hevThread; hevThread = null;
        if (t != null) try { t.join(3000); } catch (Exception ignored) {}
        hevTunFd = -1;
    }

    private ParcelFileDescriptor buildTun(boolean gaming) {
        try {
            Builder builder = new Builder()
                    .setSession("bt-hev")
                    .setMtu(1500)
                    .addAddress("198.18.0.1", 15)
                    .addAddress("fd40::1", 128)
                    .addDnsServer("198.18.0.2")
                    .addDnsServer("8.8.8.8");
            addPublicRoutes(builder);
            applyPerAppVpnPolicy(builder);
            return builder.establish();
        } catch (Exception e) {
            log("buildTun error: " + e.getMessage()); return null;
        }
    }

    private void registerNet() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return;
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network net) {
                    BtProxy.nativeSetNetwork(net.getNetworkHandle());
                }
                @Override public void onLost(Network net) {
                    BtProxy.nativeSetNetwork(0L);
                }
            };
            cm.registerNetworkCallback(
                new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                netCallback);
        } catch (Exception ignored) {}
    }

    private void unregisterNet() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            ConnectivityManager.NetworkCallback cb = netCallback; netCallback = null;
            if (cm != null && cb != null) cm.unregisterNetworkCallback(cb);
            BtProxy.nativeSetNetwork(0L);
        } catch (Exception ignored) {}
    }

    private void addPublicRoutes(Builder builder) {
        String[] excludes = {
            "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
            "169.254.0.0/16", "224.0.0.0/4", "240.0.0.0/4", "255.255.255.255/32"
        };
        List<long[]> excluded = new ArrayList<>();
        for (String cidr : excludes) {
            try {
                String[] p = cidr.split("/");
                long base = ip2long(InetAddress.getByName(p[0]));
                int pfx = Integer.parseInt(p[1]);
                long mask = pfx == 0 ? 0L : (~0L << (32 - pfx)) & 0xFFFFFFFFL;
                long start = base & mask;
                excluded.add(new long[]{start, start + (~mask & 0xFFFFFFFFL)});
            } catch (UnknownHostException ignored) {}
        }
        excluded.sort((a, b) -> Long.compare(a[0], b[0]));
        long cur = 0L;
        for (long[] ex : excluded) {
            if (cur < ex[0]) addCIDRs(builder, cur, ex[0] - 1);
            if (cur <= ex[1]) cur = ex[1] + 1;
        }
        if (cur <= 0xFFFFFFFEL) addCIDRs(builder, cur, 0xFFFFFFFEL);
        builder.addRoute("2000::", 3);
        builder.addRoute("fd40::", 128);
    }

    private void addCIDRs(Builder builder, long start, long end) {
        while (start <= end) {
            int prefix = maxPrefix(start, end);
            builder.addRoute(long2ip(start), prefix);
            start += (1L << (32 - prefix));
        }
    }

    private int maxPrefix(long start, long end) {
        int p = Math.max(0, 32 - Math.min(32, Long.numberOfTrailingZeros(start)));
        while (p < 32 && (1L << (32 - p)) > (end - start + 1)) p++;
        return p;
    }

    private long ip2long(InetAddress a) {
        byte[] b = a.getAddress();
        return ((long)(b[0]&0xFF)<<24)|((long)(b[1]&0xFF)<<16)|((long)(b[2]&0xFF)<<8)|(b[3]&0xFF);
    }

    private String long2ip(long v) {
        return ((v>>24)&0xFF)+"."+((v>>16)&0xFF)+"."+((v>>8)&0xFF)+"."+(v&0xFF);
    }

    private void applyPerAppVpnPolicy(Builder builder) {
        boolean gaming = BtProxy.isGamingMode(this);
        List<String> pkgs = BtProxy.getGamingSelectedPackages(this);
        if (gaming && !pkgs.isEmpty()) {
            for (String pkg : pkgs)
                if (pkg != null && !pkg.isBlank())
                    try { builder.addAllowedApplication(pkg); } catch (Exception ignored) {}
        }
    }

    private File writeHevCfg(boolean gaming) {
        String yml =
            "tunnel:\n  name: bt-hev\n  mtu: 1500\n  ipv4: 198.18.0.1\n  ipv6: 'fd40::1'\n" +
            "socks5:\n  address: 127.0.0.1\n  port: " + BtProxy.SOCKS5_PORT + "\n  udp: 'tcp'\n  pipeline: true\n" +
            "mapdns:\n  address: 198.18.0.2\n  port: 53\n  network: 198.18.0.0\n  netmask: 255.254.0.0\n  cache-size: 8192\n" +
            "misc:\n" +
            "  connect-timeout: 5000\n" +
            "  tcp-read-write-timeout: 180000\n" +
            "  udp-read-write-timeout: 30000\n" +
            "  udp-recv-buffer-size: 131072\n" +
            "  max-session-count: 512\n" +
            "  log-level: warn\n" +
            "  limit-nofile: 65535\n";
        File f = new File(getFilesDir(), "hev.yml");
        try (FileOutputStream o = new FileOutputStream(f, false)) {
            o.write(yml.getBytes(StandardCharsets.UTF_8)); o.flush();
        } catch (Exception ignored) {}
        return f;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null)
            nm.createNotificationChannel(
                new NotificationChannel(CH_ID, "BlackTunnel", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification buildNotif() {
        return new NotificationCompat.Builder(this, CH_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("BlackTunnel").setOngoing(true).build();
    }

    static final class HevBridge {
        static { System.loadLibrary("hev-jni"); }
        static native int  start(String path, int fd);
        static native void stop();
    }
}

final class BtProxy {
    static final int SOCKS5_PORT = 10809;
    private static final String PREFS           = "strike_prefs";
    private static final String KEY_INTERNAL_ID = "internal_id";
    private static final String KEY_GAMING_MODE = "gaming_mode";
    private static final String KEY_GAMING_APPS = "gaming_apps";

    static { System.loadLibrary("btproxy"); }

    static int    start(VpnService svc, String id) { return nativeStart(SOCKS5_PORT, svc, id); }
    static void   stop()                           { nativeStop(); }
    static String drainLogs()                      { return nativeDrainLogs(); }

    static void setGamingMode(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putBoolean(KEY_GAMING_MODE, enabled).apply();
    }

    static void applyStoredGamingMode(Context ctx) {}

    static boolean isGamingMode(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                  .getBoolean(KEY_GAMING_MODE, false);
    }

    static List<String> getGamingSelectedPackages(Context ctx) {
        Set<String> set = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                             .getStringSet(KEY_GAMING_APPS, new HashSet<>());
        return new ArrayList<>(set);
    }

    static void setGamingSelectedPackages(Context ctx, List<String> packages) {
        HashSet<String> clean = new HashSet<>();
        if (packages != null)
            for (String pkg : packages)
                if (pkg != null && !pkg.isBlank()) clean.add(pkg);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putStringSet(KEY_GAMING_APPS, clean).apply();
    }

    static String getOrCreateInternalId(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String existing = sp.getString(KEY_INTERNAL_ID, null);
        if (existing != null && !existing.isBlank()) return existing;
        String rawId = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (rawId == null) rawId = "unknown";
        String seed = rawId + "|" + Build.BRAND + "|" + Build.MODEL + "|" +
                      ctx.getPackageName() + "|" + System.currentTimeMillis();
        String id = "STRK-" + sha256(seed).substring(0, 48);
        sp.edit().putString(KEY_INTERNAL_ID, id).apply();
        return id;
    }

    private static String sha256(String v) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Long.toHexString(System.currentTimeMillis()) + Long.toHexString(System.nanoTime());
        }
    }

    private static native int    nativeStart(int port, VpnService svc, String id);
    private static native void   nativeStop();
    private static native String nativeDrainLogs();
    public  static native void   nativeSetNetwork(long networkHandle);
}
