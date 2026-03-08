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
    private static final String TAG = "BiliMonitorLog"; // 方便过滤日志
    private static final String TARGET_PKG = "tv.danmaku.bili";
    private static final String CHANNEL_ID = "monitor_channel";
    public static final String ACTION_UPDATE_UI = "com.example.audiorefresher.UPDATE_UI";

    private ScheduledExecutorService scheduler;
    private String lastForegroundApp = "";
    public static int refreshCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate: 服务正在创建");
        createNotificationChannel();
        startForeground(1, getNotification("服务已启动，等待监控..."));

        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(screenReceiver, filter);
            Log.d(TAG, "BroadcastReceiver 注册成功");
        } catch (Exception e) {
            Log.e(TAG, "注册广播失败: " + e.getMessage());
        }

        startPolling();
    }

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                Log.d(TAG, "接收到息屏广播");
                refreshMediaStatus();
            }
        }
    };

    private void startPolling() {
        Log.d(TAG, "开始轮询线程...");
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                String currentApp = getTopPackageName();
                if (TARGET_PKG.equals(lastForegroundApp) && !TARGET_PKG.equals(currentApp)) {
                    Log.d(TAG, "检测到 B站 失焦: " + lastForegroundApp + " -> " + currentApp);
                    refreshMediaStatus();
                }
                lastForegroundApp = currentApp;
            } catch (Exception e) {
                Log.e(TAG, "轮询异常: " + e.getMessage());
            }
        }, 0, 1500, TimeUnit.MILLISECONDS);
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

        Log.d(TAG, "当前检测到的顶层应用: " + topPackage);
        return topPackage;
    }

    private void refreshMediaStatus() {
        Log.d(TAG, "准备刷新媒体状态...");

        // 强制先更新一下通知，证明逻辑跑到了这里
        updateNotification("正在尝试刷新 B 站状态...");

        if (!Shizuku.pingBinder()) {
            Log.e(TAG, "刷新失败: Shizuku Binder 不可用");
            updateNotification("错误：Shizuku 未授权");
            return;
        }

        new Thread(() -> {
            try {
                // 尝试执行命令
                Log.d(TAG, "执行 Shell 命令...");
                Process p1 = Shizuku.newProcess(new String[]{"cmd", "media_session", "dispatch", "pause"}, null, null);
                p1.waitFor();
                Thread.sleep(500);
                Process p2 = Shizuku.newProcess(new String[]{"cmd", "media_session", "dispatch", "play"}, null, null);
                p2.waitFor();

                refreshCount++;
                Log.d(TAG, "刷新成功，当前次数: " + refreshCount);

                // 更新通知和 UI
                updateNotification("已成功刷新播放状态 (" + refreshCount + " 次)");

                Intent intent = new Intent(ACTION_UPDATE_UI);
                intent.putExtra("count", refreshCount);
                sendBroadcast(intent);
            } catch (Exception e) {
                Log.e(TAG, "Shizuku 执行失败: " + e.getMessage());
                updateNotification("刷新失败: " + e.getMessage());
            }
        }).start();
    }

    @SuppressWarnings("MissingPermission")
    private void updateNotification(String content) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            // 针对 Android 13+ 的运行时权限检查
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(this,
                        android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "无法更新通知：未获得 POST_NOTIFICATIONS 权限");
                    return;
                }
            }
            // 已经过上方权限检查或处于旧版本系统，可以安全调用
            manager.notify(1, getNotification(content));
        }
    }

    private Notification getNotification(String content) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Bili 进程保活中")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Bili Monitor", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service onDestroy: 服务正在销毁");
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {}
        if (scheduler != null) scheduler.shutdown();
        super.onDestroy();
    }
}