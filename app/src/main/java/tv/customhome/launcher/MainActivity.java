package tv.customhome.launcher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayInputStream;
import java.util.Collections;

import tv.customhome.launcher.dashboard.DashboardRepository;

public final class MainActivity extends Activity {
    private WebView web;
    private NativeBridge bridge;
    private DashboardRepository repository;
    private final Handler timer = new Handler();
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            repository.refreshIfNeeded();
            publish();
            timer.postDelayed(this, 60_000);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        repository = new DashboardRepository(this);
        bridge = new NativeBridge(this, repository);
        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        settings.setTextZoom(100);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        web.setBackgroundColor(0xFF03060D);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/app-icons/", bridge::icon)
                .build();
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                WebResourceResponse local = loader.shouldInterceptRequest(uri);
                if (local != null) return local;
                if ("https".equals(uri.getScheme()) && "api.open-meteo.com".equals(uri.getHost())
                        && "/v1/forecast".equals(uri.getPath()) && "GET".equals(request.getMethod())) return null;
                if ("https".equals(uri.getScheme()) && bridge.isCrestHost(uri.getHost())) return null;
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", Collections.emptyMap(),
                        new ByteArrayInputStream(new byte[0]));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !"https://appassets.androidplatform.net/assets/site/index.html".equals(request.getUrl().toString());
            }
            @Override public void onPageFinished(WebView view, String url) { publish(); }
        });
        web.addJavascriptInterface(bridge, "AndroidTV");
        setContentView(web);
        web.loadUrl("https://appassets.androidplatform.net/assets/site/index.html");
    }

    private void publish() {
        if (web != null) web.evaluateJavascript("window.TVPersonal && window.TVPersonal.update(" + bridge.dashboard() + ")", null);
    }

    @Override protected void onResume() {
        super.onResume();
        web.onResume();
        web.resumeTimers();
        repository.listen(this::publish);
        repository.refreshIfNeeded();
        publish();
        web.evaluateJavascript("window.TVRemote && window.TVRemote.resume()", null);
        timer.postDelayed(tick, 60_000);
    }

    @Override protected void onPause() {
        timer.removeCallbacks(tick);
        repository.listen(null);
        web.onPause();
        web.pauseTimers();
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        web.evaluateJavascript("window.TVRemote && window.TVRemote.home()", null);
    }

    @Override public boolean onKeyDown(int code, KeyEvent event) {
        int key = browserKey(code);
        if (key != 0) {
            web.evaluateJavascript("window.TVRemote && window.TVRemote.key(" + key + ")", null);
            return true;
        }
        return super.onKeyDown(code, event);
    }

    @Override public boolean onKeyUp(int code, KeyEvent event) {
        return browserKey(code) != 0 || super.onKeyUp(code, event);
    }

    private int browserKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return 37;
            case KeyEvent.KEYCODE_DPAD_UP: return 38;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 39;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 40;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER: return 13;
            case KeyEvent.KEYCODE_BACK: return 27;
            default: return 0;
        }
    }

    @Override protected void onDestroy() {
        timer.removeCallbacks(tick);
        repository.close();
        web.removeJavascriptInterface("AndroidTV");
        web.destroy();
        super.onDestroy();
    }
}
