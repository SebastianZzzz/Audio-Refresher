package com.example.audiorefresher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

public class MonitorService extends Service {
    private static final String TAG = "BiliMonitorLog";
    // 1. 删除了 TARGET_PKG 常量
    private static final String CHANNEL_ID = "monitor_channel";
    public static final String ACTION_UPDATE_UI = "com.example.audiorefresher.UPDATE_UI";

    private ScheduledExecutorService scheduler;
    private String lastForegroundApp = "";
    public static int refreshCount = 0;

    // 2. 新增：动态获取目标包名的方法
    // 修改后的方法：获取用户选择的所有包名集合
    private java.util.Set<String> getTargetPackages() {
        // 从 SharedPreferences 中读取字符串集合
        // 如果用户还没选过，默认只监控 B 站
        java.util.Set<String> defaultSet = new java.util.HashSet<>();
        defaultSet.add("tv.danmaku.bili");

        return getSharedPreferences("config", MODE_PRIVATE)
                .getStringSet("target_pkgs", defaultSet);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ServiceStatusManager.setRunning(true);
        Log.d(TAG, "Service onCreate: 服务正在创建");
        createNotificationChannel();

        // 更新通知显示当前正在监控哪个包
        startForeground(1, getNotification("监控中: " + getTargetPackages()));

        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(screenReceiver, filter);
        } catch (Exception e) {
            Log.e(TAG, "注册广播失败: " + e.getMessage());
        }

        startPolling();
    }

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // 屏幕动作触发时直接刷新
            refreshMediaStatus();
        }
    };

    private void startPolling() {
        Log.d(TAG, "开始轮询线程...");
        scheduler = Executors.newSingleThreadScheduledExecutor();

        scheduler.scheduleWithFixedDelay(() -> {
            try {
                String currentApp = getTopPackageName();
                // 获取当前选中的所有目标 App 集合
                java.util.Set<String> targets = getTargetPackages();

                // 核心逻辑修改：
                // 如果“刚才”的应用在名单中，且“现在”的应用不在名单中（说明发生了失焦/切出）
                if (targets.contains(lastForegroundApp) && !targets.contains(currentApp)) {
                    Log.d(TAG, "检测到监控名单中的 App 失焦: " + lastForegroundApp + " -> " + currentApp);
                    refreshMediaStatus();
                }

                if (!currentApp.isEmpty()) {
                    lastForegroundApp = currentApp;
                }

            } catch (Exception e) {
                Log.e(TAG, "轮询异常: " + e.getMessage());
            }
        }, 0, 500, TimeUnit.MILLISECONDS);
    }

    private String getTopPackageName() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(now - 5000, now);
        UsageEvents.Event event = new UsageEvents.Event();
        String topPackage = "";
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                topPackage = event.getPackageName();
            }
        }
        return topPackage;
    }

    private void refreshMediaStatus() {
        if (!Shizuku.pingBinder()) return;

        new Thread(() -> {
            try {
                // 执行 pause/play 刷新
                rikka.shizuku.ShizukuRemoteProcess p1 = Shizuku.newProcess(
                        new String[]{"cmd", "media_session", "dispatch", "pause"}, null, null);
                p1.waitFor();

                rikka.shizuku.ShizukuRemoteProcess p2 = Shizuku.newProcess(
                        new String[]{"cmd", "media_session", "dispatch", "play"}, null, null);
                p2.waitFor();

                refreshCount++;
                ServiceStatusManager.updateCount(refreshCount);
                int targetCount = getTargetPackages().size();
                updateNotification("正在监控 " + targetCount + " 个应用 (已刷新 " + refreshCount + " 次)");

            } catch (Exception e) {
                Log.e(TAG, "Shizuku 执行异常: " + e.getMessage());
            }
        }).start();
    }

    private void updateNotification(String content) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(1, getNotification(content));
        }
    }

    private Notification getNotification(String content) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("媒体保活监控中")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Monitor Channel", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (scheduler != null) scheduler.shutdownNow();
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        ServiceStatusManager.setRunning(false);
        super.onDestroy();
    }
}