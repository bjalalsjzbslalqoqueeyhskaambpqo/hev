package com.blacktunnel;

import android.app.AlertDialog;
import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;
import java.util.ArrayDeque;
import java.util.Deque;

public class SimpleModeActivity extends ComponentActivity {
    private static final String PREF_UI              = "ui_state";
    private static final String KEY_HIDE_ID          = "hide_internal_id";
    private static final String KEY_FIRST_OK         = "first_ok";
    private static final long   CONNECTING_TIMEOUT_MS = 40000L;
    private static final long   HEV_STATS_INTERVAL_MS = 1000L;

    private enum UiState { DISCONNECTED, CONNECTING, CONNECTED }

    private Button        cBtn;
    private Button        cpBtn;
    private TextView      stBdV;
    private View          stDtV;
    private View          stHlV;
    private View          stHmV;
    private TextView      stDtlsV;
    private View          lgPn;
    private TextView      lgLtV;
    private TextView      lgFullV;
    private View          lgExL;
    private Button        lgTgB;
    private Button        lgCpB;
    private Button        lgClB;
    private boolean       lgEx = false;
    private TextView      devIdV;
    private TextView      usrNmWV;
    private TextView      hevRtV;
    private ThroughputGraphView thrV;
    private TextView      daysV;
    private TextView      pngV;
    private PingPulseView pngPlsV;
    private Switch        gmSw;
    private TextView      gmBdV;
    private TextView      gmDescV;
    private TextView      gmCntV;
    private Button        selGmBtn;
    private LinearLayout  gmPn;
    private LinearLayout  gmCtlL;
    private View          pnConnV;
    private View          bRO;
    private View          bRM;
    private View          bTD;
    private View          mainSc;
    private View          frPn;
    private View          dcOv;
    private TextView      dcOvTitle;
    private TextView      dcOvBadge;
    private TextView      dcOvMsg;
    private Button        dcOvClose;
    private TextView      frIdV;
    private Button        frCpB;
    private Button        frOkB;
    private Animator      stPlsA;
    private Animator      stHlA;
    private BroadcastReceiver tunnelReceiver;
    private static final String TUNNEL_EVENT = "com.blacktunnel.TUNNEL_EVENT";

    private final ExecutorService appEx    = Executors.newSingleThreadExecutor();
    private String  iid                       = "";
    private UiState uS                          = UiState.DISCONNECTED;
    private long    connMs                = 0L;
    private boolean lstRun                      = false;
    private long    autoDcMs               = -1L;
    private boolean pendRec           = false;
    private String  aSt                        = "";
    private boolean hidIid                   = false;
    private boolean apRt           = false;
    private int     lstPing                       = -1;
    private boolean hsOk               = false;
    private String  lstConn               = "";
    private String  lstDtl                   = "";
    private String  lstDcReason         = "";
    private long    txB = 0L, rxB = 0L, stMs = 0L;

    private final Runnable autoDisconnectRunnable = this::runAutoDisconnect;
    private final Runnable delayedReconnectRunnable = () -> {
        pendRec = false;
        stVp();
    };
    private final Runnable connectingTimeoutRunnable = () -> {
        if (uS != UiState.CONNECTING) return;
        if (SystemClock.elapsedRealtime() - connMs < CONNECTING_TIMEOUT_MS) return;
        lstConn = "failed";
        apRt = false;
        if (BtVpnService.iActive()) stopVpn();
        else setUiState(UiState.DISCONNECTED);
    };
    private final Runnable hevStatsRunnable = new Runnable() {
        @Override public void run() {
            updateHevFlowHint();
            if (uS == UiState.CONNECTED) h.postDelayed(this, HEV_STATS_INTERVAL_MS);
        }
    };
    private ConnectivityManager connMgr;
    private ConnectivityManager.NetworkCallback netCb;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final BtVpnService.TunnelEventListener tunnelEventListener = (type, key, val) ->
        h.post(() -> {
            if (!isFinishing() && !isDestroyed()) handleTunnelEvent(type, key, val);
        });
    private ActivityResultLauncher<Intent> vpnPermL;

    @Override
    protected void attachBaseContext(Context newBase) {
        Configuration configuration = new Configuration(newBase.getResources().getConfiguration());
        configuration.fontScale = 1.0f;
        super.attachBaseContext(newBase.createConfigurationContext(configuration));
    }

    private void startHevStatsLoop() {
        txB = 0L; rxB = 0L; stMs = 0L;
        h.removeCallbacks(hevStatsRunnable);
        h.post(hevStatsRunnable);
    }

    private void stopHevStatsLoop() {
        h.removeCallbacks(hevStatsRunnable);
        txB = 0L; rxB = 0L; stMs = 0L;
        if (hevRtV != null) hevRtV.setVisibility(View.GONE);
        if (thrV != null) thrV.setVisibility(View.GONE);
    }

    private void updateHevFlowHint() {
        if (uS != UiState.CONNECTED) {
            stopHevStatsLoop();
            return;
        }
        try {
            long[] s = BtVpnService.HevBridge.stats();
            if (s == null || s.length < 4) return;
            long now = SystemClock.elapsedRealtime();
            long curTx = s[1], curRx = s[3];
            if (stMs > 0 && now > stMs) {
                long dt = now - stMs;
                long up = Math.max(0, (curTx - txB) * 1000L / dt);
                long dn = Math.max(0, (curRx - rxB) * 1000L / dt);
                if (hevRtV != null) {
                    hevRtV.setVisibility(View.VISIBLE);
                    hevRtV.setText(buildHevRateText(up, dn));
                }
                if (thrV != null) {
                    thrV.setVisibility(View.VISIBLE);
                    thrV.push(up, dn);
                }
            }
            txB = curTx; rxB = curRx; stMs = now;
        } catch (Throwable ignored) {}
    }

