package com.example.audiorefresher;

import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    private TextView tvUsageStatus, tvShizukuStatus, tvNotificationStatus, tvRefreshCount;
    private Button btnStart;

    // 接收来自 Service 的更新
    private final BroadcastReceiver uiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int count = intent.getIntExtra("count", 0);
            Log.d("BiliMonitorLog", "UI 收到更新广播，次数: " + count);
            tvRefreshCount.setText("刷新次数: " + count);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化视图
        tvUsageStatus = findViewById(R.id.tv_usage_status);
        tvShizukuStatus = findViewById(R.id.tv_shizuku_status);
        tvNotificationStatus = findViewById(R.id.tv_notification_status); // 请确保 XML 中有此 ID
        tvRefreshCount = findViewById(R.id.tv_refresh_count);
        btnStart = findViewById(R.id.btn_start);

        // 按钮点击逻辑：根据服务状态决定动作
        btnStart.setOnClickListener(v -> {
            if (MonitorService.isRunning) {
                stopMonitorService();
            } else {
                checkPermissionsAndStart();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 1. 同步 Service 里的最新数字
        tvRefreshCount.setText("刷新次数: " + MonitorService.refreshCount);

        // 2. 注册广播
        IntentFilter filter = new IntentFilter(MonitorService.ACTION_UPDATE_UI);
        ContextCompat.registerReceiver(
                this,
                uiReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
        Log.d("BiliMonitorLog", "MainActivity: 广播已在 onStart 注册");
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusUI();
    }

    private void updateStatusUI() {
        // 权限状态获取
        boolean hasUsage = hasUsageStatsPermission();
        boolean hasShizuku = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        boolean hasNotification = true;

        // 1. 更新使用情况权限文字
        if (hasUsage) {
            tvUsageStatus.setText("使用情况权限：已获得");
            tvUsageStatus.setTextColor(Color.GREEN);
        } else {
            tvUsageStatus.setText("使用情况权限：未获得 (点击下方修复)");
            tvUsageStatus.setTextColor(Color.RED);
        }

        // 2. 更新 Shizuku 状态文字
        if (Shizuku.pingBinder()) {
            if (hasShizuku) {
                tvShizukuStatus.setText("Shizuku 授权：已获得");
                tvShizukuStatus.setTextColor(Color.GREEN);
            } else {
                tvShizukuStatus.setText("Shizuku 授权：未授权");
                tvShizukuStatus.setTextColor(Color.RED);
            }
        } else {
            tvShizukuStatus.setText("Shizuku 授权：服务未运行");
            tvShizukuStatus.setTextColor(Color.RED);
        }

        // 3. 更新通知权限状态 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNotification = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            if (tvNotificationStatus != null) {
                tvNotificationStatus.setText(hasNotification ? "通知权限：已获得" : "通知权限：未获得");
                tvNotificationStatus.setTextColor(hasNotification ? Color.GREEN : Color.RED);
            }
        }

        // 4. 动态修改按钮状态 (核心逻辑)
        if (MonitorService.isRunning) {
            // 状态：已启动 -> 红色“暂停服务”
            btnStart.setText("暂停服务");
            btnStart.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#F44336"))); // 红色
            btnStart.setTextColor(Color.WHITE);
        } else {
            if (hasUsage && hasShizuku && hasNotification) {
                // 状态：权限全有但未启动 -> 绿色“启动监控”
                btnStart.setText("启动监控");
                btnStart.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#4CAF50"))); // 绿色
                btnStart.setTextColor(Color.WHITE);
            } else {
                // 状态：权限不足 -> 黄色“修复权限问题”
                btnStart.setText("修复权限问题");
                btnStart.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FFC107"))); // 黄色
                btnStart.setTextColor(Color.BLACK);
            }
        }
    }

    private void checkPermissionsAndStart() {
        // 1. 检查通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1002);
                return;
            }
        }

        // 2. 检查使用情况权限
        if (!hasUsageStatsPermission()) {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            return;
        }

        // 3. 检查 Shizuku
        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                startMonitorService();
            } else {
                Shizuku.requestPermission(1001);
            }
        } else {
            Toast.makeText(this, "请先启动 Shizuku 服务", Toast.LENGTH_SHORT).show();
        }
        updateStatusUI();
    }

    private void startMonitorService() {
        Intent intent = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        // 启动后延迟刷新一下 UI 状态
        btnStart.postDelayed(this::updateStatusUI, 500);
    }

    private void stopMonitorService() {
        Intent intent = new Intent(this, MonitorService.class);
        stopService(intent);
        // 重置计数器和状态
        MonitorService.refreshCount = 0;
        tvRefreshCount.setText("刷新次数: 0");
        Toast.makeText(this, "监控已停止，计数已重置", Toast.LENGTH_SHORT).show();
        // 停止后延迟刷新 UI 状态
        btnStart.postDelayed(this::updateStatusUI, 300);
    }

    private boolean hasUsageStatsPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(uiReceiver);
        } catch (Exception ignored) {}
    }
}