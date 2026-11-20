package com.bilistudy.app;

import android.app.Notification;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** 监听 B 站的媒体通知，从中取出 MediaSession token 交给 SessionBridge。 */
public class BiliNotificationListener extends NotificationListenerService {
    @Override
    public void onCreate() {
        super.onCreate();
        SessionBridge.init(this);
        TrackService.start(this);
        // 启动后主动扫一遍已经存在的通知
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) for (StatusBarNotification sbn : active) handle(sbn);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        SessionBridge.init(this);
        TrackService.start(this);
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) for (StatusBarNotification sbn : active) handle(sbn);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        handle(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null) SessionBridge.clearSession(sbn.getPackageName());
    }

    private void handle(StatusBarNotification sbn) {
        if (sbn == null || !SessionBridge.isBiliPackage(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null) return;
        Bundle extras = n.extras;
        if (extras == null) return;
        Object obj = extras.get(Notification.EXTRA_MEDIA_SESSION);
        if (obj instanceof MediaSession.Token) {
            SessionBridge.setSession((MediaSession.Token) obj, sbn.getPackageName());
            TrackService.start(this);
        }
    }
}
