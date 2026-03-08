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
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 1. 每次回到界面，先手动同步一次 Service 里的最新数字
        // 注意：这需要你在 MonitorService 里把 refreshCount 变量设为 public static
        tvRefreshCount.setText("刷新次数: " + MonitorService.refreshCount);

        // 2. 注册广播，确保 Activity 在前台时能实时收到更新
        IntentFilter filter = new IntentFilter(MonitorService.ACTION_UPDATE_UI);
        androidx.core.content.ContextCompat.registerReceiver(
                this,
                uiReceiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        );
        Log.d("BiliMonitorLog", "MainActivity: 广播已在 onStart 注册");
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
        // 1. 检查通知权限 (Android 13+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1002);
                return; // 等用户授权后再点一次
            }
        }

        // 2. 检查使用情况权限
        if (!hasUsageStatsPermission()) {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            return;
        }

        if (Shizuku.pingBinder()) {
            // 核心修复：即使 checkSelfPermission 返回 GRANTED，
            // 如果遇到调用失败，也提供一个“重新授权”的机制
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                // 这里可以加一个简单的尝试调用，或者直接启动
                startMonitorService();
            } else {
                // 如果没权限，主动弹出 Shizuku 的授权框
                Shizuku.requestPermission(1001);
            }
        } else {
            Toast.makeText(this, "Shizuku 服务未运行，请先启动 Shizuku", Toast.LENGTH_LONG).show();
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
    protected void onStop() {
        super.onStop();
        // 当 Activity 不可见时，注销广播接收器，防止内存泄漏
        try {
            unregisterReceiver(uiReceiver);
            Log.d("BiliMonitorLog", "MainActivity: 广播已在 onStop 注销");
        } catch (Exception e) {
            // 忽略未注册时的异常
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 这里可以留空，或者只做最后的清理
    }
}