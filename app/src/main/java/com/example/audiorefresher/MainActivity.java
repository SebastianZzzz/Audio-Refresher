package com.example.audiorefresher;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    private TextView tvUsageStatus, tvShizukuStatus, tvNotificationStatus, tvRefreshCount;
    private Button btnStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. 初始化视图
        tvUsageStatus = findViewById(R.id.tv_usage_status);
        tvShizukuStatus = findViewById(R.id.tv_shizuku_status);
        tvNotificationStatus = findViewById(R.id.tv_notification_status);
        tvRefreshCount = findViewById(R.id.tv_refresh_count);
        btnStart = findViewById(R.id.btn_start);

        // 2. 【观察者模式】监听服务运行状态
        ServiceStatusManager.getIsRunning().observe(this, running -> {
            updateStatusUI();
        });

        // 3. 【观察者模式】监听计数器变化
        ServiceStatusManager.getRefreshCount().observe(this, count -> {
            tvRefreshCount.setText("刷新次数: " + count);
        });

        // 4. 按钮点击逻辑
        btnStart.setOnClickListener(v -> {
            if (Boolean.TRUE.equals(ServiceStatusManager.getIsRunning().getValue())) {
                stopMonitorService();
            } else {
                checkPermissionsAndStart();
            }
        });

        Button btnSelectApps = findViewById(R.id.btn_select_apps);
        btnSelectApps.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#4CAF50")));
        btnSelectApps.setOnClickListener(v -> {
            startActivity(new Intent(this, AppSelectActivity.class));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusUI();
    }

    private void updateStatusUI() {
        boolean hasUsage = hasUsageStatsPermission();
        boolean hasShizuku = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        boolean hasNotification = true;

        // 获取当前监控的应用数量
        int selectedCount = getSavedPackages().size();
        tvUsageStatus.setText(hasUsage ? "已监控 " + selectedCount + " 个应用" : "使用情况权限：未获得 (点击修复)");
        tvUsageStatus.setTextColor(hasUsage ? Color.parseColor("#4CAF50") : Color.parseColor("#F44336"));

        if (Shizuku.pingBinder()) {
            tvShizukuStatus.setText(hasShizuku ? "Shizuku 授权：已获得" : "Shizuku 授权：未授权");
            tvShizukuStatus.setTextColor(hasUsage ? Color.parseColor("#4CAF50") : Color.parseColor("#F44336"));
        } else {
            tvShizukuStatus.setText("Shizuku 授权：服务未运行");
            tvShizukuStatus.setTextColor(Color.parseColor("#F44336"));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNotification = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            if (tvNotificationStatus != null) {
                tvNotificationStatus.setText(hasNotification ? "通知权限：已获得" : "通知权限：未获得");
                tvNotificationStatus.setTextColor(hasUsage ? Color.parseColor("#4CAF50") : Color.parseColor("#F44336"));
            }
        }

        if (Boolean.TRUE.equals(ServiceStatusManager.getIsRunning().getValue())) {
            btnStart.setText("暂停服务");
            btnStart.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#F44336")));
            btnStart.setTextColor(Color.WHITE);
        } else {
            if (hasUsage && hasShizuku && hasNotification) {
                btnStart.setText("启动监控");
                btnStart.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#4CAF50")));
                btnStart.setTextColor(Color.WHITE);
            } else {
                btnStart.setText("修复权限问题");
                btnStart.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FFC107")));
                btnStart.setTextColor(Color.BLACK);
            }
        }
    }

    private void checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1002);
                return;
            }
        }
        if (!hasUsageStatsPermission()) {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            return;
        }
        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                startMonitorService();
            } else {
                Shizuku.requestPermission(1001);
            }
        } else {
            Toast.makeText(this, "请先启动 Shizuku 服务", Toast.LENGTH_SHORT).show();
        }
    }

    private void startMonitorService() {
        Intent intent = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopMonitorService() {
        stopService(new Intent(this, MonitorService.class));
        ServiceStatusManager.updateCount(0);
        Toast.makeText(this, "监控已停止", Toast.LENGTH_SHORT).show();
    }

    private boolean hasUsageStatsPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    // --- 多选逻辑核心修改 ---

    private Set<String> getSavedPackages() {
        SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
        // 默认监控 B 站
        Set<String> defaultSet = new HashSet<>();
        defaultSet.add("tv.danmaku.bili");
        return prefs.getStringSet("target_pkgs", defaultSet);
    }
}