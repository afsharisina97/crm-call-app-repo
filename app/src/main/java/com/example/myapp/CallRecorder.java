package com.example.myapp;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// ضبط‌کننده‌ی آزمایشی: با MediaRecorder در سرویس دسترسی‌پذیری. منبع صدا از تنظیمات قابل‌تغییر است
// تا بدون بیلد جدید چند حالت را روی گوشی امتحان کنیم.
public class CallRecorder {
    public static final CallRecorder I = new CallRecorder();
    private static final String PREF = "crm_rec";
    public static volatile boolean recording = false;
    public static volatile String lastError = "";
    public static volatile int peak = 0;          // بلندترین دامنه‌ی صدا؛ ۰ یعنی ضبط بی‌صدا بوده
    public static volatile long startedAt = 0;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            synchronized (CallRecorder.this) {
                if (!recording || mr == null) return;
                try { int a = mr.getMaxAmplitude(); if (a > peak) peak = a; } catch (Exception ignored) {}
            }
            h.postDelayed(this, 500);
        }
    };
    private MediaRecorder mr;
    private File cur;

    static int sourceOf(String n) {
        switch (n) {
            case "mic": return MediaRecorder.AudioSource.MIC;
            case "voice_communication": return MediaRecorder.AudioSource.VOICE_COMMUNICATION;
            case "unprocessed": return MediaRecorder.AudioSource.UNPROCESSED;
            case "camcorder": return MediaRecorder.AudioSource.CAMCORDER;
            default: return MediaRecorder.AudioSource.VOICE_RECOGNITION;
        }
    }
    public static String getSource(Context c) { return c.getSharedPreferences(PREF, 0).getString("src", "voice_recognition"); }
    public static void setSource(Context c, String s) { c.getSharedPreferences(PREF, 0).edit().putString("src", s).apply(); }
    public static File dir(Context c) {
        File d = new File(c.getExternalFilesDir(null), "recordings");
        d.mkdirs();
        return d;
    }

    public synchronized void start(Context c) {
        if (recording) return;
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            lastError = "no_record_audio_permission";
            return;
        }
        try {
            String src = getSource(c);
            String name = "rec_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + "_" + src + ".m4a";
            cur = new File(dir(c), name);
            MediaRecorder r = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(c) : new MediaRecorder();
            r.setAudioSource(sourceOf(src));
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioChannels(1);
            r.setAudioEncodingBitRate(64000);
            r.setAudioSamplingRate(44100);
            r.setOutputFile(cur.getAbsolutePath());
            r.prepare();
            r.start();
            mr = r;
            recording = true;
            lastError = "";
            peak = 0;
            startedAt = System.currentTimeMillis();
            h.postDelayed(poll, 500);
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            release();
        }
    }

    public synchronized File stop() {
        if (!recording) return null;
        File f = cur;
        boolean ok = true;
        try { mr.stop(); } catch (RuntimeException e) { lastError = "stop: " + e.getMessage(); ok = false; }
        release();
        if (!ok) { if (f != null) f.delete(); return null; }   // ضبط خیلی کوتاه/خراب
        return f;
    }

    private void release() {
        try { if (mr != null) mr.release(); } catch (Exception ignored) {}
        mr = null;
        recording = false;
    }
}
