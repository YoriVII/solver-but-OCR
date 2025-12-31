package com.wordscapes.ocr;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ScannerService extends Service {

    private WindowManager wm;
    private FrameLayout ringOverlay;
    private TextView statusText; // New Debug Text
    private GradientDrawable ringShape;
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Vibrator vibrator;
    private int screenWidth, screenHeight, screenDensity;
    private TextRecognizer recognizer;
    private Trie trie;

    private static final String CHANNEL_ID = "OCR_Service_Channel";
    private static final int RING_SIZE = 720; 
    private static final String TAG = "OCR_DEBUG";

    private static class DetectedLetter {
        String text;
        Rect box;
        float centerX, centerY;
        boolean used = false;

        DetectedLetter(String text, Rect box) {
            this.text = text;
            this.box = box;
            this.centerX = box.exactCenterX();
            this.centerY = box.exactCenterY();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(1, new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Wordscapes Solver")
                .setContentText("Tap ring to solve")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build());

        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            trie = new Trie();
            new Thread(() -> trie.loadDictionary(this)).start();

            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
            screenDensity = metrics.densityDpi;

            setupImageReader();
            showRing();
        } catch (Exception e) {
            Log.e(TAG, "Startup Error", e);
        }
    }

    private void setupImageReader() {
        if (imageReader != null) imageReader.close();
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
    }

    private void showRing() {
        ringOverlay = new FrameLayout(this);
        
        // 1. Draw Ring
        ringShape = new GradientDrawable();
        ringShape.setShape(GradientDrawable.OVAL);
        ringShape.setColor(Color.parseColor("#3300FF00")); 
        ringShape.setStroke(10, Color.parseColor("#AA00FF00")); 
        ringOverlay.setBackground(ringShape);

        // 2. Add Status Text (Instead of Play Button)
        statusText = new TextView(this);
        statusText.setText("READY");
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(20);
        statusText.setTypeface(null, android.graphics.Typeface.BOLD);
        statusText.setGravity(Gravity.CENTER);
        
        FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, 
                FrameLayout.LayoutParams.WRAP_CONTENT);
        textParams.gravity = Gravity.CENTER;
        ringOverlay.addView(statusText, textParams);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                RING_SIZE, RING_SIZE,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = (screenWidth - RING_SIZE) / 2;
        params.y = screenHeight / 2;

        // 3. Ultra-Simple Touch Listener
        ringOverlay.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean isClick;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                try {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            initialX = params.x;
                            initialY = params.y;
                            initialTouchX = event.getRawX();
                            initialTouchY = event.getRawY();
                            isClick = true; 
                            
                            // VISUAL FEEDBACK: Change Text
                            statusText.setText("DOWN");
                            ringShape.setColor(Color.parseColor("#5500FF00")); // Darker Green
                            ringOverlay.setBackground(ringShape);
                            return true;

                        case MotionEvent.ACTION_MOVE:
                            int deltaX = (int) (event.getRawX() - initialTouchX);
                            int deltaY = (int) (event.getRawY() - initialTouchY);
                            
                            if (Math.abs(deltaX) > 50 || Math.abs(deltaY) > 50) { 
                                isClick = false;
                                statusText.setText("MOVING");
                                params.x = initialX + deltaX;
                                params.y = initialY + deltaY;
                                wm.updateViewLayout(ringOverlay, params);
                            }
                            return true;

                        case MotionEvent.ACTION_UP:
                            // Reset Color
                            ringShape.setColor(Color.parseColor("#3300FF00"));
                            ringOverlay.setBackground(ringShape);

                            if (isClick) {
                                statusText.setText("SCANNING...");
                                performVibration();
                                captureAndSolve();
                            } else {
                                statusText.setText("READY");
                            }
                            return true;
                    }
                } catch (Exception e) {
                    statusText.setText("ERROR");
                }
                return false;
            }
        });

        wm.addView(ringOverlay, params);
    }

    private void performVibration() {
        try {
            if (vibrator != null) {
                vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Exception e) {}
    }

    private void captureAndSolve() {
        if (mediaProjection == null) {
            statusText.setText("NO PERM");
            return;
        }

        try {
            Image image = imageReader.acquireLatestImage();
            if (image == null) image = imageReader.acquireNextImage();

            if (image == null) {
                statusText.setText("NO IMAGE");
                // Retry once
                new Handler(Looper.getMainLooper()).postDelayed(this::captureAndSolve, 100);
                return;
            }

            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;
            
            Bitmap bitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            image.close();

            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) ringOverlay.getLayoutParams();
            int cropX = Math.max(0, lp.x);
            int cropY = Math.max(0, lp.y);
            int cropW = Math.min(screenWidth - cropX, RING_SIZE);
            int cropH = Math.min(screenHeight - cropY, RING_SIZE);
            
            Bitmap cropped = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH);

            InputImage inputImage = InputImage.fromBitmap(cropped, 0);
            recognizer.process(inputImage)
                    .addOnSuccessListener(visionText -> processTextResults(visionText, cropX, cropY))
                    .addOnFailureListener(e -> statusText.setText("OCR FAIL"));

        } catch (Exception e) {
            statusText.setText("CAP FAIL");
            setupImageReader(); 
        }
    }

    private void processTextResults(Text visionText, int offsetX, int offsetY) {
        List<DetectedLetter> letters = new ArrayList<>();
        StringBuilder rawString = new StringBuilder();

        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                for (Text.Element element : line.getElements()) {
                    String txt = element.getText().toUpperCase();
                    if (txt.matches("[A-Z]")) {
                        Rect box = element.getBoundingBox();
                        if (box != null) {
                            box.offset(offsetX, offsetY);
                            letters.add(new DetectedLetter(txt, box));
                            rawString.append(txt);
                        }
                    }
                }
            }
        }

        if (letters.isEmpty()) {
            statusText.setText("NO TEXT");
            // Retry visual feedback
            new Handler(Looper.getMainLooper()).postDelayed(() -> statusText.setText("READY"), 2000);
        } else {
            statusText.setText("FOUND: " + rawString.toString());
            solveAndSwipe(letters, rawString.toString());
        }
    }

    private void solveAndSwipe(List<DetectedLetter> boardLetters, String inputString) {
        if (SwiperService.instance == null) {
            statusText.setText("NO HAND"); // Accessibility service is off
            return;
        }

        new Thread(() -> {
            List<String> words = trie.solve(inputString);
            words.removeIf(w -> w.length() < 3);
            Collections.sort(words, (a, b) -> b.length() - a.length());

            if (words.isEmpty()) {
                 new Handler(Looper.getMainLooper()).post(() -> statusText.setText("NO WORDS"));
                 return;
            }

            new Handler(Looper.getMainLooper()).post(() -> statusText.setText("SWIPING " + words.size()));

            for (String word : words) {
                float[][] path = buildPath(word, boardLetters);
                if (path != null) {
                    SwiperService.instance.swipe(path);
                    try { Thread.sleep(450); } catch (Exception e) {}
                }
            }
            new Handler(Looper.getMainLooper()).postDelayed(() -> statusText.setText("READY"), 2000);
        }).start();
    }

    private float[][] buildPath(String word, List<DetectedLetter> board) {
        float[][] path = new float[word.length()][2];
        for (DetectedLetter l : board) l.used = false;

        for (int i = 0; i < word.length(); i++) {
            String charStr = String.valueOf(word.charAt(i));
            DetectedLetter found = null;
            for (DetectedLetter l : board) {
                if (l.text.equals(charStr) && !l.used) {
                    found = l;
                    break;
                }
            }
            if (found == null) return null;
            found.used = true;
            path[i][0] = found.centerX;
            path[i][1] = found.centerY;
        }
        return path;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int resultCode = intent.getIntExtra("RESULT_CODE", -1);
        Intent data = intent.getParcelableExtra("DATA");

        if (resultCode != -1 && data != null) {
            mediaProjection = projectionManager.getMediaProjection(resultCode, data);
            virtualDisplay = mediaProjection.createVirtualDisplay("ScreenCapture",
                    screenWidth, screenHeight, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, null);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
    
    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID, "OCR Service", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(serviceChannel);
        }
    }
}
