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
import android.graphics.PointF;
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
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
    private GradientDrawable ringShape;
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenWidth, screenHeight, screenDensity;
    private TextRecognizer recognizer;
    private Trie trie;

    private static final String CHANNEL_ID = "OCR_Service_Channel";
    // FIX 1: Adjusted Size (720 is the Goldilocks zone)
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
                .setContentText("Tap the ring to solve")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build());

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        trie = new Trie();
        new Thread(() -> trie.loadDictionary(this)).start();

        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        // Initialize ImageReader
        setupImageReader();

        showRing();
    }

    private void setupImageReader() {
        if (imageReader != null) imageReader.close();
        // Use flag 2 (USAGE_CPU_READ_OFTEN) + maxImages 2 to prevent buffer stalling
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
    }

    private void showRing() {
        ringOverlay = new FrameLayout(this);
        
        ringShape = new GradientDrawable();
        ringShape.setShape(GradientDrawable.OVAL);
        ringShape.setColor(Color.parseColor("#3300FF00")); 
        ringShape.setStroke(10, Color.parseColor("#AA00FF00")); 
        ringOverlay.setBackground(ringShape);

        ImageView btnIcon = new ImageView(this);
        btnIcon.setImageResource(android.R.drawable.ic_media_play);
        btnIcon.setColorFilter(Color.WHITE); 
        FrameLayout.LayoutParams btnParams = new FrameLayout.LayoutParams(180, 180); 
        btnParams.gravity = Gravity.CENTER;
        ringOverlay.addView(btnIcon, btnParams);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                RING_SIZE, RING_SIZE,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = (screenWidth - RING_SIZE) / 2;
        params.y = screenHeight / 2;

        ringOverlay.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean isClick;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isClick = true; 
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int deltaX = (int) (event.getRawX() - initialTouchX);
                        int deltaY = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(deltaX) > 15 || Math.abs(deltaY) > 15) { // Increased threshold slightly
                            isClick = false;
                            params.x = initialX + deltaX;
                            params.y = initialY + deltaY;
                            wm.updateViewLayout(ringOverlay, params);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (isClick) {
                            flashRing();
                            // FIX 2: Explicit Toast so we KNOW the click worked
                            Toast.makeText(ScannerService.this, "Thinking...", Toast.LENGTH_SHORT).show();
                            captureAndSolve();
                        }
                        return true;
                }
                return false;
            }
        });

        wm.addView(ringOverlay, params);
    }

    private void flashRing() {
        ringShape.setColor(Color.parseColor("#55FF0000"));
        ringShape.setStroke(10, Color.RED);
        ringOverlay.setBackground(ringShape);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            ringShape.setColor(Color.parseColor("#3300FF00"));
            ringShape.setStroke(10, Color.parseColor("#AA00FF00"));
            ringOverlay.setBackground(ringShape);
        }, 200);
    }

    private void captureAndSolve() {
        if (mediaProjection == null) {
            Toast.makeText(this, "Camera Permission Missing! Restart App.", Toast.LENGTH_LONG).show();
            return;
        }

        // FIX 3: Force a new frame capture cleanly
        try {
            Image image = imageReader.acquireLatestImage();
            
            // If the buffer is empty, it means the screen hasn't changed or it's stuck.
            // We'll try to acquire the "Next" image instead.
            if (image == null) {
                image = imageReader.acquireNextImage();
            }

            if (image == null) {
                Toast.makeText(this, "Screen Buffer Empty - Move Ring & Retry", Toast.LENGTH_SHORT).show();
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
                    .addOnFailureListener(e -> Toast.makeText(this, "OCR Failed: " + e.getMessage(), Toast.LENGTH_LONG).show());

        } catch (Exception e) {
            Log.e(TAG, "Capture Error", e);
            // If it crashes, reset the reader for next time
            setupImageReader(); 
            Toast.makeText(this, "Capture Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "OCR saw nothing. Try moving the ring slightly.", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "OCR Saw: " + rawString.toString(), Toast.LENGTH_SHORT).show();
            solveAndSwipe(letters, rawString.toString());
        }
    }

    private void solveAndSwipe(List<DetectedLetter> boardLetters, String inputString) {
        if (SwiperService.instance == null) {
            Toast.makeText(this, "Swiper Not Ready! Check Accessibility.", Toast.LENGTH_LONG).show();
            return;
        }

        new Thread(() -> {
            List<String> words = trie.solve(inputString);
            words.removeIf(w -> w.length() < 3);
            Collections.sort(words, (a, b) -> b.length() - a.length());

            if (words.isEmpty()) {
                 new Handler(Looper.getMainLooper()).post(() -> 
                    Toast.makeText(this, "No dictionary words found for: " + inputString, Toast.LENGTH_SHORT).show());
                 return;
            }

            int swiped = 0;
            for (String word : words) {
                float[][] path = buildPath(word, boardLetters);
                if (path != null) {
                    SwiperService.instance.swipe(path);
                    swiped++;
                    try { Thread.sleep(450); } catch (Exception e) {}
                }
            }
            if (swiped == 0) {
                 new Handler(Looper.getMainLooper()).post(() -> 
                    Toast.makeText(this, "Could not trace letters on screen.", Toast.LENGTH_SHORT).show());
            }
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
            
            // Standardizing the Virtual Display setup
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
