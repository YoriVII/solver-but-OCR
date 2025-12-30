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
import android.view.GestureDetector;
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
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenWidth, screenHeight, screenDensity;
    private TextRecognizer recognizer;
    private Trie trie;
    private GestureDetector gestureDetector;

    private static final String CHANNEL_ID = "OCR_Service_Channel";
    private static final int RING_SIZE = 600; 
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

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);

        showRing();
    }

    private void showRing() {
        ringOverlay = new FrameLayout(this);
        
        // 1. Programmatic Circle (No XML needed)
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(Color.parseColor("#4400FF00")); // Semi-transparent Green
        shape.setStroke(8, Color.parseColor("#AA00FF00")); // Green Border
        ringOverlay.setBackground(shape);

        // 2. Play Icon in Center
        ImageView btnIcon = new ImageView(this);
        btnIcon.setImageResource(android.R.drawable.ic_media_play);
        btnIcon.setColorFilter(Color.WHITE); 
        FrameLayout.LayoutParams btnParams = new FrameLayout.LayoutParams(150, 150);
        btnParams.gravity = Gravity.CENTER;
        ringOverlay.addView(btnIcon, btnParams);

        // 3. Window Params
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                RING_SIZE, RING_SIZE,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = (screenWidth - RING_SIZE) / 2;
        params.y = screenHeight / 2;

        // 4. Smart Touch Logic (Tap vs Drag)
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                // This fires when you just TAP (Scan)
                Toast.makeText(ScannerService.this, "Tap Detected! Scanning...", Toast.LENGTH_SHORT).show();
                captureAndSolve();
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                // This fires when you DRAG (Move)
                params.x -= distanceX;
                params.y -= distanceY;
                wm.updateViewLayout(ringOverlay, params);
                return true;
            }
        });

        // Apply the listener to the whole ring
        ringOverlay.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));

        wm.addView(ringOverlay, params);
    }

    private void captureAndSolve() {
        if (mediaProjection == null) {
            Toast.makeText(this, "Permission missing! Restart app.", Toast.LENGTH_SHORT).show();
            return;
        }

        Image image = imageReader.acquireLatestImage();
        if (image == null) {
            // Retry briefly if buffer is empty
            new Handler(Looper.getMainLooper()).postDelayed(this::captureAndSolve, 50);
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

        // Crop to Ring
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) ringOverlay.getLayoutParams();
        int cropX = Math.max(0, lp.x);
        int cropY = Math.max(0, lp.y);
        int cropW = Math.min(screenWidth - cropX, RING_SIZE);
        int cropH = Math.min(screenHeight - cropY, RING_SIZE);
        
        Bitmap cropped = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH);

        InputImage inputImage = InputImage.fromBitmap(cropped, 0);
        recognizer.process(inputImage)
                .addOnSuccessListener(visionText -> processTextResults(visionText, cropX, cropY))
                .addOnFailureListener(e -> Toast.makeText(this, "OCR Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }

    private void processTextResults(Text visionText, int offsetX, int offsetY) {
        List<DetectedLetter> letters = new ArrayList<>();
        StringBuilder rawString = new StringBuilder();

        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                for (Text.Element element : line.getElements()) {
                    String txt = element.getText().toUpperCase();
                    // Basic cleanup: Only accept single letters
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
            Toast.makeText(this, "OCR saw nothing. Center the ring!", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "OCR Saw: " + rawString.toString(), Toast.LENGTH_SHORT).show();
            solveAndSwipe(letters, rawString.toString());
        }
    }

    private void solveAndSwipe(List<DetectedLetter> boardLetters, String inputString) {
        if (SwiperService.instance == null) {
            Toast.makeText(this, "Swiper not ready! Check Accessibility Settings.", Toast.LENGTH_LONG).show();
            return;
        }

        new Thread(() -> {
            List<String> words = trie.solve(inputString);
            words.removeIf(w -> w.length() < 3);
            Collections.sort(words, (a, b) -> b.length() - a.length());

            if (words.isEmpty()) {
                 new Handler(Looper.getMainLooper()).post(() -> 
                    Toast.makeText(this, "No words found in dictionary!", Toast.LENGTH_SHORT).show());
                 return;
            }

            for (String word : words) {
                float[][] path = buildPath(word, boardLetters);
                if (path != null) {
                    SwiperService.instance.swipe(path);
                    try { Thread.sleep(450); } catch (Exception e) {}
                }
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
