package com.wordscapes.ocr;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQUEST_OVERLAY_PERM = 1001;
    private static final int REQUEST_SCREEN_CAPTURE = 1002;
    private MediaProjectionManager projectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // KILL ZOMBIES: Stop the service if it's already running to ensure a fresh permission request
        stopService(new Intent(this, ScannerService.class));

        setContentView(createSimpleUI());
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    }

    private View createSimpleUI() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setGravity(android.view.Gravity.CENTER);
        layout.setPadding(50, 50, 50, 50);

        TextView title = new TextView(this);
        title.setText("Wordscapes OCR");
        title.setTextSize(30);
        title.setGravity(android.view.Gravity.CENTER);
        layout.addView(title);

        Button btnStart = new Button(this);
        btnStart.setText("START HELPER");
        btnStart.setTextSize(20);
        btnStart.setPadding(0, 50, 0, 50);
        btnStart.setOnClickListener(v -> startSequence());
        layout.addView(btnStart);

        return layout;
    }

    private void startSequence() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_OVERLAY_PERM);
        } else {
            requestScreenCapture();
        }
    }

    private void requestScreenCapture() {
        if (projectionManager != null) {
            // This pops up the "Start Recording" dialog
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_OVERLAY_PERM) {
            if (Settings.canDrawOverlays(this)) {
                requestScreenCapture();
            } else {
                Toast.makeText(this, "Overlay Permission Required!", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Toast.makeText(this, "Camera Permission Granted! Starting...", Toast.LENGTH_SHORT).show();
                
                Intent serviceIntent = new Intent(this, ScannerService.class);
                serviceIntent.putExtra("RESULT_CODE", resultCode);
                serviceIntent.putExtra("DATA", data);
                
                // CRITICAL: Must use startForegroundService for Android 10+
                startForegroundService(serviceIntent);
                finish(); 
            } else {
                Toast.makeText(this, "Screen Capture Denied. App cannot work.", Toast.LENGTH_LONG).show();
            }
        }
    }
}
