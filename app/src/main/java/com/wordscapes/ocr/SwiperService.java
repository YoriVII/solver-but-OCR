package com.wordscapes.ocr;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.content.Intent;
import android.widget.Toast;

public class SwiperService extends AccessibilityService {

    public static SwiperService instance;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Toast.makeText(this, "Swiper Ready", Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
        // We don't need to listen to events, we just need to perform actions
    }

    @Override
    public void onInterrupt() {
        instance = null;
    }

    public void swipe(float[][] points) {
        if (points == null || points.length < 2) return;

        Path path = new Path();
        path.moveTo(points[0][0], points[0][1]);

        for (int i = 1; i < points.length; i++) {
            path.lineTo(points[i][0], points[i][1]);
        }

        GestureDescription.Builder builder = new GestureDescription.Builder();
        // Determine duration based on word length (longer words = slower swipe)
        long duration = Math.max(200, points.length * 100);
        
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        dispatchGesture(builder.build(), null, null);
    }
}
