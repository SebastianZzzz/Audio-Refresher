package com.example.audiorefresher;

import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    private TextView tvUsageStatus, tvShizukuStatus, tvRefreshCount;

    // 接收来自 Service 的更新
    private final BroadcastReceiver uiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int count = intent.getIntExtra("count", 0);
            android.util.Log.d("BiliMonitorLog", "UI 收到更新广播，次数: " + count);
            tvRefreshCount.setText("刷新次数: " + count);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvUsageStatus = findViewById(R.id.tv_usage_status);
        tvShizukuStatus = findViewById(R.id.tv_shizuku_status);
        tvRefreshCount = findViewById(R.id.tv_refresh_count);

        findViewById(R.id.btn_start).setOnClickListener(v -> checkPermissionsAndStart());

        // --- 必须这样写才能解决 Android 14 闪退 ---
        IntentFilter filter = new IntentFilter(MonitorService.ACTION_UPDATE_UI);
        androidx.core.content.ContextCompat.registerReceiver(
                this,
                uiReceiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED // 明确指定不公开
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusUI();
    }

    private void updateStatusUI() {
        // 更新使用记录权限状态
        if (hasUsageStatsPermission()) {
            tvUsageStatus.setText("使用情况权限：已获得");
            tvUsageStatus.setTextColor(Color.GREEN);
        } else {
            tvUsageStatus.setText("使用情况权限：未获得 (点击启动检查)");
            tvUsageStatus.setTextColor(Color.RED);
        }

        // 更新 Shizuku 状态
        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                tvShizukuStatus.setText("Shizuku 授权：已获得");
                tvShizukuStatus.setTextColor(Color.GREEN);
            } else {
                tvShizukuStatus.setText("Shizuku 授权：未授权");
                tvShizukuStatus.setTextColor(Color.YELLOW);
            }
        } else {
            tvShizukuStatus.setText("Shizuku 授权：服务未运行");
            tvShizukuStatus.setTextColor(Color.RED);
        }
    }

    private void checkPermissionsAndStart() {
        if (!hasUsageStatsPermission()) {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            return;
        }

        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                startMonitorService();
                Toast.makeText(this, "监控服务已启动", Toast.LENGTH_SHORT).show();
            } else {
                Shizuku.requestPermission(1001);
                Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) startMonitorService();
                });
            }
        } else {
            Toast.makeText(this, "请先启动 Shizuku 应用", Toast.LENGTH_LONG).show();
        }
        updateStatusUI();
    }

    private boolean hasUsageStatsPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void startMonitorService() {
        Intent intent = new Intent(this, MonitorService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(uiReceiver);
    }
}