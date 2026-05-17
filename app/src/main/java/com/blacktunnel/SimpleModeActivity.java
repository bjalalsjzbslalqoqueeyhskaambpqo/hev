package com.blacktunnel;

import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

public class SimpleModeActivity extends ComponentActivity {
    private enum UiState { DISCONNECTED, CONNECTING, CONNECTED }

    private Button connectBtn;
    private TextView statusView;
    private TextView internalIdView;
    private TextView logsView;
    private UiState uiState = UiState.DISCONNECTED;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ActivityResultLauncher<Intent> vpnPermissionLauncher;

    private final Runnable stateTicker = new Runnable() {
        @Override
        public void run() {
            boolean running = BtVpnService.isRunningState();
            if (running && uiState != UiState.CONNECTED) setUiState(UiState.CONNECTED);
            if (!running && uiState == UiState.CONNECTED) setUiState(UiState.DISCONNECTED);
            refreshLogs();
            handler.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_mode);

        vpnPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        startVpn();
                    } else {
                        setUiState(UiState.DISCONNECTED);
                    }
                }
        );

        statusView = findViewById(R.id.txtStatus);
        internalIdView = findViewById(R.id.txtInternalId);
        logsView = findViewById(R.id.txtLogs);
        connectBtn = findViewById(R.id.btnConnect);

        String internalId = BtProxy.getOrCreateInternalId(this);
        internalIdView.setText("ID: " + internalId);

        setUiState(BtVpnService.isRunningState() ? UiState.CONNECTED : UiState.DISCONNECTED);

        connectBtn.setOnClickListener(v -> safeRun(() -> {
            if (uiState == UiState.CONNECTED) {
                stopVpn();
            } else if (uiState == UiState.DISCONNECTED) {
                requestVpnPermissionAndStart();
            } else if (uiState != UiState.CONNECTING) {
                cleanDisconnectState();
            }
        }));

        handler.post(stateTicker);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(stateTicker);
        super.onDestroy();
    }

    private void requestVpnPermissionAndStart() {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            setUiState(UiState.CONNECTING);
            vpnPermissionLauncher.launch(prepare);
        } else {
            startVpn();
        }
    }

    private void startVpn() {
        setUiState(UiState.CONNECTING);
        Intent i = new Intent(this, BtVpnService.class);
        i.setAction(BtVpnService.ACTION_START);
        startService(i);
    }

    private void stopVpn() {
        Intent i = new Intent(this, BtVpnService.class);
        i.setAction(BtVpnService.ACTION_STOP);
        startService(i);
        setUiState(UiState.DISCONNECTED);
    }

    private void safeRun(Runnable action) {
        try {
            action.run();
        } catch (Throwable ignored) {
            cleanDisconnectState();
        }
    }

    private void cleanDisconnectState() {
        try {
            Intent i = new Intent(this, BtVpnService.class);
            i.setAction(BtVpnService.ACTION_STOP);
            startService(i);
        } catch (Throwable ignored) {}
        setUiState(UiState.DISCONNECTED);
    }

    private void refreshLogs() {
        if (logsView == null) return;
        String logs = BtVpnService.dumpLogs();
        if (logs == null || logs.isBlank()) logs = "Sin logs";
        logsView.setText(logs);
    }

    private void setUiState(UiState state) {
        uiState = state;
        if (state == UiState.CONNECTED) {
            statusView.setText("Conectado");
            connectBtn.setText("Desconectar");
        } else if (state == UiState.CONNECTING) {
            statusView.setText("Conectando...");
            connectBtn.setText("Conectando...");
        } else {
            statusView.setText("Desconectado");
            connectBtn.setText("Conectar");
        }
    }
}
