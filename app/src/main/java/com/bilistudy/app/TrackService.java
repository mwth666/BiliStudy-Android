package com.bilistudy.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaController;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.widget.Toast;

/**
 * TrackService —— 常驻前台服务，每秒采样 B 站播放位置。
 * 计时口径与浏览器插件一致：
 *  - 按 currentTime 的增量累计（3 倍速 1 秒 = 记 3 秒内容进度）
 *  - 暂停 / 缓冲 / 切后台不计时
 *  - 拖动跳转丢弃该段，防止秒通关
 */
public class TrackService extends Service {
    private static final String CHANNEL = "bili_study_track";
    private static final int NOTIF_ID = 1001;

    private static final long TICK_MS = 1000;
    private static final double SEEK_TOL = 2.0;
    private static final double FLUSH_AT = 10.0;
    private static final double MAX_GAP = 15.0;
    private static final double STALL_LIMIT = 8.0;

    private Handler handler;
    private StatsStore store;
    private SessionBridge.Snapshot anchor;
    private long anchorWall;
    private double bufferSeconds;
    private double bufferMaxPos;
    private double bufferDuration;
    private String bufferTitle;
    private String bufferKey;
    private double stall;

    private double todaySeconds;
    private String lastStatus = "等待中";

    @Override
    public void onCreate() {
        super.onCreate();
        SessionBridge.init(this);
        store = new StatsStore(this);
        handler = new Handler();
        createChannel();
        startForeground(NOTIF_ID, buildNotification());
        handler.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SessionBridge.findActive();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (handler != null) handler.removeCallbacks(tick);
        flush("destroy");
        super.onDestroy();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                step();
            } catch (Throwable ignored) {
                // 采样绝不能把服务搞崩
            }
            updateNotification();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private void step() {
        SessionBridge.findActive(); // 每次兜底查一次，覆盖通知没来的情况
        MediaController c = SessionBridge.controller();
        SessionBridge.Snapshot s = SessionBridge.sample(c);

        String day = StatsStore.dayKey();
        todaySeconds = store.secondsOf(day);

        if (c == null || s.title == null || s.title.length() == 0 || s.durationSec <= 0) {
            lastStatus = "未检测到播放";
            anchor = null;
            return;
        }

        String key = "app:" + md5(s.title) + ":" + (long) s.durationSec;

        // 换了视频：先结算旧的
        if (bufferKey != null && !bufferKey.equals(key)) flush("lesson-change");

        if (bufferKey == null) {
            bufferKey = key;
            bufferTitle = s.title;
            bufferDuration = s.durationSec;
            bufferMaxPos = 0;
            bufferSeconds = 0;
        }
        bufferTitle = s.title;
        bufferDuration = s.durationSec;

        if (!s.playing) {
            lastStatus = "已暂停";
            anchor = null;
            stall = 0;
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (anchor == null || anchor.positionSec < 0) {
            anchor = s;
            anchorWall = now;
            lastStatus = "播放中";
            return;
        }

        double wall = (now - anchorWall) / 1000.0;
        double step = s.positionSec - anchor.positionSec;
        double expected = wall * s.speed;
        boolean isSeek = step < -0.5 || step > expected + SEEK_TOL + wall * 0.6;

        anchor = s;
        anchorWall = now;

        if (isSeek) {
            lastStatus = "播放中";
            stall = 0;
            return;
        }

        if (step > 0 && wall > 0 && wall <= MAX_GAP) {
            double gained = Math.min(step, expected + 1.5);
            bufferSeconds += gained;
            bufferMaxPos = Math.max(bufferMaxPos, s.positionSec);
            stall = 0;
            lastStatus = "播放中 · " + s.speed + "x";
            if (bufferSeconds >= FLUSH_AT) flush("threshold");
            return;
        }

        // 播放器没上报进度但仍在播：累计停顿，超过阈值按实际时长兜底
        stall += wall;
        if (stall >= STALL_LIMIT) {
            bufferSeconds += Math.min(wall, MAX_GAP);
            lastStatus = "播放中 · 兜底计时";
            if (bufferSeconds >= FLUSH_AT) flush("threshold");
        }
    }

    private void flush(String reason) {
        if (bufferSeconds < 0.5 || bufferKey == null) {
            resetBuffer();
            return;
        }
        store.report(bufferKey, bufferTitle, round1(bufferSeconds),
                bufferMaxPos, bufferDuration, System.currentTimeMillis());
        resetBuffer();
    }

    private void resetBuffer() {
        bufferSeconds = 0;
        bufferMaxPos = 0;
        bufferDuration = 0;
        bufferKey = null;
        bufferTitle = null;
        stall = 0;
        anchor = null;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static String md5(String s) {
        try {
            java.security.MessageDigest d = java.security.MessageDigest.getInstance("MD5");
            byte[] b = d.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    /* ============ 常驻通知 ============ */
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "学习记录",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("显示今日累计时长");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent it = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, it,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        String text = "今日 " + fmt(todaySeconds) + " · " + lastStatus;
        return b.setSmallIcon(R.drawable.ic_stat_play)
                .setContentTitle("B站学习陪伴")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification());
        } catch (Throwable ignored) {}
    }

    public static String fmt(double sec) {
        long s = Math.max(0, (long) sec);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        if (h > 0) return h + "小时" + m + "分";
        if (m > 0) return m + "分钟";
        return s + "秒";
    }

    public static void start(Context c) {
        Intent i = new Intent(c, TrackService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable e) {
            Toast.makeText(c, "启动失败，请检查后台权限", Toast.LENGTH_SHORT).show();
        }
    }
}
