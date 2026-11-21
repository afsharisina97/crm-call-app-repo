package com.example.myapp;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CallLog;
import androidx.core.content.ContextCompat;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// ضبط‌ها را همراه شماره/نوع/مدت از لاگ تماس به سرور (api.php?a=rec) می‌فرستد.
// فایل‌هایی که ارسالشان نشد (اینترنت/توکن) می‌مانند و دفعه‌ی بعد دوباره امتحان می‌شوند.
public class RecUploader {
    private static final ExecutorService EX = Executors.newSingleThreadExecutor();
    public static volatile String lastError = "";

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("crm_rec", 0); }
    public static void saveServer(Context c, String api, String tok) { prefs(c).edit().putString("api", api).putString("tok", tok).apply(); }
    public static boolean hasServer(Context c) { return !prefs(c).getString("api", "").isEmpty() && !prefs(c).getString("tok", "").isEmpty(); }
    public static int pending(Context c) {
        File[] a = CallRecorder.dir(c).listFiles((d, n) -> n.endsWith(".json"));
        return a == null ? 0 : a.length;
    }

    public static void enqueue(final Context c, final File f, final long start, final long end, final boolean manual, final int peak, final String src) {
        EX.execute(() -> {
            try {
                JSONObject m = new JSONObject().put("start", start).put("end", end).put("manual", manual).put("peak", peak).put("src", src);
                writeAll(new File(f.getPath() + ".json"), m.toString());
                if (!manual) Thread.sleep(4000);   // تا لاگ تماس نوشته شود
            } catch (Exception e) { lastError = "meta: " + e; return; }
            flushNow(c);
        });
    }
    public static void flush(final Context c) { EX.execute(() -> flushNow(c)); }

    private static void flushNow(Context c) {
        SharedPreferences p = prefs(c);
        String api = p.getString("api", ""), tok = p.getString("tok", "");
        File[] metas = CallRecorder.dir(c).listFiles((d, n) -> n.endsWith(".json"));
        if (metas == null || metas.length == 0) return;
        if (api.isEmpty() || tok.isEmpty()) { lastError = "no_server"; return; }
        for (File mf : metas) {
            File audio = new File(mf.getPath().substring(0, mf.getPath().length() - 5));
            try {
                if (!audio.isFile()) { mf.delete(); continue; }
                JSONObject m = new JSONObject(readAll(mf));
                if (!m.optBoolean("manual") && !m.has("ph")) { fillFromCallLog(c, m); writeAll(mf, m.toString()); }
                int code = upload(api, tok, audio, m);
                if (code == 200) { audio.delete(); mf.delete(); lastError = ""; }
                else if (code == 400 || code == 413) { audio.delete(); mf.delete(); lastError = "rejected " + code; }
                else { lastError = "http " + code; if (code == 401) break; }
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                break;   // احتمالاً اینترنت نیست؛ بعداً دوباره
            }
        }
    }

    // شماره/نوع/مدت را از لاگ تماس نزدیک‌ترین رکورد به زمان شروع ضبط برمی‌دارد
    private static void fillFromCallLog(Context c, JSONObject m) throws Exception {
        m.put("ph", "");
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return;
        long st = m.optLong("start"), en = m.optLong("end");
        try (Cursor q = c.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE},
                CallLog.Calls.DATE + " BETWEEN ? AND ?", new String[]{String.valueOf(st - 120000), String.valueOf(en + 120000)},
                CallLog.Calls.DATE + " DESC")) {
            long best = Long.MAX_VALUE;
            while (q != null && q.moveToNext()) {
                long d = Math.abs(q.getLong(3) - st);
                if (d < best) {
                    best = d;
                    m.put("ph", q.getString(0) == null ? "" : q.getString(0)).put("type", q.getInt(1)).put("dur", q.getInt(2)).put("start", q.getLong(3));
                }
            }
        }
    }

    private static int upload(String api, String tok, File f, JSONObject meta) throws Exception {
        String b = "----crm" + System.nanoTime();
        byte[] head = ("--" + b + "\r\nContent-Disposition: form-data; name=\"meta\"\r\nContent-Type: application/json; charset=utf-8\r\n\r\n"
                + meta.toString() + "\r\n--" + b + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + f.getName()
                + "\"\r\nContent-Type: audio/mp4\r\n\r\n").getBytes("UTF-8");
        byte[] tail = ("\r\n--" + b + "--\r\n").getBytes("UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(api + "?a=rec").openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("X-Token", tok);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + b);
            c.setFixedLengthStreamingMode(head.length + f.length() + tail.length);
            try (OutputStream o = c.getOutputStream(); InputStream in = new FileInputStream(f)) {
                o.write(head);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                o.write(tail);
            }
            int code = c.getResponseCode();
            try (InputStream r = code >= 400 ? c.getErrorStream() : c.getInputStream()) {
                if (r != null) { byte[] x = new byte[2048]; while (r.read(x) > 0) { } }
            }
            return code;
        } finally { c.disconnect(); }
    }

    private static String readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        }
    }
    private static void writeAll(File f, String s) throws Exception {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes("UTF-8")); }
    }
}
