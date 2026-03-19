package com.example.barcodescanner;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "BarcodeScanner";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = {Manifest.permission.CAMERA};

    private PreviewView previewView;
    private TextView textViewResult;
    private TextView textCodeType;
    private TextView textScanTime;
    private TextView btnTorch;
    private TextView btnRescan;
    private TextView iconResult;
    private FrameLayout scanOverlay;

    private BarcodeScanner barcodeScanner;
    private ExecutorService cameraExecutor;
    private ToneGenerator toneGenerator;
    private Vibrator vibrator;
    private Camera camera;
    private boolean isTorchOn = false;
    private boolean isScanSuccessful = false;
    private long lastScanTimeMs = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        textViewResult = findViewById(R.id.textViewResult);
        textCodeType = findViewById(R.id.textCodeType);
        textScanTime = findViewById(R.id.textScanTime);
        btnTorch = findViewById(R.id.btnTorch);
        btnRescan = findViewById(R.id.btnRescan);
        iconResult = findViewById(R.id.iconResult);
        scanOverlay = findViewById(R.id.scanOverlay);

        btnTorch.setOnClickListener(v -> toggleTorch());
        btnRescan.setOnClickListener(v -> resetScan());

        // 创建条码扫描器 - 支持所有格式
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build();
        barcodeScanner = BarcodeScanning.getClient(options);

        initToneGenerator();
        initVibrator();

        cameraExecutor = Executors.newSingleThreadExecutor();

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::processImageProxy);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "相机启动失败", e);
                Toast.makeText(this, "相机启动失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processImageProxy(ImageProxy imageProxy) {
        // 防止重复触发
        if (isScanSuccessful) {
            imageProxy.close();
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastScanTimeMs < 1000) {
            imageProxy.close();
            return;
        }

        try {
            Bitmap bitmap = imageProxyToBitmap(imageProxy);
            if (bitmap == null) {
                imageProxy.close();
                return;
            }

            InputImage inputImage = InputImage.fromBitmap(bitmap, 0);

            barcodeScanner.process(inputImage)
                    .addOnSuccessListener(barcodes -> {
                        if (!barcodes.isEmpty()) {
                            Barcode barcode = barcodes.get(0);
                            String value = barcode.getRawValue();
                            int format = barcode.getFormat();
                            if (value != null && !isScanSuccessful) {
                                isScanSuccessful = true;
                                lastScanTimeMs = now;
                                runOnUiThread(() -> onScanSuccess(value, format));
                            }
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "扫描失败", e);
                    })
                    .addOnCompleteListener(task -> {
                        imageProxy.close();
                        bitmap.recycle();
                    });

        } catch (Exception e) {
            Log.e(TAG, "图像处理失败", e);
            imageProxy.close();
        }
    }

    private void onScanSuccess(String value, int format) {
        String formatName = getFormatName(format);
        String scanTime = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

        textViewResult.setText(value);
        textCodeType.setText(formatName);
        textScanTime.setText(scanTime);
        iconResult.setText("✅");
        btnRescan.setVisibility(View.VISIBLE);

        // 隐藏扫描框提示，显示扫描结果
        scanOverlay.setVisibility(View.GONE);

        playSuccessFeedback();
    }

    private String getFormatName(int format) {
        switch (format) {
            case Barcode.FORMAT_QR_CODE: return "二维码 (QR)";
            case Barcode.FORMAT_DATA_MATRIX: return "Data Matrix (DM码)";
            case Barcode.FORMAT_PDF417: return "PDF417";
            case Barcode.FORMAT_AZTEC: return "Aztec";
            case Barcode.FORMAT_CODABAR: return "Codabar";
            case Barcode.FORMAT_CODE_128: return "Code 128";
            case Barcode.FORMAT_CODE_39: return "Code 39";
            case Barcode.FORMAT_CODE_93: return "Code 93";
            case Barcode.FORMAT_EAN_13: return "EAN-13";
            case Barcode.FORMAT_EAN_8: return "EAN-8";
            case Barcode.FORMAT_ITF: return "ITF-14";
            case Barcode.FORMAT_UPC_A: return "UPC-A";
            case Barcode.FORMAT_UPC_E: return "UPC-E";
            default: return "条码 (" + format + ")";
        }
    }

    private void resetScan() {
        isScanSuccessful = false;
        lastScanTimeMs = 0;
        textViewResult.setText("请将条码对准相机");
        textCodeType.setText("--");
        textScanTime.setText("--");
        iconResult.setText("🔍");
        btnRescan.setVisibility(View.GONE);
        scanOverlay.setVisibility(View.VISIBLE);
    }

    private void toggleTorch() {
        if (camera == null) return;
        isTorchOn = !isTorchOn;
        camera.getCameraControl().enableTorch(isTorchOn);
        btnTorch.setText(isTorchOn ? "🔦 关" : "💡 开");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "需要相机权限才能扫描", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (toneGenerator != null) toneGenerator.release();
        cameraExecutor.shutdown();
        barcodeScanner.close();
    }

    private void initToneGenerator() {
        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
        } catch (Exception e) {
            Log.e(TAG, "初始化音调生成器失败", e);
        }
    }

    private void initVibrator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vibratorManager = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
            vibrator = vibratorManager.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        }
    }

    private void playSuccessFeedback() {
        if (toneGenerator != null) toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 200);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 150, 80, 150}, -1));
            } else {
                vibrator.vibrate(new long[]{0, 150, 80, 150}, -1);
            }
        }
    }

    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        try {
            Image image = imageProxy.getImage();
            if (image == null) return null;

            Image.Plane[] planes = image.getPlanes();
            ByteBuffer yBuffer = planes[0].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();

            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();

            byte[] nv21 = new byte[ySize + uSize + vSize];

            yBuffer.get(nv21, 0, ySize);
            vBuffer.get(nv21, ySize, vSize);
            uBuffer.get(nv21, ySize + vSize, uSize);

            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 80, outputStream);

            Bitmap bitmap = BitmapFactory.decodeByteArray(outputStream.toByteArray(), 0, outputStream.size());

            if (imageProxy.getImageInfo().getRotationDegrees() != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(imageProxy.getImageInfo().getRotationDegrees());
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }

            return bitmap;

        } catch (Exception e) {
            Log.e(TAG, "图像转换失败", e);
            return null;
        }
    }
}
