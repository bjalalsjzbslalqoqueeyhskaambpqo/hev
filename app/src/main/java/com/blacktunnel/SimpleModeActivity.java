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

public class SimpleModeActivity extends ComponentActivity {
    private static final int REQ_VPN = 3001;

    private Button connectBtn;
    private TextView logView;
    private boolean connected = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

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
            startActivityForResult(prepare, REQ_VPN);
        } else {
            startVpn();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) startVpn();
            else SimpleLog.i("Permiso VPN denegado");
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
