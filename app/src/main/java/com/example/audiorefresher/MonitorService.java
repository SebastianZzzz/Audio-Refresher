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

    private long lastRefreshTime = 0; // 用于防抖

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
        createNotificationChannel();
        startForeground(1, getNotification("监控中: " + getTargetPackages()));

        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF); // 只保留熄屏，亮屏和解锁没必要刷新
            registerReceiver(screenReceiver, filter);
        } catch (Exception e) {
            Log.e(TAG, "注册广播失败: " + e.getMessage());
        }

        startPolling();
    }

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                refreshMediaStatus("屏幕关闭", "系统广播");
            }
        }
    };

    private void startPolling() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                String currentApp = getTopPackageName();
                java.util.Set<String> targets = getTargetPackages();
                if (currentApp.isEmpty()) return;

                if (targets.contains(currentApp)) {
                    if (!currentApp.equals(lastForegroundApp)) {
                        lastForegroundApp = currentApp;
                    }
                } else {
                    if (!lastForegroundApp.isEmpty()) {
                        String targetApp = lastForegroundApp;
                        refreshMediaStatus("应用失焦", targetApp);
                        lastForegroundApp = ""; // 触发后立即清空，保证单次触发
                    }
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

    private void refreshMediaStatus(String reason, String targetApp) {
        // 1. 防抖：1秒内不重复刷新
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastRefreshTime < 1000) return;

        // 2. 检查 Shizuku
        if (!Shizuku.pingBinder()) return;

        // 3. 检查音频状态：只有在播放时才刷新
        android.media.AudioManager audioManager = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null && !audioManager.isMusicActive()) {
            Log.d(TAG, "跳过刷新: 当前无音频播放 (来源: " + reason + ")");
            return;
        }

        lastRefreshTime = currentTime;

        new Thread(() -> {
            try {
                // 打印详细日志
                Log.i(TAG, ">>> 执行刷新! 原因: " + reason + " | 针对App: " + targetApp);

                Shizuku.newProcess(new String[]{"cmd", "media_session", "dispatch", "pause"}, null, null).waitFor();
                Shizuku.newProcess(new String[]{"cmd", "media_session", "dispatch", "play"}, null, null).waitFor();

                refreshCount++;
                ServiceStatusManager.updateCount(refreshCount);
                updateNotification("已刷新 " + refreshCount + " 次 (来源: " + reason + ")");
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