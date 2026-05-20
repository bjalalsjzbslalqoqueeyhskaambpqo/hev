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
    private static final String KEY_TOTAL_USAGE_MS   = "total_usage_ms";
    private static final String KEY_SESSION_START_MS = "session_start_elapsed_ms";
    private static final int    HOTSPOT_PROXY_PORT   = 7071;
    private static final long   CONNECTING_TIMEOUT_MS = 40000L;

    private enum UiState { DISCONNECTED, CONNECTING, CONNECTED }

    private Button        connectBtn;
    private Button        copyIdBtn;
    private TextView      statusBadgeView;
    private View          statusDotView;
    private View          statusHaloView;
    private View          statusHaloMidView;
    private TextView      statusDetailsView;
    private TextView      deviceIdView;
    private TextView      userValueView;
    private TextView      userNameWideView;
    private TextView      totalUsageView;
    private TextView      daysValueView;
    private TextView      pingValueView;
    private PingPulseView pingPulseView;
    private Switch        gamingModeSwitch;
    private TextView      gamingModeBadgeView;
    private TextView      gamingDescriptionView;
    private TextView      gamingSelectedCountView;
    private Button        selectGamingAppsBtn;
    private LinearLayout  gamingModePanel;
    private LinearLayout  gamingControlsLayout;
    private View          panelConnectionView;
    private View          btnRingOuter;
    private View          btnRingMid;
    private View          btnTopDot;
    private Animator      statusPulseAnimator;
    private Animator      statusHaloAnimator;

    private final ExecutorService appLoadExecutor    = Executors.newSingleThreadExecutor();
    private String  internalId                       = "";
    private UiState uiState                          = UiState.DISCONNECTED;
    private long    connectingSinceMs                = 0L;
    private boolean lastRunning                      = false;
    private long    autoDisconnectAtMs               = -1L;
    private boolean pendingManualReconnect           = false;
    private String  authState                        = "";
    private boolean hideInternalId                   = false;
    private boolean applyingRuntimeChanges           = false;
    private int     lastPingMs                       = -1;
    private boolean handshakeConfirmed               = false;
    private String  lastKnownConnState               = "";
    private String  lastDetailText                   = "";

    private final Runnable autoDisconnectRunnable = this::runAutoDisconnect;
    private final Runnable usageTicker = new Runnable() {
        @Override public void run() {
            updateUsageViews();
            handler.postDelayed(this, 1000);
        }
    };
    private final Runnable delayedReconnectRunnable = () -> {
        pendingManualReconnect = false;
        startVpnWithPermission();
    };
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<Intent> vpnPermissionLauncher;

    private final Runnable stateTicker = new Runnable() {
        @Override public void run() {
            try {
                String logs = BtVpnService.dumpLogs();
                syncStateFromLogs(logs);
                refreshFromLogs(logs);
            } catch (Throwable ignored) {}
            handler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        Configuration configuration = new Configuration(newBase.getResources().getConfiguration());
        configuration.fontScale = 1.0f;
        super.attachBaseContext(newBase.createConfigurationContext(configuration));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_mode);

        vpnPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) startVpn();
                else setUiState(UiState.DISCONNECTED);
            }
        );

        connectBtn              = findViewById(R.id.btnConnect);
        copyIdBtn               = findViewById(R.id.btnCopyLogs);
        statusBadgeView         = findViewById(R.id.txtStatusBadge);
        statusDotView           = findViewById(R.id.viewStatusDot);
        statusHaloView          = findViewById(R.id.viewStatusHalo);
        statusHaloMidView       = findViewById(R.id.viewStatusHaloMid);
        statusDetailsView       = findViewById(R.id.txtStatusDetails);
        deviceIdView            = findViewById(R.id.txtDeviceId);
        userValueView           = findViewById(R.id.txtUser);
        totalUsageView          = findViewById(R.id.txtTotalUsage);
        daysValueView           = findViewById(R.id.txtDays);
        pingValueView           = findViewById(R.id.txtPingValue);
        pingPulseView           = findViewById(R.id.pingPulseView);
        gamingModeSwitch        = findViewById(R.id.switchGamingMode);
        gamingModeBadgeView     = findViewById(R.id.txtGamingBadge);
        gamingDescriptionView   = findViewById(R.id.txtGamingDescription);
        gamingSelectedCountView = findViewById(R.id.txtGamingSelectedCount);
        selectGamingAppsBtn     = findViewById(R.id.btnSelectGamingApps);
        gamingModePanel         = findViewById(R.id.panelGamingMode);
        gamingControlsLayout    = findViewById(R.id.layoutGamingControls);
        panelConnectionView     = findViewById(R.id.panelConnection);
        userNameWideView        = findViewById(R.id.txtUserNameWide);
        btnRingOuter            = findViewById(R.id.btnRingOuter);
        btnRingMid              = findViewById(R.id.btnRingMid);
        btnTopDot               = findViewById(R.id.btnTopDot);

        internalId = BtProxy.getOrCreateInternalId(this);
        BtProxy.applyStoredGamingMode(this);
        hideInternalId = getSharedPreferences(PREF_UI, MODE_PRIVATE).getBoolean(KEY_HIDE_ID, false);
        if (deviceIdView != null) deviceIdView.setText("ID: " + internalId);
        refreshDeviceIdVisibility();

        boolean running = BtVpnService.isRunningState();
        setUiState(running ? UiState.CONNECTED : UiState.DISCONNECTED);
        lastRunning = running;

        connectBtn.setOnClickListener(v -> {
            if (uiState == UiState.CONNECTING) return;
            if (uiState == UiState.CONNECTED) stopVpn();
            else {
                if (BtVpnService.isRunningState()) {
                    if (!pendingManualReconnect) {
                        pendingManualReconnect = true;
                        setUiState(UiState.CONNECTING);
                        handler.removeCallbacks(delayedReconnectRunnable);
                        handler.postDelayed(delayedReconnectRunnable, 850);
                    }
                } else {
                    startVpnWithPermission();
                }
            }
        });
        copyIdBtn.setOnClickListener(v -> copyInternalIdToClipboard());

        if (gamingModeSwitch != null) {
            gamingModeSwitch.setChecked(BtProxy.isGamingMode(this));
            gamingModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                BtProxy.setGamingMode(this, isChecked);
                refreshGamingModeUi();
                if (BtVpnService.isRunningState()) {
                    showGamingApplyFeedback(
                        isChecked ? "Modo aplicaciones: activando..." : "Modo normal: aplicando...",
                        isChecked ? "Modo aplicaciones activo" : "Modo normal activo",
                        isChecked ? R.color.color_gaming : R.color.color_text_secondary
                    );
                    applyGamingChangesIfRunning();
                }
            });
        }
        if (selectGamingAppsBtn != null)
            selectGamingAppsBtn.setOnClickListener(v -> openGamingAppsDialog());

        refreshGamingModeUi();
        updateUsageViews();
        handler.post(usageTicker);
        setupConnectivityMonitor();
        handler.post(stateTicker);
    }

    private void applyHudTheme(int accentColor) {
        float dp = getResources().getDisplayMetrics().density;

        if (btnRingOuter  != null) btnRingOuter.setBackground(VisualDrawables.btnRingOuter(accentColor));
        if (btnRingMid    != null) btnRingMid.setBackground(VisualDrawables.btnRingMid(accentColor));
        if (btnTopDot     != null) btnTopDot.setBackgroundTintList(ColorStateList.valueOf(accentColor));

        if (panelConnectionView != null)
            panelConnectionView.setBackground(VisualDrawables.statusBadge(accentColor));

        View metricsStrip = findViewById(R.id.layoutDataStrip);
        if (metricsStrip != null) metricsStrip.setBackground(VisualDrawables.panelMetrics(accentColor));

        if (pingPulseView != null) pingPulseView.setLineColor(accentColor);

        if (connectBtn != null)
            connectBtn.setBackground(VisualDrawables.btnConnect(accentColor, uiState == UiState.CONNECTED));
    }

    private int resolveAccentColor() {
        return uiState == UiState.CONNECTED  ? c(R.color.color_connected)
             : uiState == UiState.CONNECTING ? c(R.color.color_connecting)
             : c(R.color.color_disconnected);
    }

    private void setupConnectivityMonitor() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onLost(Network network) {
                if (BtVpnService.isRunningState()) stopVpn();
                setUiState(UiState.DISCONNECTED);
            }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (caps == null) return;
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && uiState == UiState.CONNECTING)
                    setUiState(UiState.DISCONNECTED);
            }
        };
        try {
            connectivityManager.registerNetworkCallback(
                new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                networkCallback);
        } catch (Throwable ignored) {}
    }

    private void copyInternalIdToClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("internal_id", internalId));
            Toast.makeText(this, "ID copiado", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Error copiando ID", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (uiState == UiState.CONNECTED && connectingSinceMs > 0)
            setStoredSessionStartMs(connectingSinceMs);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(stateTicker);
        handler.removeCallbacks(autoDisconnectRunnable);
        handler.removeCallbacks(usageTicker);
        handler.removeCallbacks(delayedReconnectRunnable);
        if (connectBtn != null) connectBtn.animate().cancel();
        if (statusDetailsView != null) statusDetailsView.animate().cancel();
        if (statusBadgeView != null) statusBadgeView.animate().cancel();
        stopStatusPulse();
        stopStatusHaloWave();
        stopHudRingRotation();
        if (connectivityManager != null && networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Throwable ignored) {}
        }
        BtVpnService.stopLocalProxy();
        appLoadExecutor.shutdownNow();
        super.onDestroy();
    }

    private int c(int colorRes) { return getColor(colorRes); }
    private boolean canAnimate() { return ValueAnimator.areAnimatorsEnabled(); }

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
            statusDotView.setScaleX(1f); statusDotView.setScaleY(1f); statusDotView.setAlpha(1f);
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
        if (statusHaloView != null) {
            statusHaloView.setScaleX(1f); statusHaloView.setScaleY(1f); statusHaloView.setAlpha(0.20f);
        }
    }

    private void startHudRingRotation() {
        if (!canAnimate() || btnRingOuter == null) return;
        btnRingOuter.animate().cancel();
        btnRingOuter.animate()
            .rotationBy(360f)
            .setDuration(9000)
            .setInterpolator(new android.view.animation.LinearInterpolator())
            .withEndAction(() -> {
                if (uiState == UiState.CONNECTED && btnRingOuter != null && btnRingOuter.isAttachedToWindow())
                    startHudRingRotation();
            })
            .start();
    }

    private void stopHudRingRotation() {
        if (btnRingOuter != null) btnRingOuter.animate().cancel();
    }

    private long getStoredTotalUsageMs() {
        return getSharedPreferences(PREF_UI, MODE_PRIVATE).getLong(KEY_TOTAL_USAGE_MS, 0L);
    }

    private long getStoredSessionStartMs() {
        return getSharedPreferences(PREF_UI, MODE_PRIVATE).getLong(KEY_SESSION_START_MS, -1L);
    }

    private void setStoredSessionStartMs(long value) {
        getSharedPreferences(PREF_UI, MODE_PRIVATE).edit().putLong(KEY_SESSION_START_MS, value).apply();
    }

    private void addTotalUsageMs(long deltaMs) {
        if (deltaMs <= 0) return;
        long next = getStoredTotalUsageMs() + deltaMs;
        getSharedPreferences(PREF_UI, MODE_PRIVATE).edit().putLong(KEY_TOTAL_USAGE_MS, next).apply();
    }

    private String fmtHms(long ms) {
        long sec = Math.max(0L, ms / 1000L);
        long h = sec / 3600L;
        long m = (sec % 3600L) / 60L;
        long s = sec % 60L;
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s);
    }

    private void updateUsageViews() {
        long anchor = connectingSinceMs > 0 ? connectingSinceMs : getStoredSessionStartMs();
        if (uiState == UiState.CONNECTED && anchor > 0 && connectingSinceMs <= 0) connectingSinceMs = anchor;
        if (userValueView != null) {
            long session = uiState == UiState.CONNECTED
                ? Math.max(0L, (SystemClock.elapsedRealtime() - anchor)) : 0L;
            userValueView.setText(fmtHms(session));
        }
        if (totalUsageView != null) {
            long total = getStoredTotalUsageMs();
            if (uiState == UiState.CONNECTED && anchor > 0) total += Math.max(0L, (SystemClock.elapsedRealtime() - anchor));
            totalUsageView.setText("Uso total " + fmtHms(total));
        }
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

    private void refreshGamingModeUi() {
        boolean enabled = BtProxy.isGamingMode(this);
        List<String> selected = BtProxy.getGamingSelectedPackages(this);

        if (gamingModeBadgeView != null) {
            if (enabled) {
                gamingModeBadgeView.setText(R.string.gaming_mode_on_compact);
                gamingModeBadgeView.setTextColor(c(R.color.color_gaming));
                gamingModeBadgeView.setBackgroundResource(R.drawable.strike_chip_left);
                gamingModeBadgeView.setLetterSpacing(0.14f);
            } else {
                gamingModeBadgeView.setText(R.string.gaming_mode_off_compact);
                gamingModeBadgeView.setTextColor(c(R.color.color_btn_disabled));
            }
        }
        if (gamingDescriptionView != null)
            gamingDescriptionView.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (gamingSelectedCountView != null) {
            if (selected.isEmpty()) {
                gamingSelectedCountView.setText("Ninguna app seleccionada");
                gamingSelectedCountView.setTextColor(c(R.color.color_text_disabled));
            } else {
                String first   = selected.get(0);
                String summary = selected.size() > 1 ? first + " +" + (selected.size() - 1) + " más" : first;
                gamingSelectedCountView.setText(summary);
                gamingSelectedCountView.setTextColor(c(R.color.color_gaming));
            }
        }
        if (selectGamingAppsBtn != null) {
            selectGamingAppsBtn.setEnabled(enabled);
            selectGamingAppsBtn.setAlpha(enabled ? 1f : 0.55f);
        }
        if (gamingControlsLayout != null) {
            if (enabled) {
                gamingControlsLayout.setVisibility(View.VISIBLE);
                gamingControlsLayout.setAlpha(0f);
                gamingControlsLayout.animate().alpha(1f).setDuration(180).start();
            } else {
                gamingControlsLayout.animate().cancel();
                gamingControlsLayout.setVisibility(View.GONE);
            }
        }
        if (gamingModePanel != null) gamingModePanel.setActivated(enabled);
        if (connectBtn != null && uiState != UiState.CONNECTED && uiState != UiState.CONNECTING)
            connectBtn.setText(enabled ? getString(R.string.connect_gaming) : getString(R.string.connect));
    }

    private void openGamingAppsDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_gaming_apps, null, false);
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
                showGamingApplyFeedback("Aplicando selección de apps...", "Selección aplicada", R.color.color_accent);
                applyGamingChangesIfRunning();
            })
            .create();

        appLoadExecutor.execute(() -> {
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

    private void startVpnWithPermission() {
        setUiState(UiState.CONNECTING);
        authState = ""; lastKnownConnState = "";
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) vpnPermissionLauncher.launch(prepare);
        else startVpn();
    }

    private void startVpn() {
        Intent i = new Intent(this, BtVpnService.class);
        i.setAction(BtVpnService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
        connectingSinceMs = SystemClock.elapsedRealtime();
        handshakeConfirmed = false; lastKnownConnState = "";
        setUiState(UiState.CONNECTING);
    }

    private void stopVpn() {
        pendingManualReconnect = false;
        handler.removeCallbacks(delayedReconnectRunnable);
        Intent i = new Intent(this, BtVpnService.class);
        i.setAction(BtVpnService.ACTION_STOP);
        startService(i);
        handshakeConfirmed = false; lastKnownConnState = "";
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

    private void setHideInternalId(boolean hide) {
        if (hideInternalId == hide) return;
        hideInternalId = hide;
        getSharedPreferences(PREF_UI, MODE_PRIVATE).edit().putBoolean(KEY_HIDE_ID, hide).apply();
        refreshDeviceIdVisibility();
    }

    private void refreshDeviceIdVisibility() {
        if (deviceIdView != null) deviceIdView.setVisibility(hideInternalId ? View.GONE : View.VISIBLE);
        if (copyIdBtn   != null) copyIdBtn.setVisibility(hideInternalId ? View.GONE : View.VISIBLE);
    }

    private void showGamingApplyFeedback(String start, String done, int colorRes) {
        if (gamingModeBadgeView == null) return;
        gamingModeBadgeView.setText(start);
        gamingModeBadgeView.setTextColor(c(colorRes));
        gamingModeBadgeView.animate().cancel();
        gamingModeBadgeView.setAlpha(0.55f);
        gamingModeBadgeView.animate().alpha(1f).setDuration(220).start();
        handler.postDelayed(() -> {
            if (gamingModeBadgeView != null) gamingModeBadgeView.setText(done);
            handler.postDelayed(this::refreshGamingModeUi, 500);
        }, 500);
    }

    private void syncStateFromLogs(String logs) {
        boolean running   = BtVpnService.isRunningState();
        String  connState = findLatestConnectionState(logs);
        if (!connState.isEmpty() && !connState.equals(lastKnownConnState)) lastKnownConnState = connState;
        if ("connected".equals(connState)) handshakeConfirmed = true;

        if (running && !lastRunning) {
            authState = ""; applyingRuntimeChanges = false;
            setUiState(handshakeConfirmed ? UiState.CONNECTED : UiState.CONNECTING);
        } else if (!running && lastRunning) {
            applyingRuntimeChanges = false; handshakeConfirmed = false;
            setUiState(UiState.DISCONNECTED);
        } else if (running && handshakeConfirmed) {
            applyingRuntimeChanges = false;
            if (uiState != UiState.CONNECTED) setUiState(UiState.CONNECTED);
        } else if (running && !handshakeConfirmed) {
            if ("failed".equals(lastKnownConnState)) {
                if (SystemClock.elapsedRealtime() - connectingSinceMs > CONNECTING_TIMEOUT_MS) {
                    applyingRuntimeChanges = false; stopVpn(); setUiState(UiState.DISCONNECTED);
                }
            } else if (uiState != UiState.CONNECTING) setUiState(UiState.CONNECTING);
            else refreshConnectingDetail();
        } else if (!running && uiState == UiState.CONNECTING) {
            if (SystemClock.elapsedRealtime() - connectingSinceMs > CONNECTING_TIMEOUT_MS) {
                applyingRuntimeChanges = false; setUiState(UiState.DISCONNECTED);
            } else refreshConnectingDetail();
        }
        lastRunning = running;
    }

    private void refreshConnectingDetail() {
        if (statusDetailsView == null) return;
        String detail;
        if      ("retrying".equals(lastKnownConnState)) detail = getString(R.string.status_detail_connecting_search);
        else if ("dropped".equals(lastKnownConnState))  detail = getString(R.string.status_detail_connecting_reconnect);
        else if ("failed".equals(lastKnownConnState))   detail = getString(R.string.status_detail_connecting_retry);
        else                                             detail = getString(R.string.status_detail_connecting_default);
        if (!detail.equals(lastDetailText)) {
            lastDetailText = detail;
            statusDetailsView.setVisibility(View.VISIBLE);
            if (canAnimate()) {
                statusDetailsView.animate().cancel();
                statusDetailsView.setAlpha(0.4f);
                statusDetailsView.setText(detail);
                statusDetailsView.animate().alpha(1f).setDuration(300).start();
            } else statusDetailsView.setText(detail);
        }
    }

    private void refreshFromLogs(String logs) {
        updateServerAuthStatus(logs);
        updateUserMetadata(logs);
    }

    private void updateServerAuthStatus(String logs) {
        if (logs == null || statusDetailsView == null) return;
        String latestAuth = findLatestAuthState(logs);
        if ("not_registered".equals(latestAuth)) {
            if ("not_registered".equals(authState)) return;
            authState = "not_registered"; setHideInternalId(false);
            if (BtVpnService.isRunningState()) stopVpn();
            setUiState(UiState.DISCONNECTED);
            statusDetailsView.setVisibility(View.VISIBLE);
            statusDetailsView.setText("✖ Usuario no registrado\nComparte tu ID interno para habilitación\nID: " + internalId);
            statusDetailsView.setTextColor(c(R.color.color_disconnected));
            if (connectBtn != null) connectBtn.startAnimation(AnimationUtils.loadAnimation(this, R.anim.shake));
        } else if ("expired".equals(latestAuth)) {
            if ("expired".equals(authState)) return;
            authState = "expired"; setHideInternalId(false);
            if (BtVpnService.isRunningState()) stopVpn();
            setUiState(UiState.DISCONNECTED);
            statusDetailsView.setVisibility(View.VISIBLE);
            statusDetailsView.setText("✖ Usuario expirado\nRenueva tu acceso con soporte\nID: " + internalId);
            statusDetailsView.setTextColor(c(R.color.color_connecting));
            if (connectBtn != null) connectBtn.startAnimation(AnimationUtils.loadAnimation(this, R.anim.shake));
        } else if ("ok".equals(latestAuth)) { authState = ""; setHideInternalId(true); }
    }

    private String findLatestAuthState(String logs) {
        String[] lines = logs.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i]; if (line == null) continue;
            String lower = line.trim().toLowerCase(Locale.ROOT); if (lower.isEmpty()) continue;
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
            String line = lines[i]; if (line == null) continue;
            String lower = line.trim().toLowerCase(Locale.ROOT); if (lower.isEmpty()) continue;
            if (lower.contains("tunnel ok")  || lower.contains("user_name=") ||
                lower.contains("user_days=") || lower.contains("ping_ms="))   return "connected";
            if (lower.contains("conectando...") || lower.contains("probando ") ||
                lower.contains("ips estaticas fallaron") || lower.contains("dns ") ||
                lower.contains("relay listo"))                                  return "retrying";
            if (lower.contains("tunnel caido")       || lower.contains("pong timeout") ||
                lower.contains("tunnel read failed")  || lower.contains("payload too large") ||
                lower.contains("payload read failed"))                          return "dropped";
            if (lower.contains("handshake failed") || lower.contains("proxy no responde") ||
                lower.contains("getaddrinfo fallo") || lower.contains("connect failed"))  return "failed";
        }
        return "";
    }

    private void updateUserMetadata(String logs) {
        if (logs == null) return;
        String[] lines = logs.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim(); int idx = line.indexOf("user_name=");
            if (idx >= 0) {
                String v = line.substring(idx + "user_name=".length()).trim();
                if (!v.isEmpty() && userNameWideView != null) userNameWideView.setText("Usuario: " + v);
                break;
            }
        }
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim(); int idx = line.indexOf("user_days=");
            if (idx >= 0 && daysValueView != null) {
                String v = line.substring(idx + "user_days=".length()).trim();
                if (!v.isEmpty()) {
                    daysValueView.setText(v + " días");
                    try {
                        int days = Integer.parseInt(v.replaceAll("[^0-9]", ""));
                        daysValueView.setTextColor(days < 7 ? c(R.color.color_connecting) : c(R.color.color_connected));
                        scheduleAutoDisconnectFromDays(days);
                    } catch (Exception ignored) {}
                }
                break;
            }
        }
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim(); int idx = line.indexOf("ping_ms=");
            if (idx >= 0) {
                String v = line.substring(idx + "ping_ms=".length()).trim();
                if (!v.isEmpty()) {
                    try {
                        int ping = Integer.parseInt(v.replaceAll("[^0-9]", ""));
                        animatePingTo(ping);
                        if (pingPulseView != null) {
                            pingPulseView.setLineColor(resolvePingColor(ping));
                            pingPulseView.pushPing(ping);
                        }
                    } catch (Exception ignored) {
                        if (pingValueView != null) {
                            pingValueView.setText("--");
                            pingValueView.setTextColor(c(R.color.color_text_disabled));
                        }
                    }
                }
                break;
            }
        }
    }

    private void animatePingTo(int targetPing) {
        if (pingValueView == null) return;
        int start = lastPingMs >= 0 ? lastPingMs : targetPing;
        if (!canAnimate() || lastPingMs < 0) {
            pingValueView.setText(String.valueOf(targetPing));
            pingValueView.setTextColor(resolvePingColor(targetPing));
            lastPingMs = targetPing; return;
        }
        ValueAnimator counter = ValueAnimator.ofInt(start, targetPing);
        counter.setDuration(400);
        counter.addUpdateListener(a -> pingValueView.setText(String.valueOf((int) a.getAnimatedValue())));
        counter.start();
        ValueAnimator colorAnim = ValueAnimator.ofObject(
            new ArgbEvaluator(), pingValueView.getCurrentTextColor(), resolvePingColor(targetPing));
        colorAnim.setDuration(600);
        colorAnim.addUpdateListener(a -> pingValueView.setTextColor((Integer) a.getAnimatedValue()));
        colorAnim.start();
        lastPingMs = targetPing;
    }

    private int resolvePingColor(int ping) {
        if (ping < 80)   return c(R.color.color_connected);
        if (ping <= 150) return c(R.color.color_accent);
        return c(R.color.color_connecting);
    }

    private void scheduleAutoDisconnectFromDays(int days) {
        handler.removeCallbacks(autoDisconnectRunnable);
        if (days <= 0) { runAutoDisconnect(); return; }
        long delay = days * 24L * 60L * 60L * 1000L;
        autoDisconnectAtMs = SystemClock.elapsedRealtime() + delay;
        handler.postDelayed(autoDisconnectRunnable, delay);
    }

    private void runAutoDisconnect() {
        if (!BtVpnService.isRunningState()) return;
        stopVpn(); setHideInternalId(false);
        if (statusDetailsView != null) {
            statusDetailsView.setVisibility(View.VISIBLE);
            statusDetailsView.setText("✖ Usuario expirado\nDesconexión automática local\nID: " + internalId);
            statusDetailsView.setTextColor(c(R.color.color_connecting));
        }
    }

    private void setUiState(UiState newState) {
        UiState prev         = uiState;
        uiState              = newState;
        boolean stateChanged = (prev != newState);

        int accentColor = resolveAccentColor();

        if (stateChanged) {
            applyHudTheme(accentColor);
            animateDotColor(statusDotView, statusHaloView, accentColor, 400);
            if (statusHaloMidView != null)
                statusHaloMidView.setBackgroundTintList(ColorStateList.valueOf(accentColor));
        }

        if (connectBtn != null) {
            if (newState == UiState.CONNECTING) {
                connectBtn.setEnabled(false); connectBtn.setActivated(false);
                connectBtn.setText(R.string.status_connecting);
                connectBtn.setTextColor(c(R.color.color_connecting));
                if (stateChanged && canAnimate()) {
                    connectBtn.setAlpha(0.7f);
                    connectBtn.animate().alpha(1f).setDuration(300).start();
                }
            } else if (newState == UiState.CONNECTED) {
                if (prev != UiState.CONNECTED || connectingSinceMs <= 0) {
                    connectingSinceMs = SystemClock.elapsedRealtime();
                    setStoredSessionStartMs(connectingSinceMs);
                }
                connectBtn.setEnabled(true); connectBtn.setActivated(true);
                connectBtn.setText(R.string.disconnect);
                connectBtn.setTextColor(c(R.color.color_connected));
                if (stateChanged && canAnimate()) {
                    connectBtn.setScaleX(0.92f); connectBtn.setScaleY(0.92f);
                    connectBtn.animate().scaleX(1f).scaleY(1f).setDuration(250).start();
                }
            } else {
                if (prev == UiState.CONNECTED && connectingSinceMs > 0)
                    addTotalUsageMs(SystemClock.elapsedRealtime() - connectingSinceMs);
                connectingSinceMs = 0L;
                setStoredSessionStartMs(-1L);
                connectBtn.setEnabled(true); connectBtn.setActivated(false);
                connectBtn.setText(BtProxy.isGamingMode(this) ? getString(R.string.connect_gaming) : getString(R.string.connect));
                connectBtn.setTextColor(c(R.color.color_text_primary));
                if (stateChanged && prev == UiState.CONNECTED && canAnimate()) {
                    connectBtn.setAlpha(0.6f);
                    connectBtn.animate().alpha(1f).setDuration(400).start();
                }
            }
        }

        if (statusBadgeView != null) {
            String badgeText;
            int    badgeColor;
            if (newState == UiState.CONNECTING) {
                badgeText = getString(R.string.status_connecting); badgeColor = c(R.color.color_connecting);
                startStatusPulse(); startStatusHaloWave();
                startHudRingRotation();
            } else if (newState == UiState.CONNECTED) {
                badgeText  = getString(R.string.status_connected);
                badgeColor = BtProxy.isGamingMode(this) ? c(R.color.color_gaming) : c(R.color.color_connected);
                stopStatusPulse(); stopStatusHaloWave();
                startHudRingRotation();
                if (statusHaloView != null) statusHaloView.setAlpha(0.15f);
            } else {
                badgeText  = getString(R.string.status_disconnected); badgeColor = c(R.color.color_disconnected);
                stopStatusPulse(); stopStatusHaloWave();
                stopHudRingRotation();
                if (statusHaloView != null) statusHaloView.setAlpha(0.20f);
                if (pingPulseView  != null) pingPulseView.setIdle();
            }
            if (stateChanged) {
                animateBadgeColor(statusBadgeView, badgeColor, 350);
                if (!statusBadgeView.getText().toString().equals(badgeText)) {
                    if (canAnimate()) {
                        statusBadgeView.animate().cancel();
                        statusBadgeView.setAlpha(0f);
                        statusBadgeView.setText(badgeText);
                        statusBadgeView.animate().alpha(1f).setDuration(250).start();
                    } else { statusBadgeView.setText(badgeText); statusBadgeView.setTextColor(badgeColor); }
                }
            } else { statusBadgeView.setText(badgeText); statusBadgeView.setTextColor(badgeColor); }
            statusBadgeView.setShadowLayer(0f, 0f, 0f, 0);
        }

        if (statusDetailsView != null) {
            if (newState == UiState.CONNECTING) {
                String detail;
                if      ("retrying".equals(lastKnownConnState)) detail = getString(R.string.status_detail_connecting_search);
                else if ("dropped".equals(lastKnownConnState))  detail = getString(R.string.status_detail_connecting_reconnect);
                else if ("failed".equals(lastKnownConnState))   detail = getString(R.string.status_detail_connecting_retry);
                else                                             detail = getString(R.string.status_detail_connecting_default);
                lastDetailText = detail;
                statusDetailsView.setVisibility(View.VISIBLE);
                statusDetailsView.setTextColor(c(R.color.color_connecting));
                if (stateChanged && canAnimate()) {
                    statusDetailsView.setAlpha(0f);
                    statusDetailsView.setText(detail);
                    statusDetailsView.animate().alpha(1f).setDuration(300).start();
                } else statusDetailsView.setText(detail);
            } else if (newState == UiState.CONNECTED) {
                String full = getString(R.string.status_detail_connected);
                lastDetailText = full;
                statusDetailsView.setVisibility(View.VISIBLE);
                statusDetailsView.setTextColor(c(R.color.color_connected));
                if (stateChanged && canAnimate())
                    statusDetailsView.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_slide_in));
                statusDetailsView.setText(full);
            } else {
                lastDetailText = "";
                statusDetailsView.setVisibility(View.GONE);
                if (pingValueView != null) {
                    pingValueView.setText("--");
                    if (canAnimate()) {
                        ValueAnimator va = ValueAnimator.ofObject(new ArgbEvaluator(),
                            pingValueView.getCurrentTextColor(), c(R.color.color_text_disabled));
                        va.setDuration(300);
                        va.addUpdateListener(a -> pingValueView.setTextColor((Integer) a.getAnimatedValue()));
                        va.start();
                    } else pingValueView.setTextColor(c(R.color.color_text_disabled));
                    lastPingMs = -1;
                }
            }
        }

        refreshGamingModeUi();
        updateUsageViews();
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

        public static android.graphics.drawable.Drawable btnRingOuter(int color) { return new RingDrawable(color, 4f, true, 18f); }
        public static android.graphics.drawable.Drawable btnRingMid  (int color) { return new RingDrawable(color, 2f, true, 10f); }
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
