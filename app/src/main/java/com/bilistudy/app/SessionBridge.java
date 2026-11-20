package com.bilistudy.app;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.SystemClock;
import android.os.Bundle;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SessionBridge —— 找到 B 站的媒体会话。
 * B 站播放视频时会放出一条媒体通知，并注册 MediaSession：
 *  - 通知通道：onNotificationPosted 里拿 EXTRA_MEDIA_SESSION
 *  - 主动查询：MediaSessionManager.getActiveSessions（需要通知读取权）
 * 两条路都试，谁拿到用谁。
 */
public class SessionBridge {
    private static final String[] BILI_PACKAGES = {
            "tv.danmaku.bili",             // 主客户端
            "tv.danmaku.bilibilihd",       // HD 版
            "com.bilibili.app.in",         // 国际版
            "com.bilibili.app.in.tencent", // 应用宝渠道
            "com.bilibili",                // 部分旧版/定制版
            "tv.danmaku.bili.ui",          // 概念版等
    };

    private static Context ctx;
    private static MediaSession.Token token;
    private static String sessionPkg;

    public static void init(Context c) {
        ctx = c.getApplicationContext();
    }

    public static boolean isBiliPackage(String pkg) {
        if (pkg == null) return false;
        for (String p : BILI_PACKAGES) {
            if (p.equals(pkg) || pkg.startsWith(p + ".")) return true;
        }
        return false;
    }

    public static synchronized void setSession(MediaSession.Token t, String pkg) {
        if (!isBiliPackage(pkg)) return;
        token = t;
        sessionPkg = pkg;
    }

    public static synchronized void clearSession(String pkg) {
        if (pkg != null && pkg.equals(sessionPkg)) {
            token = null;
            sessionPkg = null;
        }
    }

    /** 主动找到当前 B 站的 MediaSession（通知里拿不到时的兜底） */
    public static synchronized void findActive() {
        if (ctx == null) return;
        try {
            MediaSessionManager msm =
                    (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return;
            ComponentName listener = new ComponentName(ctx, BiliNotificationListener.class);
            java.util.List<MediaController> list = msm.getActiveSessions(listener);
            if (list == null) return;
            for (MediaController c : list) {
                String pkg = c.getPackageName();
                if (isBiliPackage(pkg)) {
                    token = c.getSessionToken();
                    sessionPkg = pkg;
                    return;
                }
            }
        } catch (Throwable ignored) {
            // 没有通知读取权限时会抛 SecurityException，属正常
        }
    }

    public static synchronized MediaController controller() {
        if (ctx == null || token == null) return null;
        try {
            return new MediaController(ctx, token);
        } catch (Throwable e) {
            return null;
        }
    }

    public static synchronized String packageName() {
        return sessionPkg;
    }

    /** 一次采样结果 */
    public static class Snapshot {
        public boolean playing;
        public double positionSec;   // 当前播放位置（秒）
        public double durationSec;   // 总时长（秒）
        public double speed;         // 播放倍速
        public String title;
        public String pkg;
    }

    /** 从 MediaController 读一次状态；position 用 lastUpdateTime 补偿到"此刻" */
    public static Snapshot sample(MediaController c) {
        Snapshot s = new Snapshot();
        if (c == null) return s;
        s.pkg = c.getPackageName();
        try {
            PlaybackState ps = c.getPlaybackState();
            if (ps == null) return s;
            s.speed = ps.getPlaybackSpeed();
            if (s.speed <= 0) s.speed = 1;
            int st = ps.getState();
            s.playing = (st == PlaybackState.STATE_PLAYING);
            long now = SystemClock.elapsedRealtime();
            long pos = ps.getPosition();
            // PlaybackState 没有公开的 getLastUpdateTime，用 extras 里的时间戳兜底
            Bundle extras = ps.getExtras();
            long last = extras == null ? 0 : extras.getLong("android.media.session.EXTRA_LAST_UPDATE_TIME", 0);
            if (last > 0 && now > last) pos += (long) ((now - last) * s.speed);
            s.positionSec = Math.max(0, pos / 1000.0);

            if (c.getMetadata() != null) {
                s.durationSec = c.getMetadata().getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) / 1000.0;
                String t = c.getMetadata().getString(android.media.MediaMetadata.METADATA_KEY_TITLE);
                s.title = t == null ? "" : t;
            }
        } catch (Throwable ignored) {}
        return s;
    }

    /** 是否已经被用户授予通知读取权 */
    public static boolean hasNotificationAccess(Context c) {
        try {
            String flat = android.provider.Settings.Secure.getString(
                    c.getContentResolver(), "enabled_notification_listeners");
            if (flat == null) return false;
            ComponentName cn = new ComponentName(c, BiliNotificationListener.class);
            String target = cn.flattenToString();
            for (String part : flat.split(":")) {
                if (part.equals(target)) return true;
            }
            return flat.contains(c.getPackageName());
        } catch (Throwable e) {
            return false;
        }
    }
}
