package com.blacktunnel;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import java.util.concurrent.Executors;

public class SimpleModeActivity extends ComponentActivity {

    private enum State { DISCONNECTED, CONNECTING, CONNECTED }

    private State       state   = State.DISCONNECTED;
    private Button      btn;
    private Button      copyBtn;
    private TextView    status;
    private TextView    logView;
    private StringBuilder fullLog = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_mode);

        btn     = findViewById(R.id.btnConnect);
        copyBtn = findViewById(R.id.btnCopy);
        status  = findViewById(R.id.txtStatus);
        logView = findViewById(R.id.txtLog);

        btn.setOnClickListener(v -> {
            if (state == State.DISCONNECTED) startVpn();
            else stopVpn();
        });

        copyBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("logs", fullLog.toString()));
        });

        startMonitoring();
    }

    private void startVpn() {
        state = State.CONNECTING;
        updateUi();
        appendLog(">> Iniciando VPN...\n");

        Intent intent = new Intent(this, BtVpnService.class);
        intent.setAction(BtVpnService.ACTION_START);
        startForegroundService(intent);
    }

    private void stopVpn() {
        appendLog(">> Deteniendo VPN...\n");
        Intent intent = new Intent(this, BtVpnService.class);
        intent.setAction(BtVpnService.ACTION_STOP);
        startService(intent);

        state = State.DISCONNECTED;
        updateUi();
    }

    private void updateUi() {
        switch (state) {
            case DISCONNECTED:
                btn.setText(R.string.connect);
                status.setText("DESCONECTADO");
                status.setTextColor(getColor(R.color.color_disconnected));
                break;
            case CONNECTING:
                btn.setText(R.string.disconnect);
                status.setText("CONECTANDO...");
                status.setTextColor(getColor(R.color.color_connecting));
                break;
            case CONNECTED:
                btn.setText(R.string.disconnect);
                status.setText("CONECTADO");
                status.setTextColor(getColor(R.color.color_connected));
                break;
        }
    }

    private void appendLog(String line) {
        fullLog.append(line);
        runOnUiThread(() -> {
            logView.setText(fullLog.toString());
            ((ScrollView) logView.getParent()).scrollTo(0, logView.getHeight());
        });
    }

    private void startMonitoring() {
        String lastLog = "";
        Executors.newSingleThreadExecutor().submit(() -> {
            while (true) {
                String logs = BtVpnService.dLogs();
                if (logs != null && !logs.equals(lastLog)) {
                    String[] lines = logs.split("\n");
                    for (String line : lines) {
                        if (!line.trim().isEmpty()) {
                            appendLog(line + "\n");
                        }
                    }
                    lastLog = logs;

                    if (logs.contains("tunnel ok") && state == State.CONNECTING) {
                        state = State.CONNECTED;
                        runOnUiThread(this::updateUi);
                    }
                    if (!BtVpnService.iRun() && state != State.DISCONNECTED) {
                        state = State.DISCONNECTED;
                        runOnUiThread(this::updateUi);
                    }
                }
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}