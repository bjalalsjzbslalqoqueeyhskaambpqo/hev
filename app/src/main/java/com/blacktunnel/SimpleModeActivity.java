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

public class SimpleModeActivity extends ComponentActivity {
    private static final String PREF_UI              = "ui_state";
    private static final String KEY_HIDE_ID          = "hide_internal_id";
    private static final int    HOTSPOT_PROXY_PORT   = 7071;
    private static final long   CONNECTING_TIMEOUT_MS = 40000L;

    private enum UiState { DISCONNECTED, CONNECTING, CONNECTED }

    private Button        connectBtn;
    private Button        copyIdBtn;
    private TextView      statusBadgeView;
    private View          statusDotView;
    private View          statusHaloView;
    private TextView      statusDetailsView;
    private TextView      deviceIdView;
    private TextView      userValueView;
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
    private Switch        hotspotSwitch;
    private TextView      hotspotInfoView;
    private Animator      statusPulseAnimator;
    private Animator      statusHaloAnimator;

    private final ExecutorService appLoadExecutor    = Executors.newSingleThreadExecutor();
    private String  internalId                       = "";
    private UiState uiState                          = UiState.DISCONNECTED;
    private long    connectingSinceMs                = 0L;
    private boolean lastRunning                      = false;
    private long    autoDisconnectAtMs               = -1L;
    private String  authState                        = "";
    private boolean hideInternalId                   = false;
    private boolean applyingRuntimeChanges           = false;
    private int     lastPingMs                       = -1;
    private boolean handshakeConfirmed               = false;
    private String  lastKnownConnState               = "";
    private String  lastDetailText                   = "";

    private final Runnable autoDisconnectRunnable = this::runAutoDisconnect;
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
        Context fixedContext = newBase.createConfigurationContext(configuration);
        super.attachBaseContext(fixedContext);
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
        statusDetailsView       = findViewById(R.id.txtStatusDetails);
        deviceIdView            = findViewById(R.id.txtDeviceId);
        userValueView           = findViewById(R.id.txtUser);
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
        hotspotSwitch           = findViewById(R.id.switchHotspot);
        hotspotInfoView         = findViewById(R.id.txtHotspotInfo);

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
            else startVpnWithPermission();
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

