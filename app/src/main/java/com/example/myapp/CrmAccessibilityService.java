package com.example.myapp;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Build;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

// وقتی این سرویس روشن است، به وضعیت تماس گوش می‌دهد: شروع مکالمه ← شروع ضبط، پایان ← توقف.
// فعلاً فایل‌ها فقط داخل پوشه‌ی اپ ذخیره می‌شوند (برای آزمایش کیفیت).
public class CrmAccessibilityService extends AccessibilityService {
    public static volatile boolean running = false;
    public static volatile String regError = "";
    private TelephonyManager tm;
    private Object cb;
    private boolean registered = false;
    private int last = TelephonyManager.CALL_STATE_IDLE;

    @Override protected void onServiceConnected() { running = true; ensureRegistered(); }

    @Override public boolean onUnbind(android.content.Intent i) { unregister(); running = false; return super.onUnbind(i); }
    @Override public void onDestroy() { unregister(); running = false; super.onDestroy(); }

    // اگر مجوز بعد از روشن‌شدن سرویس داده شده باشد، با اولین رویداد دوباره ثبت می‌شود
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { if (!registered) ensureRegistered(); }
    @Override public void onInterrupt() { }

    private void onState(int s) {
        if (s == TelephonyManager.CALL_STATE_OFFHOOK && last != TelephonyManager.CALL_STATE_OFFHOOK) CallRecorder.I.start(this);
        else if (s == TelephonyManager.CALL_STATE_IDLE && last == TelephonyManager.CALL_STATE_OFFHOOK) CallRecorder.I.stop();
        last = s;
    }

    private void ensureRegistered() {
        if (registered) return;
        try {
            tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (Build.VERSION.SDK_INT >= 31) {
                CB31 c = new CB31();
                tm.registerTelephonyCallback(ContextCompat.getMainExecutor(this), c);
                cb = c;
            } else {
                PhoneStateListener l = new PhoneStateListener() {
                    @Override public void onCallStateChanged(int state, String number) { onState(state); }
                };
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE);
                cb = l;
            }
            registered = true;
            regError = "";
        } catch (Exception e) {
            regError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private void unregister() {
        try {
            if (registered && tm != null && cb != null) {
                if (Build.VERSION.SDK_INT >= 31) tm.unregisterTelephonyCallback((TelephonyCallback) cb);
                else tm.listen((PhoneStateListener) cb, PhoneStateListener.LISTEN_NONE);
            }
        } catch (Exception ignored) {}
        registered = false;
        CallRecorder.I.stop();
    }

    @RequiresApi(31)
    private class CB31 extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        @Override public void onCallStateChanged(int state) { onState(state); }
    }
}
