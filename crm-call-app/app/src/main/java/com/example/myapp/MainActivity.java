package com.example.myapp;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.os.Environment;
import android.view.Window;
import android.view.WindowManager;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.HashSet;
import java.util.Set;
import android.app.Activity;
import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Base64;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import android.content.Intent;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.*;
import android.widget.FrameLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

public class MainActivity extends AppCompatActivity {
    // برای عوض کردن آدرس سایت، همین خط رو ویرایش کن
    private static final String SITE_URL = "https://example.com";
    private static final int FILE_CHOOSER_REQUEST_CODE = 200;
    private WebView webView;
    private FrameLayout fullscreenContainer;
    private ValueCallback<Uri[]> filePathCallback;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    // ---- تنظیمات سمت سرور: صفحه با تگ‌های <meta name="fastapk-..."> اعلام می‌کنه ----
    private SharedPreferences cfg;
    private final Set<String> feats = new HashSet<>();
    private int insetTop = 0, insetBottom = 0;
    private boolean on(String f) { return feats.contains(f); }
    boolean crmOn() { return on("crm"); }
    void jsCall(final String js) { runOnUiThread(() -> { if (webView != null) webView.evaluateJavascript(js, null); }); }
    private static final String READ_META_JS = "(function(){var m=function(n){var e=document.querySelector('meta[name=\"'+n+'\"]');return e?e.content:''};"
        + "return JSON.stringify({f:m('fastapk-features'),fs:m('fastapk-fullscreen'),sc:m('fastapk-status-color')||m('theme-color'),nc:m('fastapk-nav-color')||m('fastapk-status-color')||m('theme-color')})})()";
    private static final String OFFLINE_HTML = "<html dir='rtl' lang='fa'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
        + "<body style='margin:0;font-family:sans-serif;background:#FAF9F5;color:#141413;display:flex;min-height:100vh;align-items:center;justify-content:center;text-align:center'>"
        + "<div><div style='font-size:56px'>&#128225;</div><h2>اتصال به اینترنت برقرار نیست</h2><p>اتصال را بررسی کنید و دوباره تلاش کنید.</p>"
        + "<button style='background:#0A66FF;color:#fff;border:0;padding:12px 28px;font-size:16px' onclick=\"location.href='__URL__'\">تلاش دوباره</button></div></body></html>";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // >>> WINDOW_SETUP_START (این بلوک رو ورک‌فلوی گیت‌هاب بر اساس ورودی fullscreen جایگزین می‌کنه)
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        WindowInsetsControllerCompat ic = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (ic != null) { ic.setAppearanceLightStatusBars(false); ic.setAppearanceLightNavigationBars(false); }
        if (Build.VERSION.SDK_INT >= 29) { getWindow().setNavigationBarContrastEnforced(false); getWindow().setStatusBarContrastEnforced(false); }
        // >>> WINDOW_SETUP_END

        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webView);
        fullscreenContainer = findViewById(R.id.fullscreenContainer);

        cfg = getSharedPreferences("fastapk", MODE_PRIVATE);
        try { applyConfig(new JSONObject(cfg.getString("cfg", "{}"))); } catch (Exception ignored) {}
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, ins) -> {
            Insets b = ins.getInsets(WindowInsetsCompat.Type.systemBars());
            float d = getResources().getDisplayMetrics().density;
            insetTop = Math.round(b.top / d); insetBottom = Math.round(b.bottom / d);
            injectInsets();
            return ins;
        });

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // >>> ZOOM_START
        settings.setTextZoom(99);
        // >>> ZOOM_END

        // پل چاپ: window.print() داخل وب‌ویو کار نمی‌کند؛ اینجا به سرویس چاپ خود اندروید وصل می‌شود
        webView.addJavascriptInterface(new PrintBridge(), "AndroidPrint"); // فقط وقتی سرور feature=print رو اعلام کنه کار می‌کنه
        webView.addJavascriptInterface(new CrmBridge(this), "AndroidCRM"); // فقط وقتی سرور feature=crm رو اعلام کنه کار می‌کنه

        webView.setDownloadListener((url, ua, cd, mime, len) -> {
            if (!on("download")) return; // فقط وقتی سرور feature=download رو اعلام کنه
            try {
                if (!url.startsWith("http")) { Toast.makeText(this, "این نوع لینک دانلود پشتیبانی نمی‌شود", Toast.LENGTH_LONG).show(); return; }
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                r.setMimeType(mime);
                String ck = CookieManager.getInstance().getCookie(url);
                if (ck != null) r.addRequestHeader("cookie", ck);
                r.addRequestHeader("User-Agent", ua);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, cd, mime));
                ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
                Toast.makeText(this, "دانلود شروع شد", Toast.LENGTH_SHORT).show();
            } catch (Exception e) { Toast.makeText(this, "دانلود انجام نشد", Toast.LENGTH_LONG).show(); }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (url == null || !url.startsWith(SITE_URL)) return;
                view.evaluateJavascript(READ_META_JS, value -> {
                    try {
                        JSONObject j = new JSONObject(new JSONTokener(value).nextValue().toString());
                        cfg.edit().putString("cfg", j.toString()).apply();
                        applyConfig(j);
                        injectApi();
                        injectInsets();
                    } catch (Exception ignored) {}
                });
            }
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && on("offline")) {
                    String u = request.getUrl().toString().replace("'", "%27");
                    view.loadDataWithBaseURL(null, OFFLINE_HTML.replace("__URL__", u), "text/html", "utf-8", null);
                }
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith(SITE_URL)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
                return true;
            }
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> callback, FileChooserParams params) {
                filePathCallback = callback;
                try { startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST_CODE); }
                catch (Exception e) { filePathCallback = null; return false; }
                return true;
            }
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view; customViewCallback = callback;
                fullscreenContainer.addView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                fullscreenContainer.setVisibility(View.VISIBLE);
                webView.setVisibility(View.GONE);
            }
            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                fullscreenContainer.setVisibility(View.GONE);
                fullscreenContainer.removeView(customView);
                webView.setVisibility(View.VISIBLE);
                customView = null;
                if (customViewCallback != null) { customViewCallback.onCustomViewHidden(); customViewCallback = null; }
            }
        });

        webView.loadUrl(SITE_URL);
    }

    public class PrintBridge {
        @JavascriptInterface
        public void print(final String size) { if (!on("print")) return; runOnUiThread(() -> doPrint(size)); }
        @JavascriptInterface
        public String printer() { if (!on("print")) return ""; return getSharedPreferences("kv", MODE_PRIVATE).getString("bt_name", ""); }
        @JavascriptInterface
        public void pick() { if (!on("print")) return; runOnUiThread(() -> { if (!hasBt()) { pendingPick = true; askBt(); } else pickPrinter(); }); }
        @JavascriptInterface
        public void raw(final String b64) { if (!on("print")) return; runOnUiThread(() -> { if (!hasBt()) { pendingB64 = b64; askBt(); } else sendRaw(b64); }); }
    }

    // ---- چاپ مستقیم بلوتوثی (ESC/POS) ----
    private static final int BT_REQ = 300;
    private String pendingB64 = null;
    private boolean pendingPick = false;

    private boolean hasBt() {
        if (Build.VERSION.SDK_INT < 31) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }
    private void askBt() {
        if (Build.VERSION.SDK_INT >= 31)
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, BT_REQ);
    }
    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == CrmBridge.REQ) { jsCall("window.onCrmPerms&&onCrmPerms()"); return; }
        if (code != BT_REQ) return;
        boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        if (ok && pendingPick) pickPrinter();
        else if (ok && pendingB64 != null) sendRaw(pendingB64);
        else if (!ok) Toast.makeText(this, "اجازه‌ی بلوتوث داده نشد", Toast.LENGTH_LONG).show();
        pendingPick = false; pendingB64 = null;
    }
    @SuppressLint("MissingPermission")
    private void pickPrinter() {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null || !ad.isEnabled()) { Toast.makeText(this, "بلوتوث را روشن کنید", Toast.LENGTH_LONG).show(); return; }
        final List<BluetoothDevice> list = new ArrayList<>(ad.getBondedDevices());
        if (list.isEmpty()) { Toast.makeText(this, "اول پرینتر را در تنظیمات بلوتوث گوشی جفت کنید", Toast.LENGTH_LONG).show(); return; }
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) names[i] = list.get(i).getName() + "\n" + list.get(i).getAddress();
        new AlertDialog.Builder(this).setTitle("انتخاب پرینتر").setItems(names, (d, i) -> {
            getSharedPreferences("kv", MODE_PRIVATE).edit()
                .putString("bt_mac", list.get(i).getAddress()).putString("bt_name", list.get(i).getName()).apply();
            webView.evaluateJavascript("window.onPrinter&&onPrinter()", null);
        }).show();
    }
    @SuppressLint("MissingPermission")
    private void sendRaw(final String b64) {
        final String mac = getSharedPreferences("kv", MODE_PRIVATE).getString("bt_mac", "");
        new Thread(() -> {
            boolean ok = false;
            BluetoothSocket sock = null;
            try {
                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                sock = ad.getRemoteDevice(mac).createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));
                ad.cancelDiscovery();
                sock.connect();
                OutputStream o = sock.getOutputStream();
                for (int i = 0; i < data.length; i += 1024) {
                    o.write(data, i, Math.min(1024, data.length - i));
                    o.flush();
                    Thread.sleep(25);
                }
                Thread.sleep(600);
                ok = true;
            } catch (Exception e) { ok = false; }
            try { if (sock != null) sock.close(); } catch (Exception ignored) {}
            final boolean r = ok;
            runOnUiThread(() -> webView.evaluateJavascript("window.onPrintDone&&onPrintDone(" + r + ")", null));
        }).start();
    }

    private Integer parseColor(String c) {
        try { return (c == null || c.isEmpty()) ? null : Integer.valueOf(Color.parseColor(c)); } catch (Exception e) { return null; }
    }

    private void applyConfig(JSONObject j) {
        feats.clear();
        for (String x : j.optString("f", "").split(",")) if (!x.trim().isEmpty()) feats.add(x.trim().toLowerCase());
        Window w = getWindow();
        String fs = j.optString("fs", "");
        boolean full = "true".equals(fs);
        if (!fs.isEmpty()) WindowCompat.setDecorFitsSystemWindows(w, !full);
        Integer sc = parseColor(j.optString("sc", "")), nc = parseColor(j.optString("nc", ""));
        if (full) { w.setStatusBarColor(Color.TRANSPARENT); w.setNavigationBarColor(Color.TRANSPARENT); }
        else if ("false".equals(fs)) {
            if (sc != null) w.setStatusBarColor(sc.intValue());
            if (nc != null) w.setNavigationBarColor(nc.intValue());
        }
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
        if (c != null) {
            if (sc != null) c.setAppearanceLightStatusBars(ColorUtils.calculateLuminance(sc.intValue()) > 0.5);
            if (nc != null) c.setAppearanceLightNavigationBars(ColorUtils.calculateLuminance(nc.intValue()) > 0.5);
        }
        if (on("keep-awake")) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void injectApi() {
        String js = "window.FastAPK={v:2,features:" + new JSONArray(feats).toString() + "};document.documentElement.classList.add('fastapk');";
        if (on("print")) js += "window.print=function(){AndroidPrint.print(typeof S!=='undefined'&&S.ps?S.ps:'80')};";
        webView.evaluateJavascript(js, null);
    }

    private void injectInsets() {
        if (webView == null) return;
        webView.evaluateJavascript("document.documentElement.style.setProperty('--fastapk-top','" + insetTop + "px');"
            + "document.documentElement.style.setProperty('--fastapk-bottom','" + insetBottom + "px');", null);
    }

    private void doPrint(String size) {
        try {
            PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
            PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter("receipt");
            PrintAttributes.MediaSize ms;
            if ("58".equals(size)) ms = new PrintAttributes.MediaSize("kv58", "58mm", 2283, 8000);
            else if ("A4".equals(size)) ms = PrintAttributes.MediaSize.ISO_A4;
            else if ("A5".equals(size)) ms = PrintAttributes.MediaSize.ISO_A5;
            else ms = new PrintAttributes.MediaSize("kv80", "80mm", 3150, 8000);
            PrintAttributes attrs = new PrintAttributes.Builder()
                    .setMediaSize(ms)
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build();
            pm.print("receipt", adapter, attrs);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) { super.onActivityResult(requestCode, resultCode, data); return; }
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getDataString() != null) results = new Uri[]{Uri.parse(data.getDataString())};
                else if (data.getClipData() != null) {
                    int c = data.getClipData().getItemCount();
                    results = new Uri[c];
                    for (int i = 0; i < c; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        } else super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) { webView.goBack(); return true; }
        return super.onKeyDown(keyCode, event);
    }
}
