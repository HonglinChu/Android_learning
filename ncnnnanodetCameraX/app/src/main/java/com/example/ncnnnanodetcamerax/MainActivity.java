package com.example.ncnnnanodetcamerax;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraX;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageAnalysisConfig;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureConfig;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.PreviewConfig;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Rational;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;


public class MainActivity extends AppCompatActivity {

    private int REQUEST_CODE_PERMISSIONS = 101;
    private final String[] REQUIRED_PERMISSIONS = new String[]{"android.permission.CAMERA"};
    TextureView textureView;    // 用来preview的textureview
    ImageView ivBitmap;         // 叠加在textureview上面的bitmap

    // CameraX的预览、分析，由于我们这里不需要捕获所以就不用了
    Preview preview;
    ImageAnalysis imageAnalysis;

    // 创建一个nanoDetNcnn
    private NanoDetNcnn nanodetncnn = new NanoDetNcnn();

    // 查询是否满足当前所有权限需求
    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE); // 将app最上面那个带名字的title隐藏掉
        setContentView(R.layout.activity_main); // 设置控件的布局
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT); // 锁定竖屏
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // 保持屏幕常亮

        textureView = findViewById(R.id.textureView);   // 绑定textureview
        ivBitmap = findViewById(R.id.ivBitmap);         // 绑定bitmap

        // 检查权限并启动摄像头
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        // 这里就不做选择了，直接默认模型0和CPU
        boolean ret_init = nanodetncnn.loadModel(getAssets(), 0, 0);
        if (!ret_init) {
            Log.e("MainActivity", "nanodetncnn loadModel failed");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        // 权限请求回调函数
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void updateTransform() {
        Matrix mx = new Matrix();
        float w = textureView.getMeasuredWidth();
        float h = textureView.getMeasuredHeight();

        float cX = w / 2f;
        float cY = h / 2f;

        int rotationDgr;
        int rotation = (int) textureView.getRotation();

        switch (rotation) {
            case Surface.ROTATION_0:
                rotationDgr = 0;
                break;
            case Surface.ROTATION_90:
                rotationDgr = 90;
                break;
            case Surface.ROTATION_180:
                rotationDgr = 180;
                break;
            case Surface.ROTATION_270:
                rotationDgr = 270;
                break;
            default:
                return;
        }

        mx.postRotate((float) rotationDgr, cX, cY);
        textureView.setTransform(mx);
    }

    private void startCamera() {
        CameraX.unbindAll(); // 解绑CameraX
        preview = setPreview(); // 设置预览
        imageAnalysis = setImageAnalysis(); // 设置图像分析
        // 绑定CameraX到生命周期，并喂入预览和分析
        CameraX.bindToLifecycle(this, preview, imageAnalysis);
    }

    private Preview setPreview() {
        Rational aspectRatio = new Rational(textureView.getWidth(), textureView.getHeight()); // 获取textureview的宽高比
        Size screen = new Size(textureView.getWidth(), textureView.getHeight()); // 获取屏幕的尺寸

        // 配置预览设置
        PreviewConfig pConfig = new PreviewConfig.Builder()
                .setTargetAspectRatio(aspectRatio)
                .setTargetResolution(screen)
                .build();
        Preview preview = new Preview(pConfig); // 用预览配置生成preview
        // 设置预览监听
        preview.setOnPreviewOutputUpdateListener(
                new Preview.OnPreviewOutputUpdateListener() {
                    @Override
                    public void onUpdated(Preview.PreviewOutput output) {
                        ViewGroup parent = (ViewGroup) textureView.getParent();
                        parent.removeView(textureView);
                        parent.addView(textureView, 0);
                        textureView.setSurfaceTexture(output.getSurfaceTexture());
                        updateTransform();
                    }
                });
        return preview;
    }

    private ImageAnalysis setImageAnalysis() {
        // 设置用来做图像分析的线程
        HandlerThread analyzerThread = new HandlerThread("OpenCVAnalysis");
        analyzerThread.start();

        // 配置图像分析并生成imageanalysis
        ImageAnalysisConfig imageAnalysisConfig = new ImageAnalysisConfig.Builder()
                .setImageReaderMode(ImageAnalysis.ImageReaderMode.ACQUIRE_LATEST_IMAGE) // 接收最新的图
                .setCallbackHandler(new Handler(analyzerThread.getLooper())) // 设置回调
                .setImageQueueDepth(1).build(); // 设置图像队列深度为1
        ImageAnalysis imageAnalysis = new ImageAnalysis(imageAnalysisConfig);

        // 设置分析器
        imageAnalysis.setAnalyzer(
            new ImageAnalysis.Analyzer() {
                @Override
                public void analyze(ImageProxy image, int rotationDegrees) {
                    // 获取摄像头实时返回的textureview的bitmap
                    final Bitmap bitmap = textureView.getBitmap();
                    if(bitmap==null) return;

                    // 在这里跑ncnn
                    int width = bitmap.getWidth();
                    int height = bitmap.getHeight();
                    int[] pixArr = new int[width*height];
                    // bitmap转数组
                    bitmap.getPixels(pixArr,0,width,0,0,width,height);
                    // 推理
                    nanodetncnn.detectDraw(width,height,pixArr);
                    // 数组转回去bitmap
                    Bitmap newBitmap = Bitmap.createBitmap(width,height,Bitmap.Config.RGB_565);
                    newBitmap.setPixels(pixArr,0,width,0,0,width,height);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            ivBitmap.setImageBitmap(newBitmap); // 将推理后的bitmao喂回去
                        }
                    });
                }
            });
        return imageAnalysis;
    }
}