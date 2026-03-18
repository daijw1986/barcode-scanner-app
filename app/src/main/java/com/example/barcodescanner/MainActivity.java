package com.example.barcodescanner;

import android.Manifest;
import android.content.pm.PackageManager;
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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "BarcodeScanner";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = {Manifest.permission.CAMERA};

    private PreviewView previewView;
    private TextView textViewResult;
    private BarcodeScanner barcodeScanner;
    private ExecutorService cameraExecutor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        textViewResult = findViewById(R.id.textViewResult);

        // 创建条码扫描器 - 支持所有格式
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build();
        barcodeScanner = BarcodeScanning.getClient(options);

        cameraExecutor = Executors.newSingleThreadExecutor();

        // 检查权限
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

                // 预览
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // 图像分析 - 使用 720p 分辨率提高兼容性
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    processImageProxy(imageProxy);
                });

                // 选择后置摄像头
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                // 绑定生命周期
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "相机启动失败", e);
                Toast.makeText(this, "相机启动失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processImageProxy(ImageProxy imageProxy) {
        try {
            Image image = imageProxy.getImage();
            if (image == null) {
                imageProxy.close();
                return;
            }

            // 转换为 Bitmap 用于 ML Kit
            Bitmap bitmap = imageProxyToBitmap(imageProxy);
            if (bitmap == null) {
                imageProxy.close();
                return;
            }

            // 创建输入图像
            InputImage inputImage = InputImage.fromBitmap(bitmap, 0);

            // 扫描条码
            barcodeScanner.process(inputImage)
                    .addOnSuccessListener(barcodes -> {
                        if (!barcodes.isEmpty()) {
                            Barcode barcode = barcodes.get(0);
                            String value = barcode.getRawValue();
                            if (value != null) {
                                runOnUiThread(() -> {
                                    textViewResult.setText("识别结果:\n" + value);
                                    Toast.makeText(this, "识别成功!", Toast.LENGTH_SHORT).show();
                                });
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

    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        try {
            Image image = imageProxy.getImage();
            if (image == null) return null;

            // 获取 YUV 数据
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

            // 旋转图像
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
        cameraExecutor.shutdown();
        barcodeScanner.close();
    }
}
