package com.example.myapp;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

// مرحله‌ی ۱: فقط وجود دارد تا کاربر بتواند فعالش کند و وضعیتش از وب قابل‌چک باشد.
// منطق ضبط تماس در مرحله‌ی بعد، بعد از تست روی گوشی واقعی، اینجا اضافه می‌شود.
public class CrmAccessibilityService extends AccessibilityService {
    public static volatile boolean running = false;

    @Override protected void onServiceConnected() { running = true; }
    @Override public boolean onUnbind(android.content.Intent i) { running = false; return super.onUnbind(i); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }
}
