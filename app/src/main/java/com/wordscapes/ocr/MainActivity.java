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
        
        // Ensure clean slate
        stopService(new Intent(this, ScannerService.class));
        ScannerService.permissionIntent = null;
        ScannerService.permissionResultCode = 0;

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
                
                // --- THE FIX: DIRECT INJECTION ---
                ScannerService.permissionResultCode = resultCode;
                ScannerService.permissionIntent = data;
                // ---------------------------------

                Toast.makeText(this, "Permission Captured!", Toast.LENGTH_SHORT).show();
                
                Intent serviceIntent = new Intent(this, ScannerService.class);
                startForegroundService(serviceIntent);
                finish(); 
            } else {
                Toast.makeText(this, "Denied.", Toast.LENGTH_LONG).show();
            }
        }
    }
}
