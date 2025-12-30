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

        // Simple UI: Just a big "START" button
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
        // 1. Check if we can draw over other apps
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_OVERLAY_PERM);
        } else {
            // 2. If yes, ask to Record Screen
            requestScreenCapture();
        }
    }

    private void requestScreenCapture() {
        if (projectionManager != null) {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_OVERLAY_PERM) {
            if (Settings.canDrawOverlays(this)) {
                requestScreenCapture();
            } else {
                Toast.makeText(this, "Overlay Permission is required!", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK) {
                // 3. Success! Start the Service and pass the permission token
                Intent serviceIntent = new Intent(this, ScannerService.class);
                serviceIntent.putExtra("RESULT_CODE", resultCode);
                serviceIntent.putExtra("DATA", data);
                startForegroundService(serviceIntent);
                finish(); // Close this screen
            } else {
                Toast.makeText(this, "Screen Capture denied. Cannot scan.", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
