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
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenWidth, screenHeight, screenDensity;
    private TextRecognizer recognizer;
    private Trie trie;

    private static final String CHANNEL_ID = "OCR_Service_Channel";
    private static final int RING_SIZE = 600; 
    private static final String TAG = "OCR_DEBUG"; // Debug tag for logging

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
                .setContentText("Tap the play button to solve")
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
        // Use the new circular drawable
        ringOverlay.setBackgroundResource(R.drawable.scanner_ring);

        ImageView btnScan = new ImageView(this);
        btnScan.setImageResource(android.R.drawable.ic_media_play);
        btnScan.setBackgroundColor(Color.parseColor("#AA000000"));
        btnScan.setPadding(20, 20, 20, 20);
        FrameLayout.LayoutParams btnParams = new FrameLayout.LayoutParams(150, 150);
        btnParams.gravity = Gravity.CENTER;
        ringOverlay.addView(btnScan, btnParams);

        btnScan.setOnClickListener(v -> captureAndSolve());

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                RING_SIZE, RING_SIZE,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = (screenWidth - RING_SIZE) / 2;
        params.y = screenHeight / 2;

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

    private void captureAndSolve() {
        if (mediaProjection == null) {
            Toast.makeText(this, "Permission missing!", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Scanning...", Toast.LENGTH_SHORT).show();

        Image image = imageReader.acquireLatestImage();
        if (image == null) {
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
                .addOnFailureListener(e -> {
                    Log.e(TAG, "OCR Failed", e);
                    Toast.makeText(this, "OCR Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }

    private void processTextResults(Text visionText, int offsetX, int offsetY) {
        List<DetectedLetter> letters = new ArrayList<>();
        StringBuilder rawString = new StringBuilder();

        String fullText = visionText.getText();
        Log.d(TAG, "OCR Raw Output: " + fullText); // Debug log

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
                            Log.d(TAG, "Found letter: " + txt + " at " + box.toString());
                        }
                    }
                }
            }
        }

        if (letters.isEmpty()) {
            Toast.makeText(this, "OCR saw nothing! Try moving the ring.", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "OCR Saw: " + rawString.toString(), Toast.LENGTH_LONG).show();
            solveAndSwipe(letters, rawString.toString());
        }
    }

    private void solveAndSwipe(List<DetectedLetter> boardLetters, String inputString) {
        if (SwiperService.instance == null) {
            Toast.makeText(this, "Swiper Service not ready!", Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            List<String> words = trie.solve(inputString);
            Log.d(TAG, "Dictionary found " + words.size() + " words for input: " + inputString);
            
            words.removeIf(w -> w.length() < 3);
            Collections.sort(words, (a, b) -> b.length() - a.length());

            int swipedCount = 0;
            for (String word : words) {
                float[][] path = buildPath(word, boardLetters);
                if (path != null) {
                    Log.d(TAG, "Swiping word: " + word);
                    SwiperService.instance.swipe(path);
                    swipedCount++;
                    try { Thread.sleep(400); } catch (Exception e) {}
                }
            }
            if (swipedCount == 0 && !words.isEmpty()) {
                 new Handler(Looper.getMainLooper()).post(() -> 
                    Toast.makeText(this, "Could not trace path for words!", Toast.LENGTH_LONG).show());
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
