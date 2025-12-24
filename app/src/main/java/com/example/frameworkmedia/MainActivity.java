package com.example.frameworkmedia;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.Collections;

/**
 * 这是一个关于 Camera2 API 的教学示例。
 * 老师会在这里手把手教你如何开启相机预览，以及为什么每一步是必要的。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "Camera2Tutorial";
    private static final int REQUEST_CAMERA_PERMISSION = 200;

    // 1. 预览控件
    // TextureView 是用于显示相机预览流的视图。相比 SurfaceView，它支持像普通 View 一样的动画操作（平移、缩放等）。
    private TextureView textureView;

    // 2. 相机设备相关变量
    // CameraDevice 代表一个连接到的物理相机设备。
    private CameraDevice cameraDevice;
    // CameraCaptureSession 是我们与相机设备交互的会话，所有的预览、拍照请求都通过它发送。
    private CameraCaptureSession cameraCaptureSession;
    // CaptureRequest.Builder 用于构建具体的拍照或预览请求（例如设置曝光、对焦模式等）。
    private CaptureRequest.Builder captureRequestBuilder;
    // 预览尺寸，我们需要根据相机支持的尺寸和屏幕尺寸来选择
    private Size imageDimension;

    // 3. 线程处理
    // Camera2 API 的操作比较耗时，强烈建议不要在主线程（UI线程）中执行，以免卡顿。
    // 我们创建一个后台线程来处理相机操作。
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureView = findViewById(R.id.textureView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 老师提示：在 onResume 中启动后台线程和打开相机，
        // 确保应用回到前台时相机能恢复工作。
        startBackgroundThread();

        // TextureView 的 SurfaceTexture 准备好后才能显示预览。
        // 如果 TextureView 已经可用，直接打开相机；否则设置监听器等待它准备好。
        if (textureView.isAvailable()) {
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }
    }

    @Override
    protected void onPause() {
        // 老师提示：在 onPause 中必须关闭相机和线程，释放资源。
        // 相机是独占资源，如果不释放，其他应用将无法使用相机，且会快速耗尽电池。
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    // -------------------------------------------------------------------------
    // 第一步：监听 TextureView 状态
    // -------------------------------------------------------------------------
    /**
     * TextureView 的生命周期监听器。
     * 我们主要关注 onSurfaceTextureAvailable，这意味着绘制表面准备好了，可以把相机画面投射上去了。
     */
    private final TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            // Surface 准备好了，可以打开相机了
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            // 可以在这里处理预览尺寸变化，比如旋转屏幕时
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
        }
    };

    // -------------------------------------------------------------------------
    // 第二步：打开相机
    // -------------------------------------------------------------------------
    /**
     * 打开相机的核心逻辑。
     */
    private void openCamera() {
        // 获取系统相机服务 CameraManager
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            // 获取后置摄像头的 ID
            // 这里的逻辑通常需要遍历 cameraIdList，找到 LENS_FACING_BACK 的摄像头。
            // 为了简化教学，我们假设 "0" 通常是后置摄像头。
            String cameraId = manager.getCameraIdList()[0];
            
            // 获取相机特性
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            
            // 获取相机支持的流配置（比如支持哪些分辨率的预览）
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            
            // 简单起见，我们直接获取支持的第一个预览尺寸
            // 实际开发中，应该遍历 map.getOutputSizes(SurfaceTexture.class) 
            // 找到最接近屏幕比例且清晰度满足需求的尺寸。
            imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];

            // 检查权限。Android 6.0+ 必须动态申请权限。
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
                return;
            }

            // 打开相机
            // 参数1：cameraId - 摄像头ID
            // 参数2：stateCallback - 监听相机打开、断开、错误的状态
            // 参数3：backgroundHandler - 指定回调在哪个线程执行（非常重要，避免阻塞 UI）
            manager.openCamera(cameraId, stateCallback, backgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "打开相机失败", e);
        }
    }

    // -------------------------------------------------------------------------
    // 第三步：监听相机设备状态
    // -------------------------------------------------------------------------
    /**
     * 相机设备状态回调。
     * 当 manager.openCamera 成功连接到相机硬件后，会回调 onOpened。
     */
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            // 相机打开成功！
            // 这里的 camera 对象就是我们操作硬件的句柄。
            cameraDevice = camera;
            // 下一步：开启预览会话
            createCameraPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            // 相机连接断开（例如被其他高优先级应用抢占）
            cameraDevice.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            // 发生错误
            cameraDevice.close();
            cameraDevice = null;
            finish(); // 严重错误通常需要关闭 Activity
        }
    };

    // -------------------------------------------------------------------------
    // 第四步：创建预览会话
    // -------------------------------------------------------------------------
    /**
     * 创建相机预览会话。这是显示画面的关键步骤。
     */
    private void createCameraPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;

            // 设置默认缓冲区的大小，必须设置，否则画面可能会拉伸或者模糊
            // 我们使用之前从 CameraCharacteristics 获取到的最佳尺寸
            texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());

            // 创建 Surface，这是相机数据的输出目标
            Surface surface = new Surface(texture);

            // 1. 创建 CaptureRequest.Builder
            // TEMPLATE_PREVIEW：专门用于相机预览的模板，会自动优化帧率和自动对焦等参数。
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            // 将我们的 Surface 添加为目标，告诉相机把数据发到这里
            captureRequestBuilder.addTarget(surface);

            // 2. 创建 CameraCaptureSession
            // 参数1：Arrays.asList(surface) - 输出目标列表。必须包含上面 addTarget 的所有 surface。
            // 参数2：CameraCaptureSession.StateCallback - 会话状态监听
            // 参数3：backgroundHandler - 回调执行线程
            cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    // 相机配置完成，可以开始预览了
                    if (cameraDevice == null) return;

                    // 会话准备好了
                    cameraCaptureSession = session;
                    try {
                        // 设置自动对焦模式为连续自动对焦（适合预览）
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                        // 3. 发送重复请求（Repeating Request）
                        // setRepeatingRequest 会持续不断地请求图像数据，从而形成视频预览流。
                        // 如果只是拍照，通常使用 capture() 方法发送单次请求。
                        cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "开启预览失败", e);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Toast.makeText(MainActivity.this, "Configuration change", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "创建预览会话失败", e);
        }
    }

    // -------------------------------------------------------------------------
    // 辅助方法：后台线程管理与权限回调
    // -------------------------------------------------------------------------
    
    // 启动后台线程
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    // 停止后台线程
    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "停止后台线程失败", e);
            }
        }
    }

    // 关闭相机
    private void closeCamera() {
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    // 处理权限申请结果
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission Granted", Toast.LENGTH_SHORT).show();
                // 此时虽然有权限了，但 onResume 可能已经跑完了，所以需要手动再触发一次
                // 如果 textureView 已经准备好
                if (textureView.isAvailable()) {
                    openCamera();
                }
            } else {
                Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
}
