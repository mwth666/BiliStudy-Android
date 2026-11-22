package com.bilistudy.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** 完整统计页：WebView 加载本地 HTML，复用浏览器插件的统计渲染逻辑 */
public class StatsActivity extends Activity {
    private WebView web;
    private StatsStore store;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("学习统计");
        store = new StatsStore(this);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setTextZoom(100);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        web.addJavascriptInterface(new JsBridge(), "BiliBridge");
        web.setWebViewClient(new WebViewClient());
        web.loadUrl("file:///android_asset/stats.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.reload();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    public class JsBridge {
        @JavascriptInterface
        public String getData() {
            return store.snapshotJson();
        }

        @JavascriptInterface
        public void clearAll() {
            store.clearAll();
        }
    }
}
