package com.wordscapes.ocr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

public class ScannerService extends Service {

    private WindowManager wm;
    private FrameLayout ringOverlay;
    private static final String CHANNEL_ID = "OCR_Service_Channel";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        // Start as Foreground Service (Required for Screen Capture)
        startForeground(1, new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Wordscapes OCR")
                .setContentText("Ready to scan...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build());
        
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        showRing();
    }

    private void showRing() {
        // This is the GREEN RING
        ringOverlay = new FrameLayout(this);
        
        // Draw the ring shape (using a simple view with background for now)
        View circle = new View(this);
        circle.setBackgroundResource(android.R.drawable.radiobutton_off_background); // Placeholder circle
        circle.setScaleX(5f); // Make it BIG
        circle.setScaleY(5f);
        circle.setAlpha(0.5f); // Semi-transparent
        
        FrameLayout.LayoutParams circleParams = new FrameLayout.LayoutParams(100, 100);
        circleParams.gravity = Gravity.CENTER;
        ringOverlay.addView(circle, circleParams);
        
        // Add a "SCAN" button in the middle
        ImageView btnScan = new ImageView(this);
        btnScan.setImageResource(android.R.drawable.ic_media_play);
        btnScan.setBackgroundColor(Color.parseColor("#88000000")); // Dark background
        FrameLayout.LayoutParams btnParams = new FrameLayout.LayoutParams(80, 80);
        btnParams.gravity = Gravity.CENTER;
        ringOverlay.addView(btnScan, btnParams);

        // Click to Scan (Placeholder)
        btnScan.setOnClickListener(v -> {
            Toast.makeText(this, "Scanning...", Toast.LENGTH_SHORT).show();
            // TODO: Call ML Kit here
        });

        // Window Parameters
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 200;
        params.y = 500;

        // Drag Logic
        ringOverlay.setOnTouchListener(new View.OnTouchListener() {
            int lastX, lastY, initialX, initialY;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = (int) event.getRawX();
                        lastY = (int) event.getRawY();
                        initialX = params.x;
                        initialY = params.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + ((int) event.getRawX() - lastX);
                        params.y = initialY + ((int) event.getRawY() - lastY);
                        wm.updateViewLayout(ringOverlay, params);
                        return true;
                }
                return false;
            }
        });

        wm.addView(ringOverlay, params);
    }

    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID, "OCR Service", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(serviceChannel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Retrieve the Screen Capture Permission we passed from MainActivity
        int resultCode = intent.getIntExtra("RESULT_CODE", -1);
        Intent data = intent.getParcelableExtra("DATA");
        
        if (resultCode != -1 && data != null) {
            // TODO: Initialize MediaProjection here
            Toast.makeText(this, "Permission Received! Ready to see.", Toast.LENGTH_LONG).show();
        }
        return START_NOT_STICKY;
    }
}
