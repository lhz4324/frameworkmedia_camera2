package com.example.frameworkmedia;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 这是一个关于 Camera2 API 的教学示例。
 * 老师会在这里手把手教你如何开启相机预览，以及如何使用 ImageReader 进行拍照。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "Camera2Tutorial";
    private static final int REQUEST_CAMERA_PERMISSION = 200;

    // 1. 预览控件
    // TextureView 是用于显示相机预览流的视图。
    private TextureView textureView;
    private Button btnCapture;

    // 2. 相机设备相关变量
    // CameraDevice 代表一个连接到的物理相机设备。
    private CameraDevice cameraDevice;
    // CameraCaptureSession 是我们与相机设备交互的会话，所有的预览、拍照请求都通过它发送。
    private CameraCaptureSession cameraCaptureSession;
    // CaptureRequest.Builder 用于构建具体的拍照或预览请求（例如设置曝光、对焦模式等）。
    private CaptureRequest.Builder captureRequestBuilder;
    // 预览尺寸
    private Size imageDimension;

    // 3. 拍照相关变量 (新增)
    // ImageReader：专门用于接收相机拍摄的高质量静态图片数据
    private ImageReader imageReader;
    // 拍照完成后的文件保存路径
    private File file;

    // 4. 线程处理
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureView = findViewById(R.id.textureView);
        btnCapture = findViewById(R.id.btn_capture);

        // 设置拍照按钮点击事件
        btnCapture.setOnClickListener(v -> takePicture());
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
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
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

            // --- 新增代码：初始化 ImageReader ---
            // 1. 获取相机支持的最大 JPEG 尺寸（用于拍照）
            // 实际项目中应该遍历 map.getOutputSizes(ImageFormat.JPEG) 找到最大的那个尺寸
            Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
            int width = 640;
            int height = 480;
            if (jpegSizes != null && jpegSizes.length > 0) {
                width = jpegSizes[0].getWidth();
                height = jpegSizes[0].getHeight();
            }
            
            // 2. 创建 ImageReader
            // 参数1：宽，参数2：高
            // 参数3：ImageFormat.JPEG 表示我们要照片数据
            // 参数4：maxImages = 1，表示同时也只处理一张照片（这对于内存有限的手机很重要）
            imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);
            
            // 3. 设置照片可用监听器
            // 当相机把照片数据填满 ImageReader 时，会回调 onImageAvailable
            imageReader.setOnImageAvailableListener(readerListener, backgroundHandler);
            // --- 新增代码结束 ---

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
                return;
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "打开相机失败", e);
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            createCameraPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            cameraDevice.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            cameraDevice.close();
            cameraDevice = null;
            finish();
        }
    };

    private void createCameraPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            Surface surface = new Surface(texture);

            // 老师敲黑板：
            // 创建会话时，必须要把 ImageReader 的 Surface 也加进去！
            // 否则后面拍照的时候，相机说：“我不认识这个目标”，就会报错。
            // Arrays.asList(surface, imageReader.getSurface())
            List<Surface> outputSurfaces = new ArrayList<>();
            outputSurfaces.add(surface);
            outputSurfaces.add(imageReader.getSurface()); // 必须添加 ImageReader 的 Surface

            cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    // 相机配置完成，可以开始预览了
                    if (cameraDevice == null) return;

                    // 会话准备好了
                    cameraCaptureSession = session;
                    try {
                        // 1. 创建 CaptureRequest.Builder
                        // TEMPLATE_PREVIEW：专门用于相机预览的模板，会自动优化帧率和自动对焦等参数。
                        // 老师解答：
                        // 是的！你完全可以在这里（onConfigured）再创建 builder。
                        // createCaptureRequest 和 createCaptureSession 没有严格的前后顺序依赖。
                        // 只要在发送请求（setRepeatingRequest）之前创建好并 addTarget 就行。
                        // 放在这里的好处是：逻辑更紧凑，都是“配置会话”相关的操作。
                        captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        // 将我们的 Surface 添加为目标，告诉相机把数据发到这里
                        captureRequestBuilder.addTarget(surface);

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

    /**
     * 执行拍照的方法
     */
    private void takePicture() {
        if (cameraDevice == null) return;
        
        // 老师解惑：为什么这里要重新获取 CameraManager？
        // 其实这里主要是为了再次获取相机特性（如方向），如果之前保存了 characteristics 变量，这里可以省略。
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
            Size[] jpegSizes = null;
            if (characteristics != null) {
                jpegSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        .getOutputSizes(ImageFormat.JPEG);
            }
            int width = 640;
            int height = 480;
            if (jpegSizes != null && jpegSizes.length > 0) {
                width = jpegSizes[0].getWidth();
                height = jpegSizes[0].getHeight();
            }

            // 1. 创建拍照请求 (TEMPLATE_STILL_CAPTURE)
            // 这种模板专门为高质量静态拍照优化（比如降噪、ISP处理更精细）
            final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            
            // 2. 设置输出目标为 ImageReader 的 Surface
            // 这意味着：拍出来的照片，请发给 ImageReader
            captureBuilder.addTarget(imageReader.getSurface());
            
            // 设置自动对焦模式
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            
            // 3. 处理照片方向
            // 手机有物理方向（SENSOR_ORIENTATION）和设备当前旋转方向（getWindowManager）
            // 这里为了简化，我们直接写死一个常见方向，实际开发需要根据传感器计算
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation));

            // 设置文件保存路径
            file = new File(getExternalFilesDir(null), "pic.jpg");

            // 4. 创建拍照完成的回调
            CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    Toast.makeText(MainActivity.this, "Saved:" + file, Toast.LENGTH_SHORT).show();
                    // 拍照完成后，重新开启预览（因为有些设备拍照后会停止预览流）
                    createCameraPreview();
                }
            };

            // 5. 停止预览，开始拍照
            // 拍照是一次性操作，所以用 capture() 而不是 setRepeatingRequest()
            cameraCaptureSession.stopRepeating();
            cameraCaptureSession.abortCaptures();
            cameraCaptureSession.capture(captureBuilder.build(), captureCallback, backgroundHandler);
            
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    
    // 计算照片方向的辅助方法
    private int getOrientation(int rotation) {
        // 这里只是简单的映射，实际需要结合 SENSOR_ORIENTATION
        // 为了演示方便，这里简化处理
        return 90; 
    }

    /**
     * ImageReader 的回调监听器
     * 当照片数据准备好时，会调用 onImageAvailable
     */
    ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = null;
            try {
                // 1. 获取最新的一张图片
                image = reader.acquireLatestImage();
                // 2. 将图片数据转为字节流并保存
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.capacity()];
                buffer.get(bytes);
                save(bytes);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                // 3. 必须关闭 image，否则 ImageReader 缓冲区满了之后就不会再接收新照片了
                if (image != null) {
                    image.close();
                }
            }
        }
    };

    // 保存文件到磁盘
    private void save(byte[] bytes) throws IOException {
        OutputStream output = null;
        try {
            output = new FileOutputStream(file);
            output.write(bytes);
        } finally {
            if (output != null) {
                output.close();
            }
        }
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

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

    private void closeCamera() {
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission Granted", Toast.LENGTH_SHORT).show();
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
