package com.example.myapp;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.CallLog;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

// پل window.AndroidCRM — فقط وقتی صفحه feature=crm را اعلام کرده باشد کار می‌کند.
// همه‌ی خروجی‌ها رشته‌ی JSON هستند.
public class CrmBridge {
    public static final int REQ = 400;
    private static final String[] PERMS = {
        Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECORD_AUDIO
    };
    private static final String[] KEYS = {"call_log", "phone_state", "read_sms", "send_sms", "record_audio"};
    private final MainActivity act;

    CrmBridge(MainActivity a) { act = a; }

    private boolean has(String p) { return ContextCompat.checkSelfPermission(act, p) == PackageManager.PERMISSION_GRANTED; }
    private static String err(String e) { return "{\"error\":\"" + e + "\"}"; }

    static boolean a11yOn(Context c) {
        if (CrmAccessibilityService.running) return true;
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) return false;
            ComponentName cn = new ComponentName(c, CrmAccessibilityService.class);
            for (String x : s.split(":"))
                if (x.equalsIgnoreCase(cn.flattenToString()) || x.equalsIgnoreCase(cn.flattenToShortString())) return true;
        } catch (Exception ignored) {}
        return false;
    }

    @JavascriptInterface
    public String ping() {
        if (!act.crmOn()) return err("feature_off");
        try {
            return new JSONObject().put("ok", true).put("v", 1).put("sdk", Build.VERSION.SDK_INT)
                .put("brand", Build.BRAND).put("model", Build.MODEL).put("pkg", act.getPackageName()).toString();
        } catch (Exception e) { return err("json"); }
    }

    @JavascriptInterface
    public String status() {
        if (!act.crmOn()) return err("feature_off");
        try {
            JSONObject p = new JSONObject();
            for (int i = 0; i < PERMS.length; i++) p.put(KEYS[i], has(PERMS[i]));
            return new JSONObject().put("perms", p).put("a11y", a11yOn(act)).put("sdk", Build.VERSION.SDK_INT).toString();
        } catch (Exception e) { return err("json"); }
    }

    @JavascriptInterface
    public void askPermissions() {
        if (!act.crmOn()) return;
        act.runOnUiThread(() -> {
            List<String> miss = new ArrayList<>();
            for (String p : PERMS) if (!has(p)) miss.add(p);
            if (miss.isEmpty()) act.jsCall("window.onCrmPerms&&onCrmPerms()");
            else ActivityCompat.requestPermissions(act, miss.toArray(new String[0]), REQ);
        });
    }

    @JavascriptInterface
    public void openAccessibility() {
        if (!act.crmOn()) return;
        act.runOnUiThread(() -> { try { act.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) {} });
    }

    // اندروید ۱۳+: برای APK نصب‌شده از بیرون Play، در همین صفحه از منوی ⋮ «Allow restricted settings» را بزنید
    @JavascriptInterface
    public void openAppInfo() {
        if (!act.crmOn()) return;
        act.runOnUiThread(() -> { try {
            act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + act.getPackageName())));
        } catch (Exception ignored) {} });
    }

    @JavascriptInterface
    public String lastCalls(int n) {
        if (!act.crmOn()) return err("feature_off");
        if (!has(Manifest.permission.READ_CALL_LOG)) return err("no_permission");
        JSONArray out = new JSONArray();
        try (Cursor c = act.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION},
                null, null, CallLog.Calls.DATE + " DESC")) {
            int max = Math.max(1, Math.min(n, 50));
            while (c != null && c.moveToNext() && out.length() < max) {
                out.put(new JSONObject().put("number", c.getString(0)).put("type", c.getInt(1))
                    .put("date", c.getLong(2)).put("duration", c.getInt(3)));
            }
        } catch (Exception e) { return err("query"); }
        return out.toString();
    }
}
