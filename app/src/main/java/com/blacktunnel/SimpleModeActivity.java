package com.blacktunnel;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import java.util.concurrent.CopyOnWriteArrayList;

public class SimpleModeActivity extends ComponentActivity {

    private enum State { DISCONNECTED, CONNECTING, CONNECTED }

    private State state = State.DISCONNECTED;
    private Button btn;
    private Button copyBtn;
    private TextView status;
    private TextView logView;
    private final CopyOnWriteArrayList<String> pendingLogs = new CopyOnWriteArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

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
            cm.setPrimaryClip(ClipData.newPlainText("logs", logView.getText()));
        });

        BtProxy.setLogListener(this::onLogReceived);
        BtProxy.setStateListener(this::onStateReceived);
    }

    private void onLogReceived(String line) {
        ui.post(() -> {
            appendLog(line + "\n");
        });
    }

    private void onStateReceived(String stateStr) {
        ui.post(() -> {
            switch (stateStr) {
                case "running" -> {
                    if (state == State.CONNECTING) {
                        state = State.CONNECTED;
                        updateUi();
                    }
                }
                case "stopped" -> {
                    state = State.DISCONNECTED;
                    updateUi();
                }
            }
        });
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
        logView.append(line);
        ((ScrollView) logView.getParent()).scrollTo(0, logView.getHeight());
    }

    @Override
    protected void onDestroy() {
        BtProxy.setLogListener(null);
        BtProxy.setStateListener(null);
        super.onDestroy();
    }
}