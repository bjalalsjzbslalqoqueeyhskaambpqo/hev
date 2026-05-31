package com.blacktunnel;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import java.util.concurrent.Executors;

public class SimpleModeActivity extends ComponentActivity {

    private enum State { DISCONNECTED, CONNECTING, CONNECTED }

    private State       state   = State.DISCONNECTED;
    private Button      btn;
    private TextView   status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_mode);

        btn    = findViewById(R.id.btnConnect);
        status = findViewById(R.id.txtStatus);

        btn.setOnClickListener(v -> {
            if (state == State.DISCONNECTED) startVpn();
            else stopVpn();
        });

        startMonitoring();
    }

    private void startVpn() {
        state = State.CONNECTING;
        updateUi();

        Intent intent = new Intent(this, BtVpnService.class);
        intent.setAction(BtVpnService.ACTION_START);
        startForegroundService(intent);
    }

    private void stopVpn() {
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

    private void startMonitoring() {
        Executors.newSingleThreadExecutor().submit(() -> {
            while (true) {
                String logs = BtVpnService.dLogs();
                if (logs != null) {
                    if (logs.contains("tunnel ok") && state == State.CONNECTING) {
                        state = State.CONNECTED;
                        runOnUiThread(this::updateUi);
                    }
                    if (!BtVpnService.iRun() && state != State.DISCONNECTED) {
                        state = State.DISCONNECTED;
                        runOnUiThread(this::updateUi);
                    }
                }
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}