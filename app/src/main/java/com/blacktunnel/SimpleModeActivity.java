package com.blacktunnel;

import android.app.AlertDialog;
import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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
    private static final String PREF_UI    = "ui_state";
    private static final String KEY_HIDE_ID = "hide_internal_id";
    private static final int    HOTSPOT_PROXY_PORT = 7071;

    private enum UiState { DISCONNECTED, CONNECTING, CONNECTED }

    private Button    connectBtn;
    private Button    copyIdBtn;
    private TextView  statusBadgeView;
    private View      statusDotView;
    private View      statusHaloView;
    private TextView  statusDetailsView;
    private TextView  deviceIdView;
    private TextView  userValueView;
    private TextView  daysValueView;
    private TextView  pingValueView;
    private Switch    gamingModeSwitch;
    private TextView  gamingModeBadgeView;
    private TextView  gamingDescriptionView;
    private TextView  gamingSelectedCountView;
    private Button    selectGamingAppsBtn;
    private LinearLayout gamingModePanel;
    private LinearLayout gamingControlsLayout;
    private View         panelConnectionView;
    private Switch    hotspotSwitch;
    private TextView  hotspotInfoView;
    private Animator  statusPulseAnimator;
    private Animator  statusHaloAnimator;

    private final ExecutorService appLoadExecutor = Executors.newSingleThreadExecutor();
    private String  internalId           = "";
    private UiState uiState              = UiState.DISCONNECTED;
    private long    connectingSinceMs    = 0L;
    private boolean lastRunning          = false;
    private long    autoDisconnectAtMs   = -1L;
    private String  authState            = "";
    private boolean hideInternalId       = false;
    private boolean applyingRuntimeChanges = false;
    private int     authLogCursor          = 0;
    private boolean awaitingFreshAuth      = false;
    private int     lastPingMs           = -1;

    private final Runnable autoDisconnectRunnable = this::runAutoDisconnect;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<Intent> vpnPermissionLauncher;

    private final Runnable stateTicker = new Runnable() {
        @Override public void run() {
            try {
                syncStateFromService();
                refreshServiceState();
            } catch (Throwable ignored) {}
            handler.postDelayed(this, 800);
        }
    };

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

        connectBtn            = findViewById(R.id.btnConnect);
        copyIdBtn             = findViewById(R.id.btnCopyLogs);
        statusBadgeView       = findViewById(R.id.txtStatusBadge);
        statusDotView         = findViewById(R.id.viewStatusDot);
        statusHaloView        = findViewById(R.id.viewStatusHalo);
        statusDetailsView     = findViewById(R.id.txtStatusDetails);
        deviceIdView          = findViewById(R.id.txtDeviceId);
        userValueView         = findViewById(R.id.txtUser);
        daysValueView         = findViewById(R.id.txtDays);
        pingValueView         = findViewById(R.id.txtPingValue);
        gamingModeSwitch      = findViewById(R.id.switchGamingMode);
        gamingModeBadgeView   = findViewById(R.id.txtGamingBadge);
        gamingDescriptionView = findViewById(R.id.txtGamingDescription);
        gamingSelectedCountView = findViewById(R.id.txtGamingSelectedCount);
        selectGamingAppsBtn   = findViewById(R.id.btnSelectGamingApps);
        gamingModePanel       = findViewById(R.id.panelGamingMode);
        gamingControlsLayout  = findViewById(R.id.layoutGamingControls);
        panelConnectionView   = findViewById(R.id.panelConnection);
        hotspotSwitch         = findViewById(R.id.switchHotspot);
        hotspotInfoView       = findViewById(R.id.txtHotspotInfo);

        internalId = BtProxy.getOrCreateInternalId(this);
        BtProxy.applyStoredGamingMode(this);
        hideInternalId = getSharedPreferences(PREF_UI, MODE_PRIVATE).getBoolean(KEY_HIDE_ID, false);
        if (deviceIdView != null) deviceIdView.setText("ID: " + internalId);
        refreshDeviceIdVisibility();

        boolean running = BtVpnService.isRunningState();
        setUiState(running ? UiState.CONNECTED : UiState.DISCONNECTED);
        lastRunning = running;
        refreshServiceState();
        if (!BtProxy.isNativeReady()) {
            setUiState(UiState.DISCONNECTED);
            connectBtn.setEnabled(false);
            String reason = BtProxy.getNativeLoadError();
            if (statusDetailsView != null) {
                statusDetailsView.setText(
                    "Error al iniciar motor nativo. Reinstalá la app para tu arquitectura.\n" + reason
                );
            }
            Toast.makeText(this, "No se pudo cargar el motor nativo", Toast.LENGTH_LONG).show();
        }

        connectBtn.setOnClickListener(v -> {
            if (!BtProxy.isNativeReady()) {
                Toast.makeText(this, "Motor nativo no disponible", Toast.LENGTH_SHORT).show();
                return;
            }
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
                        isChecked ? "Modo gaming: activando..." : "Modo normal: aplicando...",
                        isChecked ? "Modo gaming activo" : "Modo normal activo",
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
                    BtVpnService.startLocalProxy(HOTSPOT_PROXY_PORT);
                    if (hotspotInfoView != null) {
                        hotspotInfoView.setText("Proxy: " + ip + ":" + HOTSPOT_PROXY_PORT);
                        hotspotInfoView.setVisibility(View.VISIBLE);
                    }
                } else {
                    BtVpnService.stopLocalProxy();
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

    private boolean canAnimate() { return android.animation.ValueAnimator.areAnimatorsEnabled(); }

    private void startStatusPulse() {
        if (statusDotView == null || !canAnimate()) return;
        if (statusPulseAnimator == null) statusPulseAnimator = AnimatorInflater.loadAnimator(this, R.animator.pulse);
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
        if (statusHaloAnimator == null) statusHaloAnimator = AnimatorInflater.loadAnimator(this, R.animator.status_halo_wave);
        statusHaloAnimator.setTarget(statusHaloView);
        if (!statusHaloAnimator.isStarted()) statusHaloAnimator.start();
    }

    private void stopStatusHaloWave() {
        if (statusHaloAnimator != null) statusHaloAnimator.cancel();
        if (statusHaloView != null) {
            statusHaloView.setScaleX(1f); statusHaloView.setScaleY(1f); statusHaloView.setAlpha(0.20f);
        }
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
                String first = selected.get(0);
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
        if (connectBtn != null && uiState != UiState.CONNECTED)
            connectBtn.setText(enabled ? getString(R.string.connect_gaming) : getString(R.string.connect));
    }

    private void openGamingAppsDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_gaming_apps, null, false);
        EditText     searchView    = dialogView.findViewById(R.id.editSearchApps);
        TextView     counterView   = dialogView.findViewById(R.id.txtPickerCounter);
        LinearLayout selectedLayout= dialogView.findViewById(R.id.layoutSelectedApps);
        ListView     listView      = dialogView.findViewById(R.id.listGamingApps);

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
                Toast.makeText(this, "Máximo 3 aplicaciones en modo gaming", Toast.LENGTH_SHORT).show();
                adapter.notifyDataSetChanged();
                return;
            }
            if (checked) selectedPackages.add(app.packageName);
            else selectedPackages.remove(app.packageName);
            adapter.notifyDataSetChanged();
            refreshPinned[0].run();
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            AppOption app = adapter.getItem(position);
            boolean checked = !selectedPackages.contains(app.packageName);
            if (checked && selectedPackages.size() >= 3 && !selectedPackages.contains(app.packageName)) {
                Toast.makeText(this, "Máximo 3 aplicaciones en modo gaming", Toast.LENGTH_SHORT).show();
                return;
            }
            if (checked) selectedPackages.add(app.packageName);
            else selectedPackages.remove(app.packageName);
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
        PackageManager pm = getPackageManager();
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

        @Override public int      getCount()              { return filteredApps.size(); }
        @Override public AppOption getItem(int position)  { return filteredApps.get(position); }
        @Override public long     getItemId(int position) { return position; }

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


    private int currentLogSize() {
        String logs = BtVpnService.dumpLogs();
        return logs == null ? 0 : logs.length();
    }

    private void markAuthCursorNow() {
        authLogCursor = currentLogSize();
        awaitingFreshAuth = true;
    }

    private void resetTransientUiStateForNewAttempt() {
        handler.removeCallbacks(autoDisconnectRunnable);
        autoDisconnectAtMs = -1L;
        lastPingMs = -1;
        if (pingValueView != null) {
            pingValueView.setText("--");
            pingValueView.setTextColor(c(R.color.color_text_disabled));
        }
    }

    private void startVpnWithPermission() {
        setUiState(UiState.CONNECTING);
        authState = "";
        resetTransientUiStateForNewAttempt();
        markAuthCursorNow();
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
        setUiState(UiState.CONNECTING);
    }

    private void stopVpn() {
        Intent i = new Intent(this, BtVpnService.class);
        i.setAction(BtVpnService.ACTION_STOP);
        startService(i);
        setUiState(UiState.DISCONNECTED);
        handler.removeCallbacks(autoDisconnectRunnable);
        autoDisconnectAtMs = -1L;
        awaitingFreshAuth = false;
    }

    private void applyGamingChangesIfRunning() {
        if (!BtVpnService.isRunningState()) return;
        applyingRuntimeChanges = true;
        connectingSinceMs = SystemClock.elapsedRealtime();
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

    private void syncStateFromService() {
        boolean running = BtVpnService.isRunningState();
        if (running && !lastRunning) {
            authState = ""; applyingRuntimeChanges = false; setUiState(UiState.CONNECTED);
        } else if (!running && lastRunning) {
            applyingRuntimeChanges = false; setUiState(UiState.DISCONNECTED);
        } else if (!running && uiState == UiState.CONNECTING) {
            if (SystemClock.elapsedRealtime() - connectingSinceMs > 9000) {
                applyingRuntimeChanges = false; setUiState(UiState.DISCONNECTED);
                awaitingFreshAuth = false;
                authLogCursor = currentLogSize();
            }
        } else if (running && applyingRuntimeChanges) {
            applyingRuntimeChanges = false; setUiState(UiState.CONNECTED);
        }
        lastRunning = running;
    }

    private void refreshServiceState() {
        String logs = BtVpnService.dumpLogs();
        updateServerAuthStatus(logs, BtVpnService.isRunningState());
        updateUserMetadata(logs);
    }

    private void updateServerAuthStatus(String logs, boolean running) {
        if (logs == null || statusDetailsView == null) return;
        int safeCursor = Math.max(0, Math.min(authLogCursor, logs.length()));
        String window = logs.substring(safeCursor);
        String latestAuth = findLatestAuthState(window);
        if (latestAuth.isEmpty() && awaitingFreshAuth) return;
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
            awaitingFreshAuth = false;
            authLogCursor = logs.length();
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
            awaitingFreshAuth = false;
            authLogCursor = logs.length();
        } else if ("ok".equals(latestAuth)) {
            authState = "";
            setHideInternalId(true);
            awaitingFreshAuth = false;
            authLogCursor = logs.length();
        } else if (!running) {
            authLogCursor = logs.length();
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
            if (lower.contains("usuario expirado")     || lower.contains("expired"))       return "expired";
            if (lower.contains("user_name=") || lower.contains("user_days=") || lower.contains("ping_ms=")) return "ok";
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
            if (idx >= 0 && pingValueView != null) {
                String v = line.substring(idx + "ping_ms=".length()).trim();
                if (!v.isEmpty()) {
                    try { animatePingTo(Integer.parseInt(v.replaceAll("[^0-9]", ""))); }
                    catch (Exception ignored) {
                        pingValueView.setText("--");
                        pingValueView.setTextColor(c(R.color.color_text_disabled));
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
        android.animation.ValueAnimator counter = android.animation.ValueAnimator.ofInt(start, targetPing);
        counter.setDuration(400);
        counter.addUpdateListener(a -> pingValueView.setText(String.valueOf((int) a.getAnimatedValue())));
        counter.start();
        android.animation.ValueAnimator colorAnim = android.animation.ValueAnimator.ofObject(
            new android.animation.ArgbEvaluator(),
            pingValueView.getCurrentTextColor(), resolvePingColor(targetPing));
        colorAnim.setDuration(600);
        colorAnim.addUpdateListener(a -> pingValueView.setTextColor((Integer) a.getAnimatedValue()));
        colorAnim.start();
        lastPingMs = targetPing;
    }

    private int resolvePingColor(int ping) {
        if (ping < 80)  return c(R.color.color_connected);
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
        uiState = newState;
        if (connectBtn != null) {
            if (newState == UiState.CONNECTING) {
                connectBtn.setEnabled(false);
                connectBtn.setActivated(false);
                connectBtn.setText("Conectando...");
                connectBtn.setBackgroundResource(R.drawable.btn_connecting_selector);
                connectBtn.setTextColor(c(R.color.color_text_primary));
            } else if (newState == UiState.CONNECTED) {
                connectBtn.setEnabled(true);
                connectBtn.setActivated(true);
                connectBtn.setText(R.string.disconnect);
                connectBtn.setBackgroundResource(R.drawable.btn_disconnect_selector);
                connectBtn.setTextColor(c(R.color.color_text_primary));
            } else {
                connectBtn.setEnabled(true);
                connectBtn.setActivated(false);
                connectBtn.setText(BtProxy.isGamingMode(this) ? getString(R.string.connect_gaming) : getString(R.string.connect));
                connectBtn.setBackgroundResource(R.drawable.btn_connect_selector);
                connectBtn.setTextColor(0xFF050505);
            }
        }
        if (statusDotView != null) {
            int col = newState == UiState.CONNECTED ? c(R.color.color_connected)
                    : newState == UiState.CONNECTING ? c(R.color.color_connecting)
                    : c(R.color.color_text_disabled);
            statusDotView.setBackgroundTintList(ColorStateList.valueOf(col));
        }
        if (statusHaloView != null) {
            int col = newState == UiState.CONNECTED ? c(R.color.color_connected)
                    : newState == UiState.CONNECTING ? c(R.color.color_connecting)
                    : c(R.color.color_text_disabled);
            statusHaloView.setBackgroundTintList(ColorStateList.valueOf(col));
        }
        if (statusBadgeView != null) {
            if (newState == UiState.CONNECTING) {
                statusBadgeView.setText("CONECTANDO...");
                statusBadgeView.setTextColor(c(R.color.color_connecting));
                startStatusPulse(); startStatusHaloWave();
            } else if (newState == UiState.CONNECTED) {
                statusBadgeView.setText("CONECTADO");
                statusBadgeView.setTextColor(BtProxy.isGamingMode(this) ? c(R.color.color_gaming) : c(R.color.color_connected));
                stopStatusPulse(); stopStatusHaloWave();
                if (statusHaloView != null) statusHaloView.setAlpha(0.15f);
            } else {
                statusBadgeView.setText("DESCONECTADO");
                statusBadgeView.setTextColor(c(R.color.color_disconnected));
                stopStatusPulse(); stopStatusHaloWave();
                if (statusHaloView != null) statusHaloView.setAlpha(0.20f);
            }
            statusBadgeView.setShadowLayer(0f, 0f, 0f, 0);
        }
        if (statusDetailsView != null) {
            if (newState == UiState.CONNECTING) {
                statusDetailsView.setVisibility(View.VISIBLE);
                statusDetailsView.setText("• Estableciendo conexión...");
                statusDetailsView.setTextColor(c(R.color.color_connecting));
            } else if (newState == UiState.CONNECTED) {
                String eta = autoDisconnectAtMs > 0 ? "Autodesconexión local activa" : "Conexión activa";
                statusDetailsView.setVisibility(View.VISIBLE);
                statusDetailsView.setText("✓ " + eta);
                statusDetailsView.setTextColor(0xFF555555);
                if (canAnimate()) statusDetailsView.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_slide_in));
            } else {
                statusDetailsView.setVisibility(View.GONE);
                if (pingValueView != null) {
                    pingValueView.setText("--");
                    pingValueView.setTextColor(c(R.color.color_text_disabled));
                    lastPingMs = -1;
                }
            }
        }
        refreshGamingModeUi();
    }
}
