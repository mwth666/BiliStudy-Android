package com.bilistudy.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int REQ_NOTIF = 43;

    private StatsStore store;
    private TextView status, todayVal, weekVal, monthVal, streakTop, courses;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        SessionBridge.init(this);
        store = new StatsStore(this);

        status = (TextView) findViewById(R.id.status);
        todayVal = (TextView) findViewById(R.id.todayVal);
        weekVal = (TextView) findViewById(R.id.weekVal);
        monthVal = (TextView) findViewById(R.id.monthVal);
        streakTop = (TextView) findViewById(R.id.streakTop);
        courses = (TextView) findViewById(R.id.courses);

        findViewById(R.id.btnNotif).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openNotificationAccess(); }
        });
        findViewById(R.id.btnStats).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(MainActivity.this, StatsActivity.class)); }
        });
        findViewById(R.id.btnClear).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmClear(); }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }
        TrackService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        boolean access = SessionBridge.hasNotificationAccess(this);
        status.setText(access
                ? "✅ 通知读取已开启 · 服务正在后台运行"
                : "⚠️ 尚未开启通知读取权，无法记录；请点下面按钮开启");

        todayVal.setText(TrackService.fmt(store.periodSeconds("day")));
        weekVal.setText(TrackService.fmt(store.periodSeconds("week")));
        monthVal.setText(TrackService.fmt(store.periodSeconds("month")));
        JSONObject st = store.streak();
        streakTop.setText("🔥 " + (st == null ? 0 : st.optInt("current", 0)) + " 天");

        try {
            JSONObject cs = store.courseProgress();
            java.util.Iterator<String> it = cs.keys();
            StringBuilder sb = new StringBuilder();
            while (it.hasNext()) {
                String title = it.next();
                JSONObject c = cs.getJSONObject(title);
                sb.append("· ").append(title).append("  ")
                        .append(c.optInt("done", 0)).append("/").append(c.optInt("total", 0)).append(" 节\n");
            }
            courses.setText(sb.length() == 0 ? "还没有观看记录" : sb.toString().trim());
        } catch (Exception e) {
            courses.setText("还没有观看记录");
        }
    }

    private void openNotificationAccess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } else {
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            }
        } catch (Exception e) {
            Toast.makeText(this, "找不到设置页，请在系统设置里搜索「通知使用权」", Toast.LENGTH_LONG).show();
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清除全部数据")
                .setMessage("确定删除手机上的全部观看记录吗？此操作不可恢复。")
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        store.clearAll();
                        render();
                        Toast.makeText(MainActivity.this, "已清除", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