        if (hotspotSwitch != null) {
            hotspotSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    String ip = BtVpnService.getHotspotIp();
                    if (ip == null) {
                        hotspotSwitch.setChecked(false);
                        Toast.makeText(this, "Activá el hotspot WiFi primero", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (hotspotInfoView != null) {
                        hotspotInfoView.setText("Próximamente disponible");
                        hotspotInfoView.setVisibility(View.VISIBLE);
                    }
                } else {
                    if (hotspotInfoView != null) hotspotInfoView.setVisibility(View.GONE);
                }
            });
        }

        refreshGamingModeUi();
        setupConnectivityMonitor();
        handler.post(stateTicker);
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
    protected void onDestroy() {
        handler.removeCallbacks(stateTicker);
        handler.removeCallbacks(autoDisconnectRunnable);
        stopStatusPulse();
        stopStatusHaloWave();
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
        if (statusHaloView != null) {
            statusHaloView.setScaleX(1f);
            statusHaloView.setScaleY(1f);
            statusHaloView.setAlpha(0.20f);
        }
    }

    private void animateViewFadeIn(View v, long durationMs) {
        if (v == null || !canAnimate()) { if (v != null) v.setAlpha(1f); return; }
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(durationMs).start();
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
        final String   packageName;
        final String   appName;
        final Drawable icon;
        AppOption(String p, String n, Drawable i) { packageName = p; appName = n; icon = i; }
    }

    private void refreshGamingModeUi() {
        boolean enabled  = BtProxy.isGamingMode(this);
        List<String> selected = BtProxy.getGamingSelectedPackages(this);

        if (gamingModeBadgeView != null) {
            if (enabled) {
                gamingModeBadgeView.setText(R.string.gaming_mode_on_compact);
                gamingModeBadgeView.setTextColor(c(R.color.color_gaming));
                gamingModeBadgeView.setBackgroundResource(R.drawable.strike_chip_left);
                gamingModeBadgeView.setLetterSpacing(0.14f);
                gamingModeBadgeView.setShadowLayer(0f, 0f, 0f, 0);
            } else {
                gamingModeBadgeView.setText(R.string.gaming_mode_off_compact);
                gamingModeBadgeView.setTextColor(c(R.color.color_btn_disabled));
                gamingModeBadgeView.setShadowLayer(0f, 0f, 0f, 0);
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
        if (panelConnectionView != null)
            panelConnectionView.setSelected(enabled && uiState == UiState.CONNECTED);
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
                adapter.notifyDataSetChanged();
                return;
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
                Toast.makeText(this, "Máximo 3 aplicaciones en modo seleccionado", Toast.LENGTH_SHORT).show();
                return;
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

        private final List<AppOption> allApps;
        private final List<AppOption> filteredApps;
        private final Set<String>     selectedPackages;
        private OnSelectionChanged    selectionListener;

        GamingAppsAdapter(List<AppOption> apps, Set<String> selectedPackages) {
            this.allApps          = new ArrayList<>(apps);
            this.filteredApps     = new ArrayList<>(apps);
            this.selectedPackages = selectedPackages;
        }

        void setSelectionListener(OnSelectionChanged l) { this.selectionListener = l; }

        void filter(String query) {
            filteredApps.clear();
            String q = query.trim().toLowerCase(Locale.ROOT);
            for (AppOption app : allApps)
                if (q.isEmpty() || app.appName.toLowerCase(Locale.ROOT).contains(q)
                        || app.packageName.toLowerCase(Locale.ROOT).contains(q))
                    filteredApps.add(app);
            notifyDataSetChanged();
        }

        @Override public int       getCount()              { return filteredApps.size(); }
        @Override public AppOption getItem(int position)   { return filteredApps.get(position); }
        @Override public long      getItemId(int position) { return position; }

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
            check.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (selectionListener != null) selectionListener.onChange(app, isChecked);
            });
            return view;
        }
    }

    private void startVpnWithPermission() {
        setUiState(UiState.CONNECTING);
        authState          = "";
        lastKnownConnState = "";
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) vpnPermissionLauncher.launch(prepare);
        else startVpn();
    }

    private void startVpn() {
        Intent i = new Intent(this, BtVpnService.class);
        i.setAction(BtVpnService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
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

        if (!connState.isEmpty() && !connState.equals(lastKnownConnState))
            lastKnownConnState = connState;

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
                    setUiState(UiState.DISCONNECTED);
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
        String detail;
        if      ("retrying".equals(lastKnownConnState)) detail = "• Buscando servidor disponible...";
        else if ("dropped".equals(lastKnownConnState))  detail = "• Reconectando al servidor...";
        else if ("failed".equals(lastKnownConnState))   detail = "• Reintentando conexión...";
        else                                             detail = "• Estableciendo conexión...";
        if (!detail.equals(lastDetailText)) {
            lastDetailText = detail;
            statusDetailsView.setVisibility(View.VISIBLE);
            if (canAnimate()) {
                statusDetailsView.animate().cancel();
                statusDetailsView.setAlpha(0.4f);
                statusDetailsView.setText(detail);
                statusDetailsView.animate().alpha(1f).setDuration(300).start();
            } else {
                statusDetailsView.setText(detail);
            }
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
            authState = "not_registered";
            setHideInternalId(false);
            if (BtVpnService.isRunningState()) stopVpn();
            setUiState(UiState.DISCONNECTED);
            statusDetailsView.setVisibility(View.VISIBLE);
            statusDetailsView.setText("✖ Usuario no registrado\nComparte tu ID interno para habilitación\nID: " + internalId);
            statusDetailsView.setTextColor(c(R.color.color_disconnected));
            if (connectBtn != null) connectBtn.startAnimation(AnimationUtils.loadAnimation(this, R.anim.shake));
        } else if ("expired".equals(latestAuth)) {
            if ("expired".equals(authState)) return;
            authState = "expired";
            setHideInternalId(false);
            if (BtVpnService.isRunningState()) stopVpn();
            setUiState(UiState.DISCONNECTED);
            statusDetailsView.setVisibility(View.VISIBLE);
            statusDetailsView.setText("✖ Usuario expirado\nRenueva tu acceso con soporte\nID: " + internalId);
            statusDetailsView.setTextColor(c(R.color.color_connecting));
            if (connectBtn != null) connectBtn.startAnimation(AnimationUtils.loadAnimation(this, R.anim.shake));
        } else if ("ok".equals(latestAuth)) {
            authState = "";
            setHideInternalId(true);
        }
    }

    private String findLatestAuthState(String logs) {
        String[] lines = logs.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i];
            if (line == null) continue;
            String lower = line.trim().toLowerCase(Locale.ROOT);
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
            String line = lines[i];
            if (line == null) continue;
            String lower = line.trim().toLowerCase(Locale.ROOT);
            if (lower.isEmpty()) continue;

            if (lower.contains("tunnel ok")  ||
                lower.contains("user_name=") ||
                lower.contains("user_days=") ||
                lower.contains("ping_ms="))   return "connected";

            if (lower.contains("conectando...") ||
                lower.contains("probando ")     ||
                lower.contains("ips estaticas fallaron") ||
                lower.contains("dns ")          ||
                lower.contains("relay listo"))   return "retrying";

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
            String line = lines[i].trim();
            int idx = line.indexOf("ping_ms=");
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
                        if (pingValueView  != null) {
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
            lastPingMs = targetPing;
            return;
        }
        ValueAnimator counter = ValueAnimator.ofInt(start, targetPing);
        counter.setDuration(400);
        counter.addUpdateListener(a -> pingValueView.setText(String.valueOf((int) a.getAnimatedValue())));
        counter.start();
        ValueAnimator colorAnim = ValueAnimator.ofObject(
            new ArgbEvaluator(),
            pingValueView.getCurrentTextColor(), resolvePingColor(targetPing));
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
        stopVpn();
        setHideInternalId(false);
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

        if (connectBtn != null) {
            if (newState == UiState.CONNECTING) {
                connectBtn.setEnabled(false);
                connectBtn.setActivated(false);
                connectBtn.setText("Conectando...");
                connectBtn.setBackgroundResource(R.drawable.btn_connecting_selector);
                connectBtn.setTextColor(c(R.color.color_text_primary));
                if (stateChanged && canAnimate()) {
                    connectBtn.setAlpha(0.7f);
                    connectBtn.animate().alpha(1f).setDuration(300).start();
                }
            } else if (newState == UiState.CONNECTED) {
                connectBtn.setEnabled(true);
                connectBtn.setActivated(true);
                connectBtn.setText(R.string.disconnect);
                connectBtn.setBackgroundResource(R.drawable.btn_disconnect_selector);
                connectBtn.setTextColor(c(R.color.color_text_primary));
                if (stateChanged && canAnimate()) {
                    connectBtn.setScaleX(0.92f);
                    connectBtn.setScaleY(0.92f);
                    connectBtn.animate().scaleX(1f).scaleY(1f).setDuration(250).start();
                }
            } else {
                connectBtn.setEnabled(true);
                connectBtn.setActivated(false);
                connectBtn.setText(BtProxy.isGamingMode(this) ? getString(R.string.connect_gaming) : getString(R.string.connect));
                connectBtn.setBackgroundResource(R.drawable.btn_connect_selector);
                connectBtn.setTextColor(0xFF050505);
                if (stateChanged && prev == UiState.CONNECTED && canAnimate()) {
                    connectBtn.setAlpha(0.6f);
                    connectBtn.animate().alpha(1f).setDuration(400).start();
                }
            }
        }

        int dotColor = newState == UiState.CONNECTED  ? c(R.color.color_connected)
                     : newState == UiState.CONNECTING ? c(R.color.color_connecting)
                     : c(R.color.color_text_disabled);

        if (stateChanged) animateDotColor(statusDotView, statusHaloView, dotColor, 400);
        else {
            if (statusDotView  != null) statusDotView.setBackgroundTintList(ColorStateList.valueOf(dotColor));
            if (statusHaloView != null) statusHaloView.setBackgroundTintList(ColorStateList.valueOf(dotColor));
        }

        if (statusBadgeView != null) {
            String badgeText;
            int    badgeColor;
            if (newState == UiState.CONNECTING) {
                badgeText  = "CONECTANDO...";
                badgeColor = c(R.color.color_connecting);
                startStatusPulse();
                startStatusHaloWave();
            } else if (newState == UiState.CONNECTED) {
                badgeText  = "CONECTADO";
                badgeColor = BtProxy.isGamingMode(this) ? c(R.color.color_gaming) : c(R.color.color_connected);
                stopStatusPulse();
                stopStatusHaloWave();
                if (statusHaloView != null) statusHaloView.setAlpha(0.15f);
            } else {
                badgeText  = "DESCONECTADO";
                badgeColor = c(R.color.color_disconnected);
                stopStatusPulse();
                stopStatusHaloWave();
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

        if (statusDetailsView != null) {
            if (newState == UiState.CONNECTING) {
                String detail;
                if      ("retrying".equals(lastKnownConnState)) detail = "• Buscando servidor disponible...";
                else if ("dropped".equals(lastKnownConnState))  detail = "• Reconectando al servidor...";
                else if ("failed".equals(lastKnownConnState))   detail = "• Reintentando conexión...";
                else                                             detail = "• Estableciendo conexión...";
                lastDetailText = detail;
                statusDetailsView.setVisibility(View.VISIBLE);
                statusDetailsView.setTextColor(c(R.color.color_connecting));
                if (stateChanged && canAnimate()) {
                    statusDetailsView.setAlpha(0f);
                    statusDetailsView.setText(detail);
                    statusDetailsView.animate().alpha(1f).setDuration(300).start();
                } else {
                    statusDetailsView.setText(detail);
                }
            } else if (newState == UiState.CONNECTED) {
                String eta  = autoDisconnectAtMs > 0 ? "Auto desconexión activa" : "Conexión activa";
                String full = "✓ " + eta;
                lastDetailText = full;
                statusDetailsView.setVisibility(View.VISIBLE);
                statusDetailsView.setTextColor(0xFF555555);
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
                    } else {
                        pingValueView.setTextColor(c(R.color.color_text_disabled));
                    }
                    lastPingMs = -1;
                }
            }
        }

        refreshGamingModeUi();
    }
}
