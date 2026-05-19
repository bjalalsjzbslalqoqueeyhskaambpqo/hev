package com.blacktunnel;

import android.app.AlertDialog;
import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
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
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SimpleModeActivity extends ComponentActivity {

    private static final String PREF_UI               = "ui_state";
    private static final String KEY_HIDE_ID           = "hide_internal_id";
    private static final int    HOTSPOT_PROXY_PORT    = 7071;
    private static final long   CONNECTING_TIMEOUT_MS = 40_000L;
    private static final long   COLOR_TRANSITION_MS   = 500L;
    private static final long   FADE_IN_MS            = 280L;
    private static final long   BOUNCE_MS             = 320L;
    private static final int    PING_GRAPH_MAX_POINTS = 20;

    private enum UiState { DISCONNECTED, CONNECTING, CONNECTED }

    private Button          connectBtn;
    private Button          copyIdBtn;
    private TextView        statusBadgeView;
    private View            statusDotView;
    private View            statusHaloView;
    private View            statusHaloMidView;
    private TextView        statusDetailsView;
    private TextView        deviceIdView;
    private TextView        userValueView;
    private TextView        daysValueView;
    private TextView        pingValueView;
    private PingGraphView   pingGraphView;
    private Switch          gamingModeSwitch;
    private TextView        gamingModeBadgeView;
    private TextView        gamingDescriptionView;
    private TextView        gamingSelectedCountView;
    private Button          selectGamingAppsBtn;
    private LinearLayout    gamingModePanel;
    private LinearLayout    gamingControlsLayout;
    private View            panelConnectionView;
    private Switch          hotspotSwitch;
    private TextView        hotspotInfoView;

    private Animator        statusPulseAnimator;
    private Animator        statusHaloAnimator;
    private ValueAnimator   dotColorAnimator;
    private ValueAnimator   dotBlinkAnimator;

    private final ExecutorService appLoadExecutor = Executors.newSingleThreadExecutor();
    private String  internalId           = "";
    private UiState uiState              = UiState.DISCONNECTED;
    private long    connectingSinceMs    = 0L;
    private boolean lastRunning          = false;
    private long    autoDisconnectAtMs   = -1L;
    private String  authState            = "";
    private boolean hideInternalId       = false;
    private boolean applyingRuntimeChanges = false;
    private int     lastPingMs           = -1;
    private boolean handshakeConfirmed   = false;
    private String  lastKnownConnState   = "";
    private String  lastDetailText       = "";
    private boolean settingUiState       = false;

    private final Deque<Integer> pingHistory = new ArrayDeque<>();

    private final Runnable autoDisconnectRunnable = this::runAutoDisconnect;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<Intent> vpnPermissionLauncher;

    private final Runnable stateTicker = new Runnable() {
        @Override
        public void run() {
            try {
                String logs = BtVpnService.dumpLogs();
                syncStateFromLogs(logs);
                refreshFromLogs(logs);
            } catch (Throwable ignored) {}
            handler.postDelayed(this, 3_000);
        }
    };

    // ─── Vista interna de gráfica de ping ────────────────────────────────────

    public static final class PingGraphView extends View {

        private final Paint linePaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path  linePath    = new Path();
        private final Path  fillPath    = new Path();
        private final Deque<Integer> data;
        private int maxPoints           = 20;
        private int activeColor         = Color.parseColor("#4CFFA500");
        private int lineColor           = Color.parseColor("#FFA500");
        private boolean blinkOn         = true;

        public PingGraphView(Context ctx) {
            super(ctx);
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(3.5f);
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            linePaint.setStrokeJoin(Paint.Join.ROUND);
            fillPaint.setStyle(Paint.Style.FILL);
            dotPaint.setStyle(Paint.Style.FILL);
            data = new ArrayDeque<>();
        }

        public void setData(Deque<Integer> source, int maxPts) {
            data.clear();
            data.addAll(source);
            maxPoints = maxPts;
        }

        public void setColors(int line, int fill) {
            lineColor   = line;
            activeColor = fill;
            invalidate();
        }

        public void setBlinkState(boolean on) {
            blinkOn = on;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0 || data.size() < 2) return;

            Integer[] pts = data.toArray(new Integer[0]);
            int visMax = 0;
            for (int v : pts) if (v > visMax) visMax = v;
            if (visMax < 50) visMax = 50;
            float pad = h * 0.12f;
            float usableH = h - pad * 2;

            float step = (float) w / Math.max(maxPoints - 1, 1);
            float startX = w - step * (pts.length - 1);

            linePath.reset();
            fillPath.reset();

            float firstX = startX;
            float firstY = pad + usableH - (pts[0] / (float) visMax) * usableH;
            linePath.moveTo(firstX, firstY);
            fillPath.moveTo(firstX, h);
            fillPath.lineTo(firstX, firstY);

            for (int i = 1; i < pts.length; i++) {
                float x  = startX + step * i;
                float y  = pad + usableH - (pts[i] / (float) visMax) * usableH;
                float cx = (startX + step * (i - 1) + x) / 2f;
                linePath.cubicTo(cx, pad + usableH - (pts[i-1] / (float) visMax) * usableH, cx, y, x, y);
                fillPath.cubicTo(cx, pad + usableH - (pts[i-1] / (float) visMax) * usableH, cx, y, x, y);
            }

            float lastX = startX + step * (pts.length - 1);
            fillPath.lineTo(lastX, h);
            fillPath.close();

            fillPaint.setShader(new LinearGradient(0, 0, 0, h, activeColor, Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawPath(fillPath, fillPaint);

            linePaint.setColor(lineColor);
            canvas.drawPath(linePath, linePaint);

            if (blinkOn) {
                float lastY = pad + usableH - (pts[pts.length - 1] / (float) visMax) * usableH;
                dotPaint.setColor(lineColor);
                canvas.drawCircle(lastX, lastY, 5f, dotPaint);
                dotPaint.setColor(Color.argb(60, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor)));
                canvas.drawCircle(lastX, lastY, 10f, dotPaint);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ciclo de vida
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void attachBaseContext(Context newBase) {
        Configuration cfg = new Configuration(newBase.getResources().getConfiguration());
        cfg.fontScale = 1.0f;
        super.attachBaseContext(newBase.createConfigurationContext(cfg));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_mode);

        vpnPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) startVpn();
                else                                      setUiState(UiState.DISCONNECTED);
            }
        );

        bindViews();
        initData();
        initListeners();
        refreshGamingModeUi();
        setupConnectivityMonitor();
        animateInitialEntrance();
        handler.post(stateTicker);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(stateTicker);
        handler.removeCallbacks(autoDisconnectRunnable);
        cancelAllAnimators();
        if (connectivityManager != null && networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); }
            catch (Throwable ignored) {}
        }
        BtVpnService.stopLocalProxy();
        appLoadExecutor.shutdownNow();
        super.onDestroy();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inicialización
    // ─────────────────────────────────────────────────────────────────────────

    private void bindViews() {
        connectBtn              = findViewById(R.id.btnConnect);
        copyIdBtn               = findViewById(R.id.btnCopyLogs);
        statusBadgeView         = findViewById(R.id.txtStatusBadge);
        statusDotView           = findViewById(R.id.viewStatusDot);
        statusHaloView          = findViewById(R.id.viewStatusHalo);
        statusHaloMidView       = findViewById(R.id.viewStatusHaloMid);
        statusDetailsView       = findViewById(R.id.txtStatusDetails);
        deviceIdView            = findViewById(R.id.txtDeviceId);
        userValueView           = findViewById(R.id.txtUser);
        daysValueView           = findViewById(R.id.txtDays);
        pingValueView           = findViewById(R.id.txtPingValue);
        pingGraphView           = findViewById(R.id.pingGraphView);
        gamingModeSwitch        = findViewById(R.id.switchGamingMode);
        gamingModeBadgeView     = findViewById(R.id.txtGamingBadge);
        gamingDescriptionView   = findViewById(R.id.txtGamingDescription);
        gamingSelectedCountView = findViewById(R.id.txtGamingSelectedCount);
        selectGamingAppsBtn     = findViewById(R.id.btnSelectGamingApps);
        gamingModePanel         = findViewById(R.id.panelGamingMode);
        gamingControlsLayout    = findViewById(R.id.layoutGamingControls);
        panelConnectionView     = findViewById(R.id.panelConnection);
        hotspotSwitch           = findViewById(R.id.switchHotspot);
        hotspotInfoView         = findViewById(R.id.txtHotspotInfo);
    }

    private void initData() {
        internalId     = BtProxy.getOrCreateInternalId(this);
        hideInternalId = getSharedPreferences(PREF_UI, MODE_PRIVATE).getBoolean(KEY_HIDE_ID, false);
        BtProxy.applyStoredGamingMode(this);

        if (deviceIdView != null) deviceIdView.setText("ID: " + internalId);
        refreshDeviceIdVisibility();

        boolean running = BtVpnService.isRunningState();
        lastRunning = running;
        setUiState(running ? UiState.CONNECTED : UiState.DISCONNECTED);
    }

    private void initListeners() {
        connectBtn.setOnClickListener(v -> {
            if (uiState == UiState.CONNECTING) return;
            performHaptic(v);
            if (uiState == UiState.CONNECTED) stopVpn();
            else                              startVpnWithPermission();
        });

        copyIdBtn.setOnClickListener(v -> {
            performHaptic(v);
            copyInternalIdToClipboard();
        });

        if (gamingModeSwitch != null) {
            gamingModeSwitch.setChecked(BtProxy.isGamingMode(this));
            gamingModeSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
                BtProxy.setGamingMode(this, isChecked);
                refreshGamingModeUi();
                if (BtVpnService.isRunningState()) {
                    showGamingApplyFeedback(
                        isChecked ? "Modo apps: activando…"  : "Modo normal: aplicando…",
                        isChecked ? "Modo apps activo"        : "Modo normal activo",
                        isChecked ? R.color.color_gaming      : R.color.color_text_secondary
                    );
                    applyGamingChangesIfRunning();
                }
            });
        }

        if (selectGamingAppsBtn != null)
            selectGamingAppsBtn.setOnClickListener(v -> openGamingAppsDialog());

        if (hotspotSwitch != null) {
            hotspotSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
                if (!isChecked) {
                    safeSetVisible(hotspotInfoView, false);
                    return;
                }
                String ip = BtVpnService.getHotspotIp();
                if (ip == null) {
                    hotspotSwitch.setChecked(false);
                    showToast("Activá el hotspot WiFi primero");
                    return;
                }
                if (hotspotInfoView != null) {
                    hotspotInfoView.setText("Próximamente disponible");
                    animateFadeIn(hotspotInfoView, FADE_IN_MS);
                }
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Animación de entrada
    // ─────────────────────────────────────────────────────────────────────────

    private void animateInitialEntrance() {
        if (!canAnimate()) return;
        View[] panels = {
            panelConnectionView,
            findViewById(R.id.layoutDataStrip),
            connectBtn,
            gamingModePanel,
        };
        for (int i = 0; i < panels.length; i++) {
            View panel = panels[i];
            if (panel == null) continue;
            panel.setAlpha(0f);
            panel.setTranslationY(30f);
            long delay = 60L * i;
            panel.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delay)
                .setDuration(380)
                .setInterpolator(new DecelerateInterpolator(1.8f))
                .start();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Conectividad
    // ─────────────────────────────────────────────────────────────────────────

    private void setupConnectivityMonitor() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLost(Network network) {
                if (BtVpnService.isRunningState()) stopVpn();
                setUiState(UiState.DISCONNECTED);
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (caps == null) return;
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && uiState == UiState.CONNECTING) {
                    setUiState(UiState.DISCONNECTED);
                }
            }
        };

        try {
            connectivityManager.registerNetworkCallback(
                new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback
            );
        } catch (Throwable ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VPN
    // ─────────────────────────────────────────────────────────────────────────

    private void startVpnWithPermission() {
        setUiState(UiState.CONNECTING);
        authState          = "";
        lastKnownConnState = "";
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) vpnPermissionLauncher.launch(prepare);
        else                 startVpn();
    }

    private void startVpn() {
        Intent i = new Intent(this, BtVpnService.class);
        i.setAction(BtVpnService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else                                                  startService(i);
        connectingSinceMs  = SystemClock.elapsedRealtime();
        handshakeConfirmed = false;
        lastKnownConnState = "";
        setUiState(UiState.CONNECTING);
    }

    private void stopVpn() {
        Intent i = new Intent(this, BtVpnService.class);
        i.setAction(BtVpnService.ACTION_STOP);
        startService(i);
        handshakeConfirmed = false;
        lastKnownConnState = "";
        setUiState(UiState.DISCONNECTED);
        handler.removeCallbacks(autoDisconnectRunnable);
        autoDisconnectAtMs = -1L;
    }

    private void applyGamingChangesIfRunning() {
        if (!BtVpnService.isRunningState()) return;
        applyingRuntimeChanges = true;
        connectingSinceMs      = SystemClock.elapsedRealtime();
        lastKnownConnState     = "";
        setUiState(UiState.CONNECTING);
        Intent i = new Intent(this, BtVpnService.class);
        i.setAction(BtVpnService.ACTION_APPLY);
        startService(i);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sincronización de estado desde logs
    // ─────────────────────────────────────────────────────────────────────────

    private void syncStateFromLogs(String logs) {
        boolean running   = BtVpnService.isRunningState();
        String  connState = findLatestConnectionState(logs);

        if (!connState.isEmpty() && !connState.equals(lastKnownConnState)) {
            lastKnownConnState = connState;
        }
        if ("connected".equals(connState)) handshakeConfirmed = true;

        if (running && !lastRunning) {
            authState              = "";
            applyingRuntimeChanges = false;
            setUiState(handshakeConfirmed ? UiState.CONNECTED : UiState.CONNECTING);

        } else if (!running && lastRunning) {
            applyingRuntimeChanges = false;
            handshakeConfirmed     = false;
            setUiState(UiState.DISCONNECTED);

        } else if (running && handshakeConfirmed) {
            applyingRuntimeChanges = false;
            if (uiState != UiState.CONNECTED) setUiState(UiState.CONNECTED);

        } else if (running && !handshakeConfirmed) {
            if ("failed".equals(lastKnownConnState)) {
                long elapsed = SystemClock.elapsedRealtime() - connectingSinceMs;
                if (elapsed > CONNECTING_TIMEOUT_MS) {
                    applyingRuntimeChanges = false;
                    stopVpn();
                }
            } else if (uiState != UiState.CONNECTING) {
                setUiState(UiState.CONNECTING);
            } else {
                refreshConnectingDetail();
            }

        } else if (!running && uiState == UiState.CONNECTING) {
            long elapsed = SystemClock.elapsedRealtime() - connectingSinceMs;
            if (elapsed > CONNECTING_TIMEOUT_MS) {
                applyingRuntimeChanges = false;
                setUiState(UiState.DISCONNECTED);
            } else {
                refreshConnectingDetail();
            }
        }

        lastRunning = running;
    }

    private void refreshConnectingDetail() {
        if (statusDetailsView == null) return;
        String detail = buildConnectingDetailText();
        if (detail.equals(lastDetailText)) return;
        lastDetailText = detail;
        safeSetVisible(statusDetailsView, true);
        if (canAnimate()) {
            statusDetailsView.animate().cancel();
            statusDetailsView.setAlpha(0.3f);
            statusDetailsView.setText(detail);
            statusDetailsView.animate().alpha(1f).setDuration(280).start();
        } else {
            statusDetailsView.setText(detail);
        }
    }

    private String buildConnectingDetailText() {
        switch (lastKnownConnState) {
            case "retrying": return "• Buscando servidor disponible…";
            case "dropped":  return "• Reconectando al servidor…";
            case "failed":   return "• Reintentando conexión…";
            default:         return "• Estableciendo conexión…";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lectores de logs
    // ─────────────────────────────────────────────────────────────────────────

    private void refreshFromLogs(String logs) {
        updateServerAuthStatus(logs);
        updateUserMetadata(logs);
    }

    private void updateServerAuthStatus(String logs) {
        if (logs == null || statusDetailsView == null) return;
        String latestAuth = findLatestAuthState(logs);

        if ("not_registered".equals(latestAuth)) {
            if ("not_registered".equals(authState)) return;
            authState = "not_registered";
            setHideInternalId(false);
            if (BtVpnService.isRunningState()) stopVpn();
            setUiState(UiState.DISCONNECTED);
            showErrorDetail(
                "✖ Usuario no registrado\nCompartí tu ID para habilitación\nID: " + internalId,
                c(R.color.color_disconnected)
            );

        } else if ("expired".equals(latestAuth)) {
            if ("expired".equals(authState)) return;
            authState = "expired";
            setHideInternalId(false);
            if (BtVpnService.isRunningState()) stopVpn();
            setUiState(UiState.DISCONNECTED);
            showErrorDetail(
                "✖ Acceso expirado\nRenovalo con soporte\nID: " + internalId,
                c(R.color.color_connecting)
            );

        } else if ("ok".equals(latestAuth)) {
            authState = "";
            setHideInternalId(true);
        }
    }

    private void showErrorDetail(String message, int textColor) {
        safeSetVisible(statusDetailsView, true);
        if (canAnimate()) {
            statusDetailsView.animate().cancel();
            statusDetailsView.setAlpha(0f);
            statusDetailsView.setText(message);
            statusDetailsView.setTextColor(textColor);
            statusDetailsView.animate().alpha(1f).setDuration(FADE_IN_MS).start();
        } else {
            statusDetailsView.setText(message);
            statusDetailsView.setTextColor(textColor);
        }
        if (connectBtn != null)
            connectBtn.startAnimation(AnimationUtils.loadAnimation(this, R.anim.shake));
    }

    private String findLatestAuthState(String logs) {
        String[] lines = logs.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i] == null) continue;
            String lower = lines[i].trim().toLowerCase(Locale.ROOT);
            if (lower.isEmpty()) continue;
            if (lower.contains("usuario no registrado") || lower.contains("not_registered")) return "not_registered";
            if (lower.contains("usuario expirado")      || lower.contains("expired"))        return "expired";
            if (lower.contains("user_name=") || lower.contains("user_days=") || lower.contains("ping_ms=")) return "ok";
        }
        return "";
    }

    private String findLatestConnectionState(String logs) {
        if (logs == null || logs.isEmpty()) return "";
        String[] lines = logs.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i] == null) continue;
            String lower = lines[i].trim().toLowerCase(Locale.ROOT);
            if (lower.isEmpty()) continue;

            if (lower.contains("tunnel ok")  ||
                lower.contains("user_name=") ||
                lower.contains("user_days=") ||
                lower.contains("ping_ms="))   return "connected";

            if (lower.contains("conectando...")      ||
                lower.contains("probando ")           ||
                lower.contains("ips estaticas fallaron") ||
                lower.contains("dns ")                ||
                lower.contains("relay listo"))         return "retrying";

            if (lower.contains("tunnel caido")       ||
                lower.contains("pong timeout")        ||
                lower.contains("tunnel read failed")  ||
                lower.contains("payload too large")   ||
                lower.contains("payload read failed")) return "dropped";

            if (lower.contains("handshake failed") ||
                lower.contains("proxy no responde") ||
                lower.contains("getaddrinfo fallo") ||
                lower.contains("connect failed"))   return "failed";
        }
        return "";
    }

    private void updateUserMetadata(String logs) {
        if (logs == null) return;
        String[] lines = logs.split("\n");

        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            int idx = line.indexOf("user_name=");
            if (idx >= 0 && userValueView != null) {
                String v = line.substring(idx + "user_name=".length()).trim();
                if (!v.isEmpty()) userValueView.setText(v);
                break;
            }
        }

        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            int idx = line.indexOf("user_days=");
            if (idx >= 0 && daysValueView != null) {
                String v = line.substring(idx + "user_days=".length()).trim();
                if (!v.isEmpty()) {
                    daysValueView.setText(v + " d");
                    try {
                        int days = Integer.parseInt(v.replaceAll("[^0-9]", ""));
                        int colorRes = days < 3
                            ? R.color.color_disconnected
                            : days < 7
                                ? R.color.color_connecting
                                : R.color.color_connected;
                        daysValueView.setTextColor(c(colorRes));
                        scheduleAutoDisconnectFromDays(days);
                    } catch (Exception ignored) {}
                }
                break;
            }
        }

        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            int idx = line.indexOf("ping_ms=");
            if (idx >= 0) {
                String v = line.substring(idx + "ping_ms=".length()).trim();
                if (!v.isEmpty()) {
                    try {
                        int ping = Integer.parseInt(v.replaceAll("[^0-9]", ""));
                        pushPingHistory(ping);
                        animatePingTo(ping);
                    } catch (Exception ignored) { resetPingView(); }
                }
                break;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Historial y gráfica de ping
    // ─────────────────────────────────────────────────────────────────────────

    private void pushPingHistory(int ping) {
        pingHistory.addLast(ping);
        while (pingHistory.size() > PING_GRAPH_MAX_POINTS) pingHistory.pollFirst();
        if (pingGraphView != null) {
            int color = resolvePingColor(ping);
            int fill  = Color.argb(60, Color.red(color), Color.green(color), Color.blue(color));
            pingGraphView.setData(pingHistory, PING_GRAPH_MAX_POINTS);
            pingGraphView.setColors(color, fill);
            pingGraphView.setVisibility(View.VISIBLE);
        }
    }

    private void startPingGraphBlink() {
        if (pingGraphView == null) return;
        handler.removeCallbacksAndMessages("ping_blink");
        final boolean[] state = { true };
        Runnable blinkRunnable = new Runnable() {
            @Override
            public void run() {
                state[0] = !state[0];
                pingGraphView.setBlinkState(state[0]);
                handler.postDelayed(this, 700);
            }
        };
        handler.postDelayed(blinkRunnable, 700);
    }

    private void stopPingGraphBlink() {
        handler.removeCallbacksAndMessages("ping_blink");
        if (pingGraphView != null) pingGraphView.setBlinkState(false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI de estado principal
    // ─────────────────────────────────────────────────────────────────────────

    private void setUiState(UiState newState) {
        if (settingUiState) return;
        settingUiState = true;
        try {
            applyUiState(newState);
        } finally {
            settingUiState = false;
        }
    }

    private void applyUiState(UiState newState) {
        final UiState prev        = uiState;
        uiState                   = newState;
        final boolean stateChanged = prev != newState;

        updateConnectButton(newState, prev, stateChanged);
        updateStatusDot(newState, stateChanged);
        updateStatusBadge(newState, stateChanged);
        updateStatusDetails(newState, stateChanged);
        refreshGamingModeUi();

        if (newState == UiState.CONNECTED) {
            startPingGraphBlink();
        } else {
            stopPingGraphBlink();
            if (newState == UiState.DISCONNECTED && pingGraphView != null) {
                pingHistory.clear();
                pingGraphView.setVisibility(View.GONE);
            }
        }
    }

    private void updateConnectButton(UiState newState, UiState prev, boolean stateChanged) {
        if (connectBtn == null) return;
        switch (newState) {
            case CONNECTING:
                connectBtn.setEnabled(false);
                connectBtn.setActivated(false);
                connectBtn.setText("Conectando…");
                connectBtn.setBackgroundResource(R.drawable.btn_connecting_selector);
                connectBtn.setTextColor(c(R.color.color_text_primary));
                if (stateChanged && canAnimate()) {
                    connectBtn.setAlpha(0.65f);
                    connectBtn.animate().alpha(1f).setDuration(350).start();
                }
                break;

            case CONNECTED:
                connectBtn.setEnabled(true);
                connectBtn.setActivated(true);
                connectBtn.setText(R.string.disconnect);
                connectBtn.setBackgroundResource(R.drawable.btn_disconnect_selector);
                connectBtn.setTextColor(c(R.color.color_text_primary));
                if (stateChanged && canAnimate()) {
                    connectBtn.setScaleX(0.88f);
                    connectBtn.setScaleY(0.88f);
                    connectBtn.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(BOUNCE_MS)
                        .setInterpolator(new OvershootInterpolator(2.2f))
                        .start();
                }
                break;

            default:
                connectBtn.setEnabled(true);
                connectBtn.setActivated(false);
                connectBtn.setText(BtProxy.isGamingMode(this)
                    ? getString(R.string.connect_gaming)
                    : getString(R.string.connect));
                connectBtn.setBackgroundResource(R.drawable.btn_connect_selector);
                connectBtn.setTextColor(0xFF050505);
                if (stateChanged && prev == UiState.CONNECTED && canAnimate()) {
                    connectBtn.setAlpha(0.55f);
                    connectBtn.animate().alpha(1f).setDuration(400).start();
                }
                break;
        }
    }

    private void updateStatusDot(UiState newState, boolean stateChanged) {
        int dotColor = resolveStatusColor(newState);
        long duration = stateChanged ? COLOR_TRANSITION_MS : 0;

        if (dotColorAnimator != null) dotColorAnimator.cancel();
        if (dotBlinkAnimator != null) { dotBlinkAnimator.cancel(); dotBlinkAnimator = null; }

        if (stateChanged && canAnimate()) {
            int fromColor = statusDotView != null && statusDotView.getBackgroundTintList() != null
                ? statusDotView.getBackgroundTintList().getDefaultColor()
                : dotColor;
            dotColorAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), fromColor, dotColor);
            dotColorAnimator.setDuration(duration);
            dotColorAnimator.addUpdateListener(a -> {
                int col = (Integer) a.getAnimatedValue();
                applyTint(statusDotView,       col);
                applyTint(statusHaloView,      col);
                applyTint(statusHaloMidView,   col);
            });
            dotColorAnimator.start();
        } else {
            applyTint(statusDotView,     dotColor);
            applyTint(statusHaloView,    dotColor);
            applyTint(statusHaloMidView, dotColor);
        }

        switch (newState) {
            case CONNECTING:
                startStatusPulse();
                startStatusHaloWave();
                break;
            case CONNECTED:
                stopStatusPulse();
                stopStatusHaloWave();
                startDotBlink(dotColor);
                safeSetAlpha(statusHaloView, 0.18f);
                safeSetAlpha(statusHaloMidView, 0.28f);
                break;
            default:
                stopStatusPulse();
                stopStatusHaloWave();
                safeSetAlpha(statusHaloView, 0.15f);
                safeSetAlpha(statusHaloMidView, 0.00f);
                break;
        }
    }

    private void startDotBlink(int color) {
        if (statusDotView == null || !canAnimate()) return;
        int dimColor = Color.argb(120,
            Color.red(color), Color.green(color), Color.blue(color));
        dotBlinkAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), color, dimColor);
        dotBlinkAnimator.setDuration(900);
        dotBlinkAnimator.setRepeatMode(ValueAnimator.REVERSE);
        dotBlinkAnimator.setRepeatCount(ValueAnimator.INFINITE);
        dotBlinkAnimator.addUpdateListener(a -> applyTint(statusDotView, (Integer) a.getAnimatedValue()));
        dotBlinkAnimator.start();
    }

    private void updateStatusBadge(UiState newState, boolean stateChanged) {
        if (statusBadgeView == null) return;
        String badgeText  = resolveBadgeText(newState);
        int    badgeColor = resolveBadgeColor(newState);

        if (stateChanged) {
            animateBadgeColor(statusBadgeView, badgeColor, 400L);
            if (!statusBadgeView.getText().toString().equals(badgeText)) {
                if (canAnimate()) {
                    statusBadgeView.animate().cancel();
                    statusBadgeView.setAlpha(0f);
                    statusBadgeView.setText(badgeText);
                    statusBadgeView.animate().alpha(1f).setDuration(250).start();
                } else {
                    statusBadgeView.setText(badgeText);
                    statusBadgeView.setTextColor(badgeColor);
                }
            }
        } else {
            statusBadgeView.setText(badgeText);
            statusBadgeView.setTextColor(badgeColor);
        }
        statusBadgeView.setShadowLayer(0f, 0f, 0f, 0);
    }

    private void updateStatusDetails(UiState newState, boolean stateChanged) {
        if (statusDetailsView == null) return;

        switch (newState) {
            case CONNECTING: {
                String detail = buildConnectingDetailText();
                lastDetailText = detail;
                safeSetVisible(statusDetailsView, true);
                statusDetailsView.setTextColor(c(R.color.color_connecting));
                if (stateChanged && canAnimate()) {
                    statusDetailsView.setAlpha(0f);
                    statusDetailsView.setText(detail);
                    statusDetailsView.animate().alpha(1f).setDuration(280).start();
                } else {
                    statusDetailsView.setText(detail);
                }
                break;
            }
            case CONNECTED: {
                String full = "✓ Conexión activa";
                lastDetailText = full;
                safeSetVisible(statusDetailsView, true);
                statusDetailsView.setTextColor(c(R.color.color_text_secondary));
                if (stateChanged && canAnimate())
                    statusDetailsView.startAnimation(
                        AnimationUtils.loadAnimation(this, R.anim.fade_slide_in));
                statusDetailsView.setText(full);
                break;
            }
            default: {
                lastDetailText = "";
                safeSetVisible(statusDetailsView, false);
                resetPingView();
                break;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gaming mode
    // ─────────────────────────────────────────────────────────────────────────

    private void refreshGamingModeUi() {
        boolean      enabled  = BtProxy.isGamingMode(this);
        List<String> selected = BtProxy.getGamingSelectedPackages(this);

        if (gamingModeBadgeView != null) {
            if (enabled) {
                gamingModeBadgeView.setText(R.string.gaming_mode_on_compact);
                gamingModeBadgeView.setTextColor(c(R.color.color_gaming));
                gamingModeBadgeView.setBackgroundResource(R.drawable.strike_chip_left);
                gamingModeBadgeView.setLetterSpacing(0.12f);
            } else {
                gamingModeBadgeView.setText(R.string.gaming_mode_off_compact);
                gamingModeBadgeView.setTextColor(c(R.color.color_text_disabled));
                gamingModeBadgeView.setBackground(null);
            }
            gamingModeBadgeView.setShadowLayer(0f, 0f, 0f, 0);
        }

        safeSetVisible(gamingDescriptionView, enabled);

        if (gamingSelectedCountView != null) {
            if (selected.isEmpty()) {
                gamingSelectedCountView.setText("Ninguna app seleccionada");
                gamingSelectedCountView.setTextColor(c(R.color.color_text_disabled));
            } else {
                String first   = selected.get(0);
                int    rest    = selected.size() - 1;
                String summary = rest > 0 ? first + " +" + rest + " más" : first;
                gamingSelectedCountView.setText(summary);
                gamingSelectedCountView.setTextColor(c(R.color.color_gaming));
            }
        }

        if (selectGamingAppsBtn != null) {
            selectGamingAppsBtn.setEnabled(enabled);
            selectGamingAppsBtn.animate().alpha(enabled ? 1f : 0.50f).setDuration(200).start();
        }

        if (gamingControlsLayout != null) {
            if (enabled) {
                gamingControlsLayout.setVisibility(View.VISIBLE);
                if (canAnimate()) {
                    gamingControlsLayout.setAlpha(0f);
                    gamingControlsLayout.setTranslationY(12f);
                    gamingControlsLayout.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(220)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
                } else {
                    gamingControlsLayout.setAlpha(1f);
                }
            } else {
                if (canAnimate()) {
                    gamingControlsLayout.animate()
                        .alpha(0f)
                        .setDuration(160)
                        .withEndAction(() -> gamingControlsLayout.setVisibility(View.GONE))
                        .start();
                } else {
                    gamingControlsLayout.setVisibility(View.GONE);
                }
            }
        }

        if (gamingModePanel    != null) gamingModePanel.setActivated(enabled);
        if (panelConnectionView != null)
            panelConnectionView.setSelected(enabled && uiState == UiState.CONNECTED);

        if (connectBtn != null && uiState != UiState.CONNECTED && uiState != UiState.CONNECTING)
            connectBtn.setText(enabled ? getString(R.string.connect_gaming) : getString(R.string.connect));
    }

    private void showGamingApplyFeedback(String start, String done, int colorRes) {
        if (gamingModeBadgeView == null) return;
        gamingModeBadgeView.setText(start);
        gamingModeBadgeView.setTextColor(c(colorRes));
        gamingModeBadgeView.animate().cancel();
        gamingModeBadgeView.setAlpha(0.5f);
        gamingModeBadgeView.animate().alpha(1f).setDuration(200).start();
        handler.postDelayed(() -> {
            if (gamingModeBadgeView != null) gamingModeBadgeView.setText(done);
            handler.postDelayed(this::refreshGamingModeUi, 600);
        }, 600);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Diálogo de selección de apps para Gaming
    // ─────────────────────────────────────────────────────────────────────────

    private void openGamingAppsDialog() {
        View dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_gaming_apps, null, false);
        EditText     searchView     = dialogView.findViewById(R.id.editSearchApps);
        TextView     counterView    = dialogView.findViewById(R.id.txtPickerCounter);
        LinearLayout selectedLayout = dialogView.findViewById(R.id.layoutSelectedApps);
        ListView     listView       = dialogView.findViewById(R.id.listGamingApps);

        counterView.setText(getString(R.string.gaming_loading_apps));
        Set<String> selectedPackages = new HashSet<>(BtProxy.getGamingSelectedPackages(this));
        listView.setEnabled(false);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setView(dialogView)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar", (d, which) -> {
                BtProxy.setGamingSelectedPackages(this, new ArrayList<>(selectedPackages));
                refreshGamingModeUi();
                showGamingApplyFeedback(
                    "Aplicando selección…",
                    "Selección guardada",
                    R.color.color_accent
                );
                applyGamingChangesIfRunning();
            })
            .create();

        appLoadExecutor.execute(() -> {
            List<AppOption> allApps = loadInstalledUserApps();
            runOnUiThread(() -> bindGamingDialogContent(
                searchView, counterView, selectedLayout, listView, selectedPackages, allApps));
        });

        dialog.show();
    }

    private void bindGamingDialogContent(
            EditText searchView, TextView counterView,
            LinearLayout selectedLayout, ListView listView,
            Set<String> selectedPackages, List<AppOption> allApps) {

        GamingAppsAdapter adapter = new GamingAppsAdapter(allApps, selectedPackages);
        listView.setAdapter(adapter);
        listView.setEnabled(true);

        final Runnable[] refreshPinned = { null };
        refreshPinned[0] = () -> {
            selectedLayout.removeAllViews();
            counterView.setText(getString(R.string.gaming_selected_count, selectedPackages.size()));
            for (AppOption app : allApps) {
                if (!selectedPackages.contains(app.packageName)) continue;
                Button chip = buildChip(app.appName + " ✕");
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
                showToast("Máximo 3 apps en modo seleccionado");
                adapter.notifyDataSetChanged();
                return;
            }
            if (checked) selectedPackages.add(app.packageName);
            else         selectedPackages.remove(app.packageName);
            adapter.notifyDataSetChanged();
            refreshPinned[0].run();
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            AppOption app     = adapter.getItem(position);
            boolean   checked = !selectedPackages.contains(app.packageName);
            if (checked && selectedPackages.size() >= 3) {
                showToast("Máximo 3 apps en modo seleccionado");
                return;
            }
            if (checked) selectedPackages.add(app.packageName);
            else         selectedPackages.remove(app.packageName);
            adapter.notifyDataSetChanged();
            refreshPinned[0].run();
        });

        searchView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                adapter.filter(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        refreshPinned[0].run();
    }

    private Button buildChip(String label) {
        Button chip = new Button(this);
        chip.setText(label);
        chip.setAllCaps(false);
        chip.setTextSize(12f);
        chip.setPadding(20, 10, 20, 10);
        chip.setBackgroundResource(R.drawable.strike_chip_left);
        chip.setTextColor(c(R.color.color_text_primary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 10, 0);
        chip.setLayoutParams(lp);
        return chip;
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

    // ─────────────────────────────────────────────────────────────────────────
    // Auto-disconnect
    // ─────────────────────────────────────────────────────────────────────────

    private void scheduleAutoDisconnectFromDays(int days) {
        handler.removeCallbacks(autoDisconnectRunnable);
        if (days <= 0) { runAutoDisconnect(); return; }
        long delay = days * 24L * 60L * 60L * 1000L;
        autoDisconnectAtMs = SystemClock.elapsedRealtime() + delay;
        handler.postDelayed(autoDisconnectRunnable, delay);
    }

    private void runAutoDisconnect() {
        if (!BtVpnService.isRunningState()) return;
        stopVpn();
        setHideInternalId(false);
        showErrorDetail(
            "✖ Acceso expirado\nID: " + internalId,
            c(R.color.color_connecting)
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Device ID
    // ─────────────────────────────────────────────────────────────────────────

    private void setHideInternalId(boolean hide) {
        if (hideInternalId == hide) return;
        hideInternalId = hide;
        getSharedPreferences(PREF_UI, MODE_PRIVATE).edit()
            .putBoolean(KEY_HIDE_ID, hide).apply();
        refreshDeviceIdVisibility();
    }

    private void refreshDeviceIdVisibility() {
        safeSetVisible(deviceIdView, !hideInternalId);
        safeSetVisible(copyIdBtn,   !hideInternalId);
    }

    private void copyInternalIdToClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("internal_id", internalId));
            showToast("ID copiado al portapapeles");
        } else {
            showToast("No se pudo copiar el ID");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ping
    // ─────────────────────────────────────────────────────────────────────────

    private void animatePingTo(int targetPing) {
        if (pingValueView == null) return;
        int start = lastPingMs >= 0 ? lastPingMs : targetPing;

        if (!canAnimate() || lastPingMs < 0) {
            pingValueView.setText(String.valueOf(targetPing));
            pingValueView.setTextColor(resolvePingColor(targetPing));
            lastPingMs = targetPing;
            return;
        }

        ValueAnimator counter = ValueAnimator.ofInt(start, targetPing);
        counter.setDuration(450);
        counter.setInterpolator(new DecelerateInterpolator());
        counter.addUpdateListener(a -> pingValueView.setText(
            String.valueOf((int) a.getAnimatedValue())));
        counter.start();

        ValueAnimator colorAnim = ValueAnimator.ofObject(
            new ArgbEvaluator(),
            pingValueView.getCurrentTextColor(),
            resolvePingColor(targetPing)
        );
        colorAnim.setDuration(600);
        colorAnim.addUpdateListener(a ->
            pingValueView.setTextColor((Integer) a.getAnimatedValue()));
        colorAnim.start();

        lastPingMs = targetPing;
    }

    private void resetPingView() {
        if (pingValueView == null) return;
        if (canAnimate()) {
            ValueAnimator va = ValueAnimator.ofObject(
                new ArgbEvaluator(),
                pingValueView.getCurrentTextColor(),
                c(R.color.color_text_disabled)
            );
            va.setDuration(350);
            va.addUpdateListener(a -> pingValueView.setTextColor((Integer) a.getAnimatedValue()));
            va.start();
        } else {
            pingValueView.setTextColor(c(R.color.color_text_disabled));
        }
        pingValueView.setText("--");
        lastPingMs = -1;
    }

    private int resolvePingColor(int ping) {
        if (ping < 60)   return c(R.color.color_connected);
        if (ping < 120)  return c(R.color.color_accent);
        if (ping <= 200) return c(R.color.color_connecting);
        return c(R.color.color_disconnected);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Animadores del dot
    // ─────────────────────────────────────────────────────────────────────────

    private void startStatusPulse() {
        if (statusDotView == null || !canAnimate()) return;
        if (statusPulseAnimator == null)
            statusPulseAnimator = AnimatorInflater.loadAnimator(this, R.animator.pulse);
        statusPulseAnimator.setTarget(statusDotView);
        if (!statusPulseAnimator.isStarted()) statusPulseAnimator.start();
    }

    private void stopStatusPulse() {
        if (statusPulseAnimator != null) statusPulseAnimator.cancel();
        if (statusDotView != null) {
            statusDotView.setScaleX(1f);
            statusDotView.setScaleY(1f);
            statusDotView.setAlpha(1f);
        }
    }

    private void startStatusHaloWave() {
        if (statusHaloView == null || !canAnimate()) return;
        if (statusHaloAnimator == null)
            statusHaloAnimator = AnimatorInflater.loadAnimator(this, R.animator.status_halo_wave);
        statusHaloAnimator.setTarget(statusHaloView);
        if (!statusHaloAnimator.isStarted()) statusHaloAnimator.start();
    }

    private void stopStatusHaloWave() {
        if (statusHaloAnimator != null) statusHaloAnimator.cancel();
        safeSetAlpha(statusHaloView, 0.15f);
        safeSetScale(statusHaloView, 1f);
    }

    private void cancelAllAnimators() {
        if (statusPulseAnimator != null) statusPulseAnimator.cancel();
        if (statusHaloAnimator  != null) statusHaloAnimator.cancel();
        if (dotColorAnimator    != null) dotColorAnimator.cancel();
        if (dotBlinkAnimator    != null) dotBlinkAnimator.cancel();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private boolean canAnimate() { return ValueAnimator.areAnimatorsEnabled(); }

    private int c(int colorRes) { return getColor(colorRes); }

    private void animateFadeIn(View v, long durationMs) {
        if (v == null) return;
        v.setVisibility(View.VISIBLE);
        if (!canAnimate()) { v.setAlpha(1f); return; }
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(durationMs).start();
    }

    private void animateBadgeColor(TextView tv, int toColor, long durationMs) {
        if (tv == null) return;
        if (!canAnimate()) { tv.setTextColor(toColor); return; }
        int from = tv.getCurrentTextColor();
        ValueAnimator va = ValueAnimator.ofObject(new ArgbEvaluator(), from, toColor);
        va.setDuration(durationMs);
        va.addUpdateListener(a -> tv.setTextColor((Integer) a.getAnimatedValue()));
        va.start();
    }

    private void applyTint(View view, int color) {
        if (view != null)
            view.setBackgroundTintList(ColorStateList.valueOf(color));
    }

    private void safeSetAlpha(View v, float alpha) {
        if (v != null) v.setAlpha(alpha);
    }

    private void safeSetScale(View v, float scale) {
        if (v != null) { v.setScaleX(scale); v.setScaleY(scale); }
    }

    private void safeSetVisible(View v, boolean visible) {
        if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void performHaptic(View v) {
        if (v != null) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int resolveStatusColor(UiState state) {
        if (state == UiState.CONNECTED)  return c(R.color.color_connected);
        if (state == UiState.CONNECTING) return c(R.color.color_connecting);
        return c(R.color.color_text_disabled);
    }

    private String resolveBadgeText(UiState state) {
        if (state == UiState.CONNECTING) return "CONECTANDO…";
        if (state == UiState.CONNECTED)  return "CONECTADO";
        return "DESCONECTADO";
    }

    private int resolveBadgeColor(UiState state) {
        if (state == UiState.CONNECTING) return c(R.color.color_connecting);
        if (state == UiState.CONNECTED)
            return BtProxy.isGamingMode(this) ? c(R.color.color_gaming) : c(R.color.color_connected);
        return c(R.color.color_disconnected);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Clases internas
    // ─────────────────────────────────────────────────────────────────────────

    private static final class AppOption {
        final String   packageName;
        final String   appName;
        final Drawable icon;
        AppOption(String p, String n, Drawable i) { packageName = p; appName = n; icon = i; }
    }

    private final class GamingAppsAdapter extends BaseAdapter {

        interface OnSelectionChanged { void onChange(AppOption app, boolean checked); }

        private final List<AppOption> allApps;
        private final List<AppOption> filteredApps;
        private final Set<String>     selectedPackages;
        private OnSelectionChanged    selectionListener;

        GamingAppsAdapter(List<AppOption> apps, Set<String> selectedPkgs) {
            this.allApps          = new ArrayList<>(apps);
            this.filteredApps     = new ArrayList<>(apps);
            this.selectedPackages = selectedPkgs;
        }

        void setSelectionListener(OnSelectionChanged l) { this.selectionListener = l; }

        void filter(String query) {
            filteredApps.clear();
            String q = query.trim().toLowerCase(Locale.ROOT);
            for (AppOption app : allApps) {
                if (q.isEmpty()
                        || app.appName.toLowerCase(Locale.ROOT).contains(q)
                        || app.packageName.toLowerCase(Locale.ROOT).contains(q)) {
                    filteredApps.add(app);
                }
            }
            notifyDataSetChanged();
        }

        @Override public int       getCount()              { return filteredApps.size(); }
        @Override public AppOption getItem(int p)          { return filteredApps.get(p); }
        @Override public long      getItemId(int p)        { return p; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
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
            check.setOnCheckedChangeListener((btn, isChecked) -> {
                if (selectionListener != null) selectionListener.onChange(app, isChecked);
            });
            return view;
        }
    }
}