    private CharSequence buildHevRateText(long up, long dn) {
        String upText = "↑ " + fmtRate(up);
        String dnText = "↓ " + fmtRate(dn);
        String merged = upText + "  " + dnText;
        SpannableString span = new SpannableString(merged);
        span.setSpan(new ForegroundColorSpan(Color.parseColor("#3BEF8F")), 0, upText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new ForegroundColorSpan(Color.parseColor("#44B7FF")), upText.length() + 2, merged.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return span;
    }

    private String fmtRate(long bps) {
        if (bps >= 1024L * 1024L) return String.format(Locale.US, "%.1f MB/s", bps / 1024f / 1024f);
        if (bps >= 1024L) return String.format(Locale.US, "%.1f KB/s", bps / 1024f);
        return bps + " B/s";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_mode);

        vpnPermL = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK || VpnService.prepare(this) == null) startVpn();
                else setUiState(UiState.DISCONNECTED);
            }
        );

        cBtn              = findViewById(R.id.btnConnect);
        cpBtn               = findViewById(R.id.btnCopyLogs);
        stBdV         = findViewById(R.id.txtStatusBadge);
        stDtV           = findViewById(R.id.viewStatusDot);
        stHlV          = findViewById(R.id.viewStatusHalo);
        stHmV       = findViewById(R.id.viewStatusHaloMid);
        stDtlsV       = findViewById(R.id.txtStatusDetails);
        lgPn              = findViewById(R.id.panelConnLog);
        lgLtV             = findViewById(R.id.txtConnLogLatest);
        lgFullV           = findViewById(R.id.txtConnLogFull);
        lgExL             = findViewById(R.id.layoutConnLogExpanded);
        lgTgB             = findViewById(R.id.btnConnLogToggle);
        lgCpB             = findViewById(R.id.btnConnLogCopy);
        lgClB             = findViewById(R.id.btnConnLogClear);
        devIdV            = findViewById(R.id.txtDeviceId);
        daysV           = findViewById(R.id.txtDays);
        pngV           = findViewById(R.id.txtPingValue);
        pngPlsV           = findViewById(R.id.pingPulseView);
        gmSw        = findViewById(R.id.switchGamingMode);
        gmBdV     = findViewById(R.id.txtGamingBadge);
        gmDescV   = findViewById(R.id.txtGamingDescription);
        gmCntV = findViewById(R.id.txtGamingSelectedCount);
        selGmBtn     = findViewById(R.id.btnSelectGamingApps);
        gmPn         = findViewById(R.id.panelGamingMode);
        gmCtlL    = findViewById(R.id.layoutGamingControls);
        pnConnV     = findViewById(R.id.panelConnection);
        usrNmWV        = findViewById(R.id.txtUserNameWide);
        hevRtV         = findViewById(R.id.txtHevRate);
        thrV           = findViewById(R.id.throughputGraphView);
        bRO            = findViewById(R.id.btnRingOuter);
        bRM              = findViewById(R.id.btnRingMid);
        bTD               = findViewById(R.id.btnTopDot);
        mainSc            = findViewById(R.id.mainScroll);
        frPn              = findViewById(R.id.panelFirstRun);
        dcOv              = findViewById(R.id.disconnectOverlay);
        dcOvTitle         = findViewById(R.id.txtOverlayTitle);
        dcOvBadge         = findViewById(R.id.txtOverlayBadge);
        dcOvMsg           = findViewById(R.id.txtOverlayMessage);
        dcOvClose         = findViewById(R.id.btnOverlayClose);
        frIdV             = findViewById(R.id.txtFirstRunId);
        frCpB             = findViewById(R.id.btnFirstRunCopy);
        frOkB             = findViewById(R.id.btnFirstRunOk);

        iid = BtProxy.gIid(this);
        BtProxy.aGm(this);
        hidIid = getSharedPreferences(PREF_UI, MODE_PRIVATE).getBoolean(KEY_HIDE_ID, false);
        if (devIdV != null) devIdV.setText("ID: " + iid);
        refreshDeviceIdVisibility();

        boolean run = BtVpnService.iRun();
        boolean starting = BtVpnService.iStarting();
        setUiState(run ? UiState.CONNECTED : starting ? UiState.CONNECTING : UiState.DISCONNECTED);
        lstRun = run;

        cBtn.setOnClickListener(v -> {
            if (uS == UiState.CONNECTING) return;
            if (uS == UiState.CONNECTED) stopVpn();
            else {
                if (BtVpnService.iActive()) {
                    if (!pendRec) {
                        pendRec = true;
                        setUiState(UiState.CONNECTING);
                        h.removeCallbacks(delayedReconnectRunnable);
                        h.postDelayed(delayedReconnectRunnable, 850);
                    }
                } else {
                    stVp();
                }
            }
        });
        cpBtn.setOnClickListener(v -> copyInternalIdToClipboard());
        if (frCpB != null) frCpB.setOnClickListener(v -> copyInternalIdToClipboard());
        if (frOkB != null) frOkB.setOnClickListener(v -> dismissFirstRun());
        if (dcOvClose != null) dcOvClose.setOnClickListener(v -> hideDisconnectOverlay());
        if (lgTgB != null) lgTgB.setOnClickListener(v -> toggleConnLog());
        if (lgCpB != null) lgCpB.setOnClickListener(v -> copyConnLog());
        if (lgClB != null) lgClB.setOnClickListener(v -> clearConnLogView());

        if (gmSw != null) {
            gmSw.setChecked(BtProxy.iGm(this));
            gmSw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                BtProxy.sGm(this, isChecked);
                rfGmUi();
                if (BtVpnService.iActive()) {
                    showGamingApplyFeedback(
                        isChecked ? "Modo aplicaciones: activando..." : "Modo normal: aplicando...",
                        isChecked ? "Modo aplicaciones activo" : "Modo normal activo",
                        isChecked ? R.color.color_gaming : R.color.color_text_secondary
                    );
                    apGmIfRun();
                }
            });
        }
        if (selGmBtn != null)
            selGmBtn.setOnClickListener(v -> openGamingAppsDialog());

        rfGmUi();
        setupConnectivityMonitor();
        showFirstRunIfNeeded();
        BtVpnService.setTunnelEventListener(tunnelEventListener);
        registerTunnelReceiver();
    }

    private void showFirstRunIfNeeded() {
        boolean ok = getSharedPreferences(PREF_UI, MODE_PRIVATE).getBoolean(KEY_FIRST_OK, false);
        if (ok) return;
        if (frIdV != null) frIdV.setText(iid);
        if (frPn != null) frPn.setVisibility(View.VISIBLE);
        if (mainSc != null) mainSc.setVisibility(View.GONE);
    }

    private void dismissFirstRun() {
        getSharedPreferences(PREF_UI, MODE_PRIVATE).edit().putBoolean(KEY_FIRST_OK, true).apply();
        if (frPn != null) frPn.setVisibility(View.GONE);
        if (mainSc != null) mainSc.setVisibility(View.VISIBLE);
    }

    private void showDisconnectOverlay(String title, String message, int titleColor) {
        if (dcOv == null || dcOvTitle == null || dcOvMsg == null) return;
        if (dcOvBadge != null) {
            String badge = "BLOQUEO DE CONEXIÓN";
            if (title.contains("EXPIRADO")) badge = "ACCESO EXPIRADO";
            else if (title.contains("NO REGISTRADO")) badge = "USUARIO NO HABILITADO";
            else if (title.contains("SESIÓN")) badge = "SESIÓN FINALIZADA";
            dcOvBadge.setText(badge);
        }
        dcOvTitle.setText(title);
        dcOvTitle.setTextColor(titleColor);
        dcOvMsg.setText(message);
        dcOv.setVisibility(View.VISIBLE);
    }

    private void hideDisconnectOverlay() {
        if (dcOv != null) dcOv.setVisibility(View.GONE);
    }

    private void applyHudTheme(int accentColor) {
        float dp = getResources().getDisplayMetrics().density;

        if (bRO  != null) bRO.setBackground(VisualDrawables.bRO(accentColor));
        if (bRM    != null) bRM.setBackground(VisualDrawables.bRM(accentColor));
        if (bTD     != null) bTD.setBackgroundTintList(ColorStateList.valueOf(accentColor));

        if (pnConnV != null)
            pnConnV.setBackground(VisualDrawables.statusBadge(accentColor));

        View metricsStrip = findViewById(R.id.layoutDataStrip);
        if (metricsStrip != null) metricsStrip.setBackground(VisualDrawables.panelMetrics(accentColor));

        if (pngPlsV != null) pngPlsV.setLineColor(accentColor);

        if (cBtn != null)
            cBtn.setBackground(VisualDrawables.btnConnect(accentColor, uS == UiState.CONNECTED));
    }

    private int resolveAccentColor() {
        return uS == UiState.CONNECTED  ? c(R.color.color_connected)
             : uS == UiState.CONNECTING ? c(R.color.color_connecting)
             : c(R.color.color_disconnected);
    }

    private void setupConnectivityMonitor() {
        connMgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connMgr == null) return;
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onLost(Network network) {
                h.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (uS == UiState.CONNECTED || BtVpnService.iRun()) {
                        lstConn = "dropped";
                        setUiState(UiState.CONNECTING);
                    } else if (uS == UiState.CONNECTING) {
                        lstConn = "retrying";
                        refreshConnectingDetail();
                    }
                });
            }

            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (caps == null) return;
                h.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (uS == UiState.CONNECTING
                            && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        lstConn = "retrying";
                        refreshConnectingDetail();
                    }
                });
            }
        };
        try {
            connMgr.registerNetworkCallback(
                new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                netCb);
        } catch (Throwable ignored) {}
    }

    private void copyInternalIdToClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("internal_id", iid));
            Toast.makeText(this, "ID copiado", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Error copiando ID", Toast.LENGTH_SHORT).show();
        }
    }


    private void handleConnectionFailureEvent() {
        lstConn = "failed";
        if (BtVpnService.iActive()) {
            setUiState(UiState.CONNECTING);
        } else {
            setUiState(UiState.DISCONNECTED);
            Toast.makeText(this, "No se pudo conectar al túnel", Toast.LENGTH_SHORT).show();
        }
    }


    private void registerTunnelReceiver() {
        tunnelReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int    type = intent.getIntExtra("type", 0);
                String key  = intent.getStringExtra("key");
                String val  = intent.getStringExtra("value");
                handleTunnelEvent(type, key, val);
            }
        };
        IntentFilter filter = new IntentFilter(TUNNEL_EVENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tunnelReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(tunnelReceiver, filter);
        }
    }

    private void handleTunnelEvent(int type, String key, String val) {
        if (val == null) val = "";
        switch (type) {

            case 1: // EV_STAGE
                switch (val) {
                    case "native_start":
                    case "proxy_connect_start":
                    case "proxy_connected":
                    case "server_auth_request":
                        lstConn = val;
                        if (uS != UiState.CONNECTING) setUiState(UiState.CONNECTING);
                        else refreshConnectingDetail();
                        break;

                    case "relay_ready":
                        lstConn = "relay_ready";
                        hsOk = true;
                        if (uS != UiState.CONNECTING) setUiState(UiState.CONNECTING);
                        else refreshConnectingDetail();
                        break;

                    case "vpn_start":
                        lstConn = "vpn_start";
                        if (uS != UiState.CONNECTING) setUiState(UiState.CONNECTING);
                        else refreshConnectingDetail();
                        break;

                    case "hev_started":
                        lstConn = "connected";
                        hsOk = true;
                        setUiState(UiState.CONNECTED);
                        break;

                    case "hev_failed":
                        lstConn = "hev_failed";
                        apRt = false;
                        if (BtVpnService.iActive()) stopVpn();
                        else setUiState(UiState.DISCONNECTED);
                        Toast.makeText(this, "No se pudo iniciar el motor VPN", Toast.LENGTH_SHORT).show();
                        break;

                    case "access_granted":
                        hsOk   = true;
                        lstConn = "access_granted";
                        if (uS != UiState.CONNECTING) setUiState(UiState.CONNECTING);
                        else refreshConnectingDetail();
                        break;

                    case "auth_rejected":
                        lstConn = "auth_rejected";
                        apRt = false;
                        if (BtVpnService.iActive()) stopVpn();
                        else setUiState(UiState.DISCONNECTED);
                        break;

                    case "manual_reconnect_required":
                        lstConn = "manual_reconnect_required";
                        apRt    = false;
                        if (BtVpnService.iActive()) stopVpn();
                        setUiState(UiState.DISCONNECTED);
                        break;

                    case "tunnel_down":
                        apRt = false;
                        hsOk  = false;
                        lstConn = "dropped";
                        if (BtVpnService.iActive()) {
                            setUiState(UiState.CONNECTING);
                        } else {
                            setUiState(UiState.DISCONNECTED);
                        }
                        break;

                    case "proxy_connect_failed":
                    case "proxy_no_response":
                        handleConnectionFailureEvent();
                        break;
                }
                break;

            case 2: // EV_PING
                try {
                    int ping = Integer.parseInt(val.replaceAll("[^0-9]", ""));
                    animatePingTo(ping);
                    if (pngPlsV != null) {
                        pngPlsV.setLineColor(resolvePingColor(ping));
                        pngPlsV.pushPing(ping);
                    }
                } catch (Exception ignored) {}
                break;

            case 3: // EV_USER_DATA
                if ("user_name".equals(key)) {
                    if (usrNmWV != null) usrNmWV.setText("Usuario: " + val);
                } else if ("user_days".equals(key)) {
                    if (daysV != null) {
                        daysV.setText(val + " días");
                        try {
                            int days = Integer.parseInt(val.replaceAll("[^0-9]", ""));
                            daysV.setTextColor(days < 7
                                ? c(R.color.color_connecting)
                                : c(R.color.color_connected));
                            scheduleAutoDisconnectFromDays(days);
                        } catch (Exception ignored) {}
                    }
                    setHideInternalId(true);
                    hideDisconnectOverlay();
                }
                break;

            case 4: // EV_DISCONNECT
                lstDcReason = val;
                switch (val) {
                    case "not_registered":
                        if ("not_registered".equals(aSt)) break;
                        aSt = "not_registered";
                        setHideInternalId(false);
                        if (BtVpnService.iActive()) stopVpn();
                        setUiState(UiState.DISCONNECTED);
                        if (stDtlsV != null) {
                            stDtlsV.setVisibility(View.VISIBLE);
                            stDtlsV.setText("✖ Usuario no registrado\nComparte tu ID interno\nID: " + iid);
                            stDtlsV.setTextColor(c(R.color.color_disconnected));
                        }
                        showDisconnectOverlay("USUARIO NO REGISTRADO",
                            "Tu identificador no está registrado.\nID: " + iid,
                            c(R.color.color_disconnected));
                        if (cBtn != null)
                            cBtn.startAnimation(AnimationUtils.loadAnimation(this, R.anim.shake));
                        break;

                    case "expired":
                        if ("expired".equals(aSt)) break;
                        aSt = "expired";
                        setHideInternalId(false);
                        if (BtVpnService.iActive()) stopVpn();
                        setUiState(UiState.DISCONNECTED);
                        if (stDtlsV != null) {
                            stDtlsV.setVisibility(View.VISIBLE);
                            stDtlsV.setText("✖ Usuario expirado\nRenueva tu acceso\nID: " + iid);
                            stDtlsV.setTextColor(c(R.color.color_connecting));
                        }
                        showDisconnectOverlay("USUARIO EXPIRADO",
                            "Tu acceso venció.\nRenueva con soporte.\nID: " + iid,
                            c(R.color.color_connecting));
                        if (cBtn != null)
                            cBtn.startAnimation(AnimationUtils.loadAnimation(this, R.anim.shake));
                        break;

                    case "kick":
                        if ("kick".equals(aSt)) break;
                        aSt = "kick";
                        if (BtVpnService.iActive()) stopVpn();
                        setUiState(UiState.DISCONNECTED);
                        showDisconnectOverlay("SESIÓN CERRADA",
                            "La sesión fue cerrada por el administrador.",
                            c(R.color.color_disconnected));
                        break;
                }
                break;
        }
    }

    @Override
    protected void onDestroy() {
        BtVpnService.clearTunnelEventListener(tunnelEventListener);
        if (tunnelReceiver != null) {
            try { unregisterReceiver(tunnelReceiver); } catch (Exception ignored) {}
        }
        h.removeCallbacks(autoDisconnectRunnable);
        h.removeCallbacks(delayedReconnectRunnable);
        h.removeCallbacks(connectingTimeoutRunnable);
        h.removeCallbacks(hevStatsRunnable);
        if (cBtn != null) cBtn.animate().cancel();
        if (stDtlsV != null) stDtlsV.animate().cancel();
        if (stBdV != null) stBdV.animate().cancel();
        stopStatusPulse();
        stopStatusHaloWave();
        stopHudRingRotation();
        if (connMgr != null && netCb != null) {
            try { connMgr.unregisterNetworkCallback(netCb); } catch (Throwable ignored) {}
        }
        appEx.shutdownNow();
        super.onDestroy();
    }

    private int c(int colorRes) { return getColor(colorRes); }
    private boolean canAnimate() { return ValueAnimator.areAnimatorsEnabled(); }

    private void startStatusPulse() {
        if (stDtV == null || !canAnimate()) return;
        if (stPlsA == null)
            stPlsA = AnimatorInflater.loadAnimator(this, R.animator.pulse);
        stPlsA.setTarget(stDtV);
        if (!stPlsA.isStarted()) stPlsA.start();
    }

    private void stopStatusPulse() {
        if (stPlsA != null) stPlsA.cancel();
        if (stDtV != null) {
            stDtV.setScaleX(1f); stDtV.setScaleY(1f); stDtV.setAlpha(1f);
        }
    }

    private void startStatusHaloWave() {
        if (stHlV == null || !canAnimate()) return;
        if (stHlA == null)
            stHlA = AnimatorInflater.loadAnimator(this, R.animator.status_halo_wave);
        stHlA.setTarget(stHlV);
        if (!stHlA.isStarted()) stHlA.start();
    }

    private void stopStatusHaloWave() {
        if (stHlA != null) stHlA.cancel();
        if (stHlV != null) {
            stHlV.setScaleX(1f); stHlV.setScaleY(1f); stHlV.setAlpha(0.20f);
        }
    }

    private void startHudRingRotation() {
        if (!canAnimate() || bRO == null) return;
        bRO.animate().cancel();
        bRO.animate()
            .rotationBy(360f)
            .setDuration(9000)
            .setInterpolator(new android.view.animation.LinearInterpolator())
            .withEndAction(() -> {
                if (uS == UiState.CONNECTED && bRO != null && bRO.isAttachedToWindow())
                    startHudRingRotation();
            })
            .start();
    }

    private void stopHudRingRotation() {
        if (bRO != null) bRO.animate().cancel();
    }


    private void animateBadgeColor(TextView tv, int toColor, long durationMs) {
        if (tv == null || !canAnimate()) { if (tv != null) tv.setTextColor(toColor); return; }
        int from = tv.getCurrentTextColor();
        ValueAnimator va = ValueAnimator.ofObject(new ArgbEvaluator(), from, toColor);
        va.setDuration(durationMs);
        va.addUpdateListener(a -> tv.setTextColor((Integer) a.getAnimatedValue()));
        va.start();
    }

    private void animateDotColor(View dot, View halo, int toColor, long durationMs) {
        if (dot == null) return;
        if (!canAnimate()) {
            dot.setBackgroundTintList(ColorStateList.valueOf(toColor));
            if (halo != null) halo.setBackgroundTintList(ColorStateList.valueOf(toColor));
            return;
        }
        int fromDot = dot.getBackgroundTintList() != null
            ? dot.getBackgroundTintList().getDefaultColor() : toColor;
        ValueAnimator va = ValueAnimator.ofObject(new ArgbEvaluator(), fromDot, toColor);
        va.setDuration(durationMs);
        va.addUpdateListener(a -> {
            int col = (Integer) a.getAnimatedValue();
            dot.setBackgroundTintList(ColorStateList.valueOf(col));
            if (halo != null) halo.setBackgroundTintList(ColorStateList.valueOf(col));
        });
        va.start();
    }

    private static final class AppOption {
        final String packageName; final String appName; final Drawable icon;
        AppOption(String p, String n, Drawable i) { packageName = p; appName = n; icon = i; }
    }

    private void rfGmUi() {
        boolean enabled = BtProxy.iGm(this);
        List<String> selected = BtProxy.gGmPk(this);

        if (gmBdV != null) {
            if (enabled) {
                gmBdV.setText(R.string.gaming_mode_on_compact);
                gmBdV.setTextColor(c(R.color.color_gaming));
                gmBdV.setBackgroundResource(R.drawable.strike_chip_left);
                gmBdV.setLetterSpacing(0.14f);
            } else {
                gmBdV.setText(R.string.gaming_mode_off_compact);
                gmBdV.setTextColor(c(R.color.color_btn_disabled));
            }
        }
        if (gmDescV != null)
            gmDescV.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (gmCntV != null) {
            if (selected.isEmpty()) {
                gmCntV.setText("Ninguna app seleccionada");
                gmCntV.setTextColor(c(R.color.color_text_disabled));
            } else {
                String first   = selected.get(0);
                String summary = selected.size() > 1 ? first + " +" + (selected.size() - 1) + " más" : first;
                gmCntV.setText(summary);
                gmCntV.setTextColor(c(R.color.color_gaming));
            }
        }
        if (selGmBtn != null) {
            selGmBtn.setEnabled(enabled);
            selGmBtn.setAlpha(enabled ? 1f : 0.55f);
        }
        if (gmCtlL != null) {
            if (enabled) {
                gmCtlL.setVisibility(View.VISIBLE);
                gmCtlL.setAlpha(0f);
                gmCtlL.animate().alpha(1f).setDuration(180).start();
            } else {
                gmCtlL.animate().cancel();
                gmCtlL.setVisibility(View.GONE);
            }
        }
        if (gmPn != null) gmPn.setActivated(enabled);
        if (cBtn != null && uS != UiState.CONNECTED && uS != UiState.CONNECTING)
            cBtn.setText(enabled ? getString(R.string.connect_gaming) : getString(R.string.connect));
    }

    private void openGamingAppsDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_gaming_apps, null, false);
        EditText     searchView     = dialogView.findViewById(R.id.editSearchApps);
        TextView     counterView    = dialogView.findViewById(R.id.txtPickerCounter);
        LinearLayout selectedLayout = dialogView.findViewById(R.id.layoutSelectedApps);
        ListView     listView       = dialogView.findViewById(R.id.listGamingApps);

        counterView.setText(getString(R.string.gaming_loading_apps));
        Set<String> selectedPackages = new HashSet<>(BtProxy.gGmPk(this));
        listView.setEnabled(false);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setView(dialogView)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar", (d, which) -> {
                BtProxy.sGmPk(this, new ArrayList<>(selectedPackages));
                rfGmUi();
                showGamingApplyFeedback("Aplicando selección de apps...", "Selección aplicada", R.color.color_accent);
                apGmIfRun();
            })
            .create();

        appEx.execute(() -> {
            List<AppOption> allApps = loadInstalledUserApps();
            runOnUiThread(() -> bindGamingDialogContent(searchView, counterView, selectedLayout, listView, selectedPackages, allApps));
        });
        dialog.show();
    }

    private void bindGamingDialogContent(EditText searchView, TextView counterView,
            LinearLayout selectedLayout, ListView listView,
            Set<String> selectedPackages, List<AppOption> allApps) {
        GamingAppsAdapter adapter = new GamingAppsAdapter(allApps, selectedPackages);
        listView.setAdapter(adapter);
        listView.setEnabled(true);

        final Runnable[] refreshPinned = new Runnable[1];
        refreshPinned[0] = () -> {
            selectedLayout.removeAllViews();
            counterView.setText(getString(R.string.gaming_selected_count, selectedPackages.size()));
            for (AppOption app : allApps) {
                if (!selectedPackages.contains(app.packageName)) continue;
                Button chip = new Button(this);
                chip.setText(app.appName + " ✕");
                chip.setAllCaps(false);
                chip.setTextSize(12f);
                chip.setPadding(20, 10, 20, 10);
                chip.setBackgroundResource(R.drawable.strike_chip_left);
                chip.setTextColor(c(R.color.color_text_primary));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, 12, 0);
                chip.setLayoutParams(lp);
                chip.setOnClickListener(v -> {
                    selectedPackages.remove(app.packageName);
                    adapter.notifyDataSetChanged();
                    refreshPinned[0].run();
                });
                selectedLayout.addView(chip);
            }
        };

        adapter.setSelectionListener((app, checked) -> {
            if (checked && selectedPackages.size() >= 3 && !selectedPackages.contains(app.packageName)) {
                Toast.makeText(this, "Máximo 3 aplicaciones en modo seleccionado", Toast.LENGTH_SHORT).show();
                adapter.notifyDataSetChanged(); return;
            }
            if (checked) selectedPackages.add(app.packageName);
            else         selectedPackages.remove(app.packageName);
            adapter.notifyDataSetChanged();
            refreshPinned[0].run();
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            AppOption app    = adapter.getItem(position);
            boolean   checked = !selectedPackages.contains(app.packageName);
            if (checked && selectedPackages.size() >= 3 && !selectedPackages.contains(app.packageName)) {
                Toast.makeText(this, "Máximo 3 aplicaciones en modo seleccionado", Toast.LENGTH_SHORT).show(); return;
            }
            if (checked) selectedPackages.add(app.packageName);
            else         selectedPackages.remove(app.packageName);
            adapter.notifyDataSetChanged();
            refreshPinned[0].run();
        });

        searchView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        refreshPinned[0].run();
    }

    private List<AppOption> loadInstalledUserApps() {
        PackageManager pm  = getPackageManager();
        List<ApplicationInfo> all = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        List<AppOption> out = new ArrayList<>();
        for (ApplicationInfo app : all) {
            boolean isSystem        = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean isUpdatedSystem = (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            boolean isInData        = app.sourceDir != null && app.sourceDir.startsWith("/data/app/");
            if (!isSystem || isUpdatedSystem || isInData) {
                String pkg = app.packageName;
                if (pkg == null || pkg.equals(getPackageName())) continue;
                CharSequence label = app.loadLabel(pm);
                out.add(new AppOption(pkg, label == null ? pkg : label.toString(), app.loadIcon(pm)));
            }
        }
        out.sort(Comparator.comparing(o -> o.appName.toLowerCase(Locale.ROOT)));
        return out;
    }

    private final class GamingAppsAdapter extends BaseAdapter {
        interface OnSelectionChanged { void onChange(AppOption app, boolean checked); }
        private final List<AppOption> allApps, filteredApps;
        private final Set<String>     selectedPackages;
        private OnSelectionChanged    selectionListener;
        GamingAppsAdapter(List<AppOption> apps, Set<String> sel) {
            allApps = new ArrayList<>(apps); filteredApps = new ArrayList<>(apps); selectedPackages = sel;
        }
        void setSelectionListener(OnSelectionChanged l) { selectionListener = l; }
        void filter(String query) {
            filteredApps.clear();
            String q = query.trim().toLowerCase(Locale.ROOT);
            for (AppOption app : allApps)
                if (q.isEmpty() || app.appName.toLowerCase(Locale.ROOT).contains(q)
                        || app.packageName.toLowerCase(Locale.ROOT).contains(q))
                    filteredApps.add(app);
            notifyDataSetChanged();
        }
        @Override public int       getCount()            { return filteredApps.size(); }
        @Override public AppOption getItem(int p)        { return filteredApps.get(p); }
        @Override public long      getItemId(int p)      { return p; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null)
                view = LayoutInflater.from(SimpleModeActivity.this)
                    .inflate(R.layout.item_gaming_app, parent, false);
            AppOption app = getItem(position);
            ((ImageView) view.findViewById(R.id.imgAppIcon)).setImageDrawable(app.icon);
            ((TextView)  view.findViewById(R.id.txtAppName)).setText(app.appName);
            ((TextView)  view.findViewById(R.id.txtPackageName)).setText(app.packageName);
            CheckBox check = view.findViewById(R.id.checkSelected);
            check.setOnCheckedChangeListener(null);
            check.setChecked(selectedPackages.contains(app.packageName));
            check.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (selectionListener != null) selectionListener.onChange(app, isChecked);
            });
            return view;
        }
    }

    private void stVp() {
        BtVpnService.cLogs();
        clearConnLogView();
        setUiState(UiState.CONNECTING);
        aSt = ""; lstConn = "";
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) vpnPermL.launch(prepare);
        else startVpn();
    }

    private void startVpn() {
        BtVpnService.cLogs();
        Intent i = new Intent(this, BtVpnService.class);
        i.setAction(BtVpnService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        } catch (Throwable t) {
            lstConn = "failed";
            setUiState(UiState.DISCONNECTED);
            Toast.makeText(this, "No se pudo iniciar la VPN", Toast.LENGTH_SHORT).show();
            return;
        }
        connMs = SystemClock.elapsedRealtime();
        hsOk = false; lstConn = ""; lstDtl = ""; aSt = "";
        lstPing = -1;
        if (pngV != null) {
            pngV.setText("--");
            pngV.setTextColor(c(R.color.color_text_disabled));
        }
        if (usrNmWV != null) usrNmWV.setText("Usuario: --");
        if (daysV != null) {
            daysV.setText("--");
            daysV.setTextColor(c(R.color.color_text_disabled));
        }
        stopHevStatsLoop();
        setUiState(UiState.CONNECTING);
        h.removeCallbacks(connectingTimeoutRunnable);
        h.removeCallbacks(hevStatsRunnable);
        h.postDelayed(connectingTimeoutRunnable, CONNECTING_TIMEOUT_MS + 750L);
    }

    private void stopVpn() {
        pendRec = false;
        h.removeCallbacks(delayedReconnectRunnable);
        h.removeCallbacks(connectingTimeoutRunnable);
        h.removeCallbacks(hevStatsRunnable);
        Intent i = new Intent(this, BtVpnService.class);
        i.setAction(BtVpnService.ACTION_STOP);
        startService(i);
        BtVpnService.cLogs();
        hsOk = false; lstConn = ""; lstDtl = ""; aSt = "";
        setUiState(UiState.DISCONNECTED);
        clearConnLogView();
        lgPn.setVisibility(View.GONE);
        lstPing = -1;
        if (pngV != null) {
            pngV.setText("--");
            pngV.setTextColor(c(R.color.color_text_disabled));
        }
        if (usrNmWV != null) usrNmWV.setText("Usuario: --");
        if (daysV != null) {
            daysV.setText("--");
            daysV.setTextColor(c(R.color.color_text_disabled));
        }
        stopHevStatsLoop();
        h.removeCallbacks(autoDisconnectRunnable);
        autoDcMs = -1L;
    }

    private void apGmIfRun() {
        if (!BtVpnService.iActive()) return;
        apRt = true;
        connMs      = SystemClock.elapsedRealtime();
        lstConn     = "";
        setUiState(UiState.CONNECTING);
        Intent i = new Intent(this, BtVpnService.class);
        i.setAction(BtVpnService.ACTION_APPLY);
        startService(i);
    }

    private void setHideInternalId(boolean hide) {
        if (hidIid == hide) return;
        hidIid = hide;
        getSharedPreferences(PREF_UI, MODE_PRIVATE).edit().putBoolean(KEY_HIDE_ID, hide).apply();
        refreshDeviceIdVisibility();
    }

    private void refreshDeviceIdVisibility() {
        if (devIdV != null) devIdV.setVisibility(hidIid ? View.GONE : View.VISIBLE);
        if (cpBtn   != null) cpBtn.setVisibility(hidIid ? View.GONE : View.VISIBLE);
    }

    private void showGamingApplyFeedback(String start, String done, int colorRes) {
        if (gmBdV == null) return;
        gmBdV.setText(start);
        gmBdV.setTextColor(c(colorRes));
        gmBdV.animate().cancel();
        gmBdV.setAlpha(0.55f);
        gmBdV.animate().alpha(1f).setDuration(220).start();
        h.postDelayed(() -> {
            if (gmBdV != null) gmBdV.setText(done);
            h.postDelayed(this::rfGmUi, 500);
        }, 500);
    }

    private void toggleConnLog() {
        lgEx = !lgEx;
        if (lgExL != null) lgExL.setVisibility(lgEx ? View.VISIBLE : View.GONE);
        if (lgTgB != null) lgTgB.setText(lgEx ? "Ocultar" : "Ver más");
    }

    private void copyConnLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        String logs = BtVpnService.gLogs();
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("conn_logs", logs));
            Toast.makeText(this, logs.isEmpty() ? "No hay logs para copiar" : "Logs copiados", Toast.LENGTH_SHORT).show();
        }
    }

    private void clearConnLogView() {
        if (lgLtV != null) lgLtV.setText("");
        if (lgFullV != null) lgFullV.setText("");
    }

    private void refreshConnectingDetail() {
        if (stDtlsV == null) return;
        String detail;
        if      ("native_start".equals(lstConn)) detail = "Abriendo túnel nativo...";
        else if ("proxy_connect_start".equals(lstConn)) detail = "Conectando al proxy...";
        else if ("proxy_connected".equals(lstConn)) detail = "Proxy conectado";
        else if ("server_auth_request".equals(lstConn)) detail = "Solicitando acceso al servidor...";
        else if ("access_granted".equals(lstConn)) detail = "Acceso concedido, preparando túnel...";
        else if ("relay_ready".equals(lstConn)) detail = "Túnel listo, creando VPN...";
        else if ("vpn_start".equals(lstConn)) detail = "Iniciando motor HEV...";
        else if ("hev_failed".equals(lstConn)) detail = "No se pudo iniciar el motor VPN";
        else if ("auth_rejected".equals(lstConn)) {
            if ("not_registered".equals(lstDcReason)) detail = "Usuario no registrado";
            else if ("expired".equals(lstDcReason)) detail = "Usuario expirado";
            else if ("kick".equals(lstDcReason)) detail = "Sesión cerrada por el administrador";
            else detail = "Acceso denegado por el servidor";
        }
        else if ("manual_reconnect_required".equals(lstConn)) detail = "Requiere reconexión manual";
        else if ("retrying".equals(lstConn)) detail = getString(R.string.status_detail_connecting_search);
        else if ("dropped".equals(lstConn))  detail = getString(R.string.status_detail_connecting_reconnect);
        else if ("failed".equals(lstConn))   detail = getString(R.string.status_detail_connecting_retry);
        else                                             detail = getString(R.string.status_detail_connecting_default);
        if (!detail.equals(lstDtl)) {
            lstDtl = detail;
            stDtlsV.setVisibility(View.VISIBLE);
            if (canAnimate()) {
                stDtlsV.animate().cancel();
                stDtlsV.setAlpha(0.4f);
                stDtlsV.setText(detail);
                stDtlsV.animate().alpha(1f).setDuration(300).start();
            } else stDtlsV.setText(detail);
        }
    }


    private void animatePingTo(int targetPing) {
        if (pngV == null) return;
        int start = lstPing >= 0 ? lstPing : targetPing;
        if (!canAnimate() || lstPing < 0) {
            pngV.setText(String.valueOf(targetPing));
            pngV.setTextColor(resolvePingColor(targetPing));
            lstPing = targetPing; return;
        }
        ValueAnimator counter = ValueAnimator.ofInt(start, targetPing);
        counter.setDuration(400);
        counter.addUpdateListener(a -> pngV.setText(String.valueOf((int) a.getAnimatedValue())));
        counter.start();
        ValueAnimator colorAnim = ValueAnimator.ofObject(
            new ArgbEvaluator(), pngV.getCurrentTextColor(), resolvePingColor(targetPing));
        colorAnim.setDuration(600);
        colorAnim.addUpdateListener(a -> pngV.setTextColor((Integer) a.getAnimatedValue()));
        colorAnim.start();
        lstPing = targetPing;
    }

    private int resolvePingColor(int ping) {
        if (ping < 80)   return c(R.color.color_connected);
        if (ping <= 150) return c(R.color.color_accent);
        return c(R.color.color_connecting);
    }

    private void scheduleAutoDisconnectFromDays(int days) {
        h.removeCallbacks(autoDisconnectRunnable);
        if (days <= 0) { runAutoDisconnect(); return; }
        long delay = days * 24L * 60L * 60L * 1000L;
        autoDcMs = SystemClock.elapsedRealtime() + delay;
        h.postDelayed(autoDisconnectRunnable, delay);
    }

    private void runAutoDisconnect() {
        if (!BtVpnService.iActive()) return;
        stopVpn(); setHideInternalId(false);
        if (stDtlsV != null) {
            stDtlsV.setVisibility(View.VISIBLE);
            stDtlsV.setText("✖ Usuario expirado\nDesconexión automática local\nID: " + iid);
            stDtlsV.setTextColor(c(R.color.color_connecting));
        }
    }

    private void setUiState(UiState newState) {
        UiState prev         = uS;
        uS              = newState;
        boolean stateChanged = (prev != newState);
        if (newState != UiState.CONNECTING) h.removeCallbacks(connectingTimeoutRunnable);
        if (newState == UiState.CONNECTED) {
            if (stateChanged) startHevStatsLoop();
        } else {
            stopHevStatsLoop();
        }

        int accentColor = resolveAccentColor();

        if (stateChanged) {
            applyHudTheme(accentColor);
            animateDotColor(stDtV, stHlV, accentColor, 400);
            if (stHmV != null)
                stHmV.setBackgroundTintList(ColorStateList.valueOf(accentColor));
        }

        if (cBtn != null) {
            if (newState == UiState.CONNECTING) {
                cBtn.setEnabled(false); cBtn.setActivated(false);
                cBtn.setText(R.string.status_connecting);
                cBtn.setTextColor(c(R.color.color_connecting));
                if (stateChanged && canAnimate()) {
                    cBtn.setAlpha(0.7f);
                    cBtn.animate().alpha(1f).setDuration(300).start();
                }
            } else if (newState == UiState.CONNECTED) {
                cBtn.setEnabled(true); cBtn.setActivated(true);
                cBtn.setText(R.string.disconnect);
                cBtn.setTextColor(c(R.color.color_connected));
                if (stateChanged && canAnimate()) {
                    cBtn.setScaleX(0.92f); cBtn.setScaleY(0.92f);
                    cBtn.animate().scaleX(1f).scaleY(1f).setDuration(250).start();
                }
            } else {
                cBtn.setEnabled(true); cBtn.setActivated(false);
                cBtn.setText(BtProxy.iGm(this) ? getString(R.string.connect_gaming) : getString(R.string.connect));
                cBtn.setTextColor(c(R.color.color_text_primary));
                if (stateChanged && prev == UiState.CONNECTED && canAnimate()) {
                    cBtn.setAlpha(0.6f);
                    cBtn.animate().alpha(1f).setDuration(400).start();
                }
            }
        }

        if (stBdV != null) {
            String badgeText;
            int    badgeColor;
            if (newState == UiState.CONNECTING) {
                badgeText = getString(R.string.status_connecting); badgeColor = c(R.color.color_connecting);
                startStatusPulse(); startStatusHaloWave();
                startHudRingRotation();
            } else if (newState == UiState.CONNECTED) {
                badgeText  = getString(R.string.status_connected);
                badgeColor = BtProxy.iGm(this) ? c(R.color.color_gaming) : c(R.color.color_connected);
                stopStatusPulse(); stopStatusHaloWave();
                startHudRingRotation();
                if (stHlV != null) stHlV.setAlpha(0.15f);
            } else {
                badgeText  = getString(R.string.status_disconnected); badgeColor = c(R.color.color_disconnected);
                stopStatusPulse(); stopStatusHaloWave();
                stopHudRingRotation();
                if (stHlV != null) stHlV.setAlpha(0.20f);
                if (pngPlsV  != null) pngPlsV.setIdle();
            }
            if (stateChanged) {
                animateBadgeColor(stBdV, badgeColor, 350);
                if (!stBdV.getText().toString().equals(badgeText)) {
                    if (canAnimate()) {
                        stBdV.animate().cancel();
                        stBdV.setAlpha(0f);
                        stBdV.setText(badgeText);
                        stBdV.animate().alpha(1f).setDuration(250).start();
                    } else { stBdV.setText(badgeText); stBdV.setTextColor(badgeColor); }
                }
            } else { stBdV.setText(badgeText); stBdV.setTextColor(badgeColor); }
            stBdV.setShadowLayer(0f, 0f, 0f, 0);
        }

        if (stDtlsV != null) {
            if (newState == UiState.CONNECTING) {
                String detail;
                if      ("retrying".equals(lstConn)) detail = getString(R.string.status_detail_connecting_search);
                else if ("dropped".equals(lstConn))  detail = getString(R.string.status_detail_connecting_reconnect);
                else if ("failed".equals(lstConn))   detail = getString(R.string.status_detail_connecting_retry);
                else if ("native_start".equals(lstConn)) detail = "Abriendo túnel nativo...";
                else if ("proxy_connect_start".equals(lstConn)) detail = "Conectando al proxy...";
                else if ("proxy_connected".equals(lstConn)) detail = "Proxy conectado";
                else if ("server_auth_request".equals(lstConn)) detail = "Solicitando acceso al servidor...";
                else if ("access_granted".equals(lstConn)) detail = "Acceso concedido, preparando túnel...";
                else if ("relay_ready".equals(lstConn)) detail = "Túnel listo, creando VPN...";
                else if ("vpn_start".equals(lstConn)) detail = "Iniciando motor HEV...";
                else                                             detail = getString(R.string.status_detail_connecting_default);
                lstDtl = detail;
                stDtlsV.setVisibility(View.VISIBLE);
                stDtlsV.setTextColor(c(R.color.color_connecting));
                if (stateChanged && canAnimate()) {
                    stDtlsV.setAlpha(0f);
                    stDtlsV.setText(detail);
                    stDtlsV.animate().alpha(1f).setDuration(300).start();
                } else stDtlsV.setText(detail);
            } else if (newState == UiState.CONNECTED) {
                String full = getString(R.string.status_detail_connected);
                lstDtl = full;
                stDtlsV.setVisibility(View.VISIBLE);
                stDtlsV.setTextColor(c(R.color.color_connected));
                if (stateChanged && canAnimate())
                    stDtlsV.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_slide_in));
                stDtlsV.setText(full);
            } else {
                lstDtl = "";
                stDtlsV.setVisibility(View.GONE);
                if (pngV != null) {
                    pngV.setText("--");
                    if (canAnimate()) {
                        ValueAnimator va = ValueAnimator.ofObject(new ArgbEvaluator(),
                            pngV.getCurrentTextColor(), c(R.color.color_text_disabled));
                        va.setDuration(300);
                        va.addUpdateListener(a -> pngV.setTextColor((Integer) a.getAnimatedValue()));
                        va.start();
                    } else pngV.setTextColor(c(R.color.color_text_disabled));
                    lstPing = -1;
                }
            }
        }

        rfGmUi();
    }

    private static final class VisualDrawables {

        private VisualDrawables() {}

        public static android.graphics.drawable.Drawable cornerTL(int color) { return new CornerDrawable(color, 0); }
        public static android.graphics.drawable.Drawable cornerTR(int color) { return new CornerDrawable(color, 1); }
        public static android.graphics.drawable.Drawable cornerBL(int color) { return new CornerDrawable(color, 2); }
        public static android.graphics.drawable.Drawable cornerBR(int color) { return new CornerDrawable(color, 3); }

        public static android.graphics.drawable.Drawable ringOuter(int color) { return new RingDrawable(color, 6f, true,  20f); }
        public static android.graphics.drawable.Drawable ringMid  (int color) { return new RingDrawable(color, 2f, true,  12f); }
        public static android.graphics.drawable.Drawable ringInner(int color) { return new RingDrawable(color, 3f, false,  0f); }
        public static android.graphics.drawable.Drawable glowRing (int color) { return new GlowRingDrawable(color); }

        public static android.graphics.drawable.Drawable bRO(int color) { return new RingDrawable(color, 4f, true, 18f); }
        public static android.graphics.drawable.Drawable bRM  (int color) { return new RingDrawable(color, 2f, true, 10f); }
        public static android.graphics.drawable.Drawable btnConnect  (int color, boolean connected) { return new BtnCircleDrawable(color, connected); }

        public static android.graphics.drawable.Drawable statusBadge (int color) { return new StatusBadgeDrawable(color); }
        public static android.graphics.drawable.Drawable panelMetrics(int color) { return new PanelDrawable(color, true);  }
        public static android.graphics.drawable.Drawable panelData   (int color) { return new PanelDrawable(color, false); }
        public static android.graphics.drawable.Drawable panelId     (int color) { return new PanelDrawable(color, false); }
        public static android.graphics.drawable.Drawable crossLine   (int color) { return new CrossLineDrawable(color); }

        static final class CornerDrawable extends android.graphics.drawable.Drawable {
            private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final int variant;
            CornerDrawable(int color, int variant) { this.variant = variant; p.setColor(color); p.setStyle(android.graphics.Paint.Style.STROKE); p.setStrokeWidth(2.5f); p.setStrokeCap(android.graphics.Paint.Cap.SQUARE); }
            @Override public void draw(android.graphics.Canvas c) { float w=getBounds().width(), h=getBounds().height(), arm=w*0.55f; android.graphics.Path path=new android.graphics.Path(); switch (variant) { case 0: path.moveTo(0,arm); path.lineTo(0,0); path.lineTo(arm,0); break; case 1: path.moveTo(w-arm,0); path.lineTo(w,0); path.lineTo(w,arm); break; case 2: path.moveTo(0,h-arm); path.lineTo(0,h); path.lineTo(arm,h); break; case 3: path.moveTo(w-arm,h); path.lineTo(w,h); path.lineTo(w,h-arm); break; } c.drawPath(path,p); }
            @Override public void setAlpha(int a) { p.setAlpha(a); }
            @Override public void setColorFilter(android.graphics.ColorFilter cf) { p.setColorFilter(cf); }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        }
        static final class RingDrawable extends android.graphics.drawable.Drawable {
            private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            RingDrawable(int color, float strokeW, boolean dash, float dashLen) { p.setColor(color); p.setStyle(android.graphics.Paint.Style.STROKE); p.setStrokeWidth(strokeW); if (dash && dashLen > 0) p.setPathEffect(new android.graphics.DashPathEffect(new float[]{dashLen, dashLen * 0.5f}, 0)); }
            @Override public void draw(android.graphics.Canvas c) { float sw=p.getStrokeWidth(); android.graphics.RectF r=new android.graphics.RectF(sw,sw,getBounds().width()-sw,getBounds().height()-sw); c.drawOval(r,p); }
            @Override public void setAlpha(int a) { p.setAlpha(a); }
            @Override public void setColorFilter(android.graphics.ColorFilter cf) { p.setColorFilter(cf); }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        }
        static final class GlowRingDrawable extends android.graphics.drawable.Drawable {
            private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final int baseColor;
            GlowRingDrawable(int color) { baseColor=color; p.setStyle(android.graphics.Paint.Style.STROKE); p.setStrokeWidth(14f); }
            @Override public void draw(android.graphics.Canvas c) { float cx=getBounds().width()/2f, cy=getBounds().height()/2f, r=Math.min(cx,cy)-8f; p.setShader(new android.graphics.RadialGradient(cx, cy, r, new int[]{ android.graphics.Color.argb(0, android.graphics.Color.red(baseColor), android.graphics.Color.green(baseColor), android.graphics.Color.blue(baseColor)), android.graphics.Color.argb(120, android.graphics.Color.red(baseColor), android.graphics.Color.green(baseColor), android.graphics.Color.blue(baseColor)), android.graphics.Color.argb(0, android.graphics.Color.red(baseColor), android.graphics.Color.green(baseColor), android.graphics.Color.blue(baseColor)) }, new float[]{0.7f,0.9f,1f}, android.graphics.Shader.TileMode.CLAMP)); c.drawCircle(cx,cy,r,p); }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        }
        static final class BtnCircleDrawable extends android.graphics.drawable.Drawable {
            private final android.graphics.Paint fillP=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG), strokeP=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final int color; private final boolean connected;
            BtnCircleDrawable(int color, boolean connected) { this.color=color; this.connected=connected; fillP.setStyle(android.graphics.Paint.Style.FILL); strokeP.setStyle(android.graphics.Paint.Style.STROKE); strokeP.setStrokeWidth(2f); }
            @Override public void draw(android.graphics.Canvas c) { float cx=getBounds().width()/2f, cy=getBounds().height()/2f, r=Math.min(cx,cy)-3f; fillP.setColor(android.graphics.Color.argb(200,8,12,18)); c.drawCircle(cx,cy,r,fillP); strokeP.setColor(color); c.drawCircle(cx,cy,r,strokeP); if (connected) { android.graphics.Paint glow=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG); glow.setStyle(android.graphics.Paint.Style.STROKE); glow.setStrokeWidth(8f); glow.setColor(android.graphics.Color.argb(60, android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))); c.drawCircle(cx,cy,r-2f,glow);} }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        }
        static final class StatusBadgeDrawable extends android.graphics.drawable.Drawable {
            private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final int color;
            StatusBadgeDrawable(int color) { this.color=color; p.setStyle(android.graphics.Paint.Style.STROKE); p.setStrokeWidth(1.5f); }
            @Override public void draw(android.graphics.Canvas c) { float w=getBounds().width(), h=getBounds().height(); p.setColor(color); android.graphics.RectF r=new android.graphics.RectF(1,1,w-1,h-1); c.drawRoundRect(r,h/2f,h/2f,p); p.setStyle(android.graphics.Paint.Style.FILL); p.setColor(android.graphics.Color.argb(40, android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))); c.drawRoundRect(r,h/2f,h/2f,p); p.setStyle(android.graphics.Paint.Style.STROKE);}
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        }
        static final class PanelDrawable extends android.graphics.drawable.Drawable {
            private final android.graphics.Paint strokeP=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG), fillP=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG), cornerP=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final int color; private final boolean metric;
            PanelDrawable(int color, boolean metric) { this.color=color; this.metric=metric; strokeP.setStyle(android.graphics.Paint.Style.STROKE); strokeP.setStrokeWidth(1.2f); fillP.setStyle(android.graphics.Paint.Style.FILL); cornerP.setStyle(android.graphics.Paint.Style.STROKE); cornerP.setStrokeWidth(2f); cornerP.setStrokeCap(android.graphics.Paint.Cap.SQUARE); }
            @Override public void draw(android.graphics.Canvas c) { float w=getBounds().width(), h=getBounds().height(), radius=10f; android.graphics.RectF r=new android.graphics.RectF(1,1,w-1,h-1); fillP.setColor(android.graphics.Color.argb(metric?25:18, android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))); c.drawRoundRect(r,radius,radius,fillP); strokeP.setColor(android.graphics.Color.argb(70, android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))); c.drawRoundRect(r,radius,radius,strokeP); float arm=18f; cornerP.setColor(color); c.drawLine(2,arm,2,2,cornerP); c.drawLine(2,2,arm,2,cornerP); c.drawLine(w-arm,2,w-2,2,cornerP); c.drawLine(w-2,2,w-2,arm,cornerP); c.drawLine(2,h-arm,2,h-2,cornerP); c.drawLine(2,h-2,arm,h-2,cornerP); c.drawLine(w-arm,h-2,w-2,h-2,cornerP); c.drawLine(w-2,h-2,w-2,h-arm,cornerP);}
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        }
        static final class CrossLineDrawable extends android.graphics.drawable.Drawable {
            private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            CrossLineDrawable(int color) { p.setColor(color); p.setStyle(android.graphics.Paint.Style.FILL); }
            @Override public void draw(android.graphics.Canvas c) { c.drawRect(getBounds(), p); }
            @Override public void setAlpha(int a) { p.setAlpha(a); }
            @Override public void setColorFilter(android.graphics.ColorFilter cf) { p.setColorFilter(cf); }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        }
    }

}

class PingPulseView extends View {

    private static final int   MAX_POINTS     = 30;
    private static final long  SCROLL_DURATION = 2000L;
    private static final float IDLE_AMPLITUDE  = 4f;

    private final Deque<Float> pingHistory = new ArrayDeque<>();

    private final Paint linePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path  linePath   = new Path();

    private int   currentPingColor = Color.parseColor("#444444");
    private float scrollOffset     = 0f;
    private float blinkAlpha       = 1f;
    private boolean isActive       = false;

    private ValueAnimator scrollAnimator;
    private ValueAnimator blinkAnimator;

    public PingPulseView(Context context) {
        super(context);
        init();
    }

    public PingPulseView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PingPulseView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.5f);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(6f);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStrokeJoin(Paint.Join.ROUND);

        dotPaint.setStyle(Paint.Style.FILL);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(0.7f);
        gridPaint.setColor(Color.argb(20, 255, 255, 255));

        for (int i = 0; i < MAX_POINTS; i++) pingHistory.addLast(0f);

        startScrollAnimation();
        startBlinkAnimation();
    }

    private void startScrollAnimation() {
        if (scrollAnimator != null) scrollAnimator.cancel();
        scrollAnimator = ValueAnimator.ofFloat(0f, 1f);
        scrollAnimator.setDuration(SCROLL_DURATION);
        scrollAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scrollAnimator.setInterpolator(new LinearInterpolator());
        scrollAnimator.addUpdateListener(a -> {
            scrollOffset = (float) a.getAnimatedValue();
            invalidate();
        });
        scrollAnimator.start();
    }

    private void startBlinkAnimation() {
        if (blinkAnimator != null) blinkAnimator.cancel();
        blinkAnimator = ValueAnimator.ofFloat(1f, 0.2f, 1f);
        blinkAnimator.setDuration(1200);
        blinkAnimator.setRepeatCount(ValueAnimator.INFINITE);
        blinkAnimator.setInterpolator(new LinearInterpolator());
        blinkAnimator.addUpdateListener(a -> {
            blinkAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        blinkAnimator.start();
    }

    public void pushPing(int pingMs) {
        if (pingHistory.size() >= MAX_POINTS) pingHistory.pollFirst();
        pingHistory.addLast((float) pingMs);
        isActive = pingMs > 0;
        invalidate();
    }

    public void setLineColor(int color) {
        currentPingColor = color;
        invalidate();
    }

    public void setIdle() {
        isActive = false;
        pingHistory.clear();
        for (int i = 0; i < MAX_POINTS; i++) pingHistory.addLast(0f);
        currentPingColor = Color.parseColor("#444444");
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        drawGrid(canvas, w, h);

        Float[] arr = pingHistory.toArray(new Float[0]);
        float maxVal = 1f;
        for (Float v : arr) if (v > maxVal) maxVal = v;
        maxVal = Math.max(maxVal, 200f);

        float stepX = (float) w / (MAX_POINTS - 1);
        float shiftX = scrollOffset * stepX;

        linePath.reset();
        boolean first = true;
        int totalPadV = (int)(h * 0.15f);
        float drawH = h - totalPadV * 2f;

        for (int i = 0; i < arr.length; i++) {
            float rawVal = arr[i];
            float normalised;
            if (!isActive || rawVal <= 0f) {
                float idlePhase = (float)(i + scrollOffset * MAX_POINTS) / MAX_POINTS;
                normalised = 0.5f + (float)(Math.sin(idlePhase * Math.PI * 2) * IDLE_AMPLITUDE / drawH);
            } else {
                normalised = 1f - (rawVal / maxVal);
                normalised = Math.max(0.05f, Math.min(0.95f, normalised));
            }
            float x = i * stepX - shiftX + stepX;
            float y = totalPadV + normalised * drawH;
            if (first) { linePath.moveTo(x, y); first = false; }
            else        linePath.lineTo(x, y);
        }

        int glowColor = Color.argb(
            (int)(80 * (isActive ? blinkAlpha : 0.3f)),
            Color.red(currentPingColor),
            Color.green(currentPingColor),
            Color.blue(currentPingColor));

        glowPaint.setColor(glowColor);
        canvas.drawPath(linePath, glowPaint);

        int lineAlpha = isActive ? (int)(230 * blinkAlpha) : 60;
        linePaint.setColor(Color.argb(lineAlpha,
            Color.red(currentPingColor),
            Color.green(currentPingColor),
            Color.blue(currentPingColor)));

        LinearGradient grad = new LinearGradient(0, 0, w, 0,
            Color.argb(lineAlpha / 3, Color.red(currentPingColor), Color.green(currentPingColor), Color.blue(currentPingColor)),
            Color.argb(lineAlpha, Color.red(currentPingColor), Color.green(currentPingColor), Color.blue(currentPingColor)),
            Shader.TileMode.CLAMP);
        linePaint.setShader(grad);
        canvas.drawPath(linePath, linePaint);
        linePaint.setShader(null);

        if (isActive && arr.length > 0) {
            Float lastVal = arr[arr.length - 1];
            float normalised;
            if (lastVal <= 0f) {
                normalised = 0.5f;
            } else {
                normalised = 1f - (lastVal / maxVal);
                normalised = Math.max(0.05f, Math.min(0.95f, normalised));
            }
            float dotX = w - shiftX + stepX * 0.5f;
            float dotY = totalPadV + normalised * drawH;
            dotX = Math.min(dotX, w - 4f);

            dotPaint.setColor(Color.argb((int)(255 * blinkAlpha),
                Color.red(currentPingColor),
                Color.green(currentPingColor),
                Color.blue(currentPingColor)));
            dotPaint.setShadowLayer(8f, 0f, 0f, currentPingColor);
            canvas.drawCircle(dotX, dotY, 3.5f, dotPaint);
            dotPaint.setShadowLayer(0f, 0f, 0f, 0);
        }
    }

    private void drawGrid(Canvas canvas, int w, int h) {
        int rows = 3;
        for (int i = 1; i < rows; i++) {
            float y = (float) h / rows * i;
            canvas.drawLine(0, y, w, y, gridPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (scrollAnimator != null) scrollAnimator.cancel();
        if (blinkAnimator  != null) blinkAnimator.cancel();
    }
}

class ThroughputGraphView extends View {
    private static final int MAX = 36;
    private final Deque<Float> upH = new ArrayDeque<>();
    private final Deque<Float> dnH = new ArrayDeque<>();
    private final Paint upP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dnP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path upPath = new Path();
    private final Path dnPath = new Path();

    public ThroughputGraphView(Context c, AttributeSet a) {
        super(c, a);
        upP.setStyle(Paint.Style.STROKE); upP.setStrokeWidth(2.2f); upP.setColor(Color.parseColor("#3BEF8F"));
        dnP.setStyle(Paint.Style.STROKE); dnP.setStrokeWidth(2.2f); dnP.setColor(Color.parseColor("#44B7FF"));
        gridP.setStyle(Paint.Style.STROKE); gridP.setStrokeWidth(1f); gridP.setColor(Color.parseColor("#22FFFFFF"));
    }

    public void push(long up, long dn) {
        if (upH.size() >= MAX) upH.removeFirst();
        if (dnH.size() >= MAX) dnH.removeFirst();
        upH.addLast((float) up);
        dnH.addLast((float) dn);
        postInvalidateOnAnimation();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight(), m = 6f;
        c.drawLine(m, h * 0.33f, w - m, h * 0.33f, gridP);
        c.drawLine(m, h * 0.66f, w - m, h * 0.66f, gridP);
        if (upH.isEmpty() || dnH.isEmpty()) return;
        float max = 1f;
        for (Float v : upH) max = Math.max(max, v);
        for (Float v : dnH) max = Math.max(max, v);
        drawSeries(c, upH, upPath, upP, m, w, h, max);
        drawSeries(c, dnH, dnPath, dnP, m, w, h, max);
    }

    private void drawSeries(Canvas c, Deque<Float> hs, Path p, Paint paint, float m, float w, float h, float max) {
        p.reset();
        int i = 0, n = hs.size();
        for (Float v : hs) {
            float x = m + ((w - 2f * m) * i / Math.max(1, n - 1));
            float y = h - m - ((h - 2f * m) * (v / max));
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
            i++;
        }
        c.drawPath(p, paint);
    }
}
