package com.blacktunnel;

import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

public class SimpleModeActivity extends ComponentActivity {
    private Button connectBtn;
    private TextView logView;
    private boolean connected = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<Intent> vpnPermissionLauncher;

    private final Runnable logTicker = new Runnable() {
        @Override
        public void run() {
            if (logView != null) {
                logView.setText(SimpleLog.dump());
            }
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
                    else SimpleLog.i("Permiso VPN denegado");
                }
        );

        connectBtn = findViewById(R.id.btnConnect);
        logView = findViewById(R.id.txtLogs);

        connectBtn.setOnClickListener(v -> {
            if (connected) {
                stopVpn();
            } else {
                startVpnWithPermission();
            }
        });

        handler.post(logTicker);
        SimpleLog.i("Simple mode listo");
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(logTicker);
        super.onDestroy();
    }

    private void startVpnWithPermission() {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            vpnPermissionLauncher.launch(prepare);
        } else {
            startVpn();
        }
    }

    private void startVpn() {
        Intent i = new Intent(this, BtVpnService.class);
        i.setAction("com.blacktunnel.START");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
        connected = true;
        connectBtn.setText("Desconectar");
        SimpleLog.i("Solicitando conexión...");
    }

    private void stopVpn() {
        Intent i = new Intent(this, BtVpnService.class);
        i.setAction("com.blacktunnel.STOP");
        startService(i);
        connected = false;
        connectBtn.setText("Conectar");
        SimpleLog.i("Solicitando desconexión...");
    }
}
