# Camera2 通用开发套路总结 (预览与拍照)

本文档总结了 Android Camera2 API 中最核心的开发流程，以及每个步骤中涉及的关键变量。这是掌握 Camera2 的“万能模板”。

## 一、 核心变量概览

| 变量名 | 类型 | 作用 | 比喻 |
| :--- | :--- | :--- | :--- |
| **mCameraManager** | `CameraManager` | 系统服务，用于获取相机列表和打开相机。 | **管家** |
| **mCameraDevice** | `CameraDevice` | 代表连接到的物理相机硬件。 | **摄像机** |
| **mCaptureSession** | `CameraCaptureSession` | 相机与 Surface 之间的会话通道。 | **剧组/管道** |
| **mRequestBuilder** | `CaptureRequest.Builder` | 构建预览或拍照的请求（配置参数）。 | **工单模板** |
| **mTextureView** | `TextureView` | 用于显示预览画面的 UI 控件。 | **取景器** |
| **mImageReader** | `ImageReader` | 用于接收高质量静态图片数据的缓冲区。 | **底片/存储卡** |

---

## 二、 通用开发流程 (5步走)

### 第 1 步：准备 Surface (容器)
在开启相机之前，必须确保有地方接收数据。

*   **预览 Surface**：等待 `TextureView` 回调 `onSurfaceTextureAvailable`。
*   **拍照 Surface**：初始化 `ImageReader`，设置分辨率和格式 (JPEG)，并添加 `OnImageAvailableListener` 监听数据回调。

### 第 2 步：打开相机 (Open)
通过 `CameraManager` 打开指定 ID 的相机。

```java
manager.openCamera(cameraId, stateCallback, backgroundHandler);
```

*   **输入**：Camera ID (通常 "0" 后置, "1" 前置)。
*   **回调**：`StateCallback.onOpened` -> 获取 `CameraDevice` 对象。

### 第 3 步：创建会话 (Session)
这是连接“摄像机”和“屏幕/文件”的关键一步。

```java
// 必须把所有可能用到的 Surface 都加进来！
List<Surface> targets = Arrays.asList(surfacePreview, surfaceImageReader);
mCameraDevice.createCaptureSession(targets, sessionCallback, backgroundHandler);
```

*   **关键点**：如果你要预览**并且**拍照，必须同时把 `textureView.getSurface()` 和 `imageReader.getSurface()` 都传进去。
*   **回调**：`StateCallback.onConfigured` -> 获取 `CameraCaptureSession` 对象。

### 第 4 步：开启预览 (Preview)
会话建立后，发送一个**重复**的请求，让相机不断吐出画面。

```java
// 1. 创建预览模板
mRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
mRequestBuilder.addTarget(surfacePreview); // 目标是屏幕

// 2. 发送重复请求
mCaptureSession.setRepeatingRequest(mRequestBuilder.build(), null, backgroundHandler);
```

### 第 5 步：触发拍照 (Capture)
当用户点击按钮时，发送一个**单次**的请求。

```java
// 1. 创建拍照模板 (高质量)
CaptureRequest.Builder captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
captureBuilder.addTarget(surfaceImageReader); // 目标是 ImageReader

// 2. 停止预览 (可选，视需求而定)
mCaptureSession.stopRepeating();

// 3. 发送单次请求
mCaptureSession.capture(captureBuilder.build(), captureCallback, backgroundHandler);
```

---

## 三、 老师的避坑指南 (FAQ)

1.  **Q: 为什么拍照会崩溃？**
    *   **A:** 90% 的原因是创建 Session 时忘了把 `ImageReader` 的 Surface 加进去。相机不认识未注册的 Surface。

2.  **Q: 为什么预览画面变形？**
    *   **A:** `TextureView` 的宽高比必须和相机输出的宽高比一致。需要根据 `StreamConfigurationMap` 选择合适的预览尺寸，并调整 View 的大小。

3.  **Q: 为什么要用 HandlerThread？**
    *   **A:** 相机操作非常耗时，如果直接在主线程跑，界面会严重卡顿。必须把所有 Camera 回调扔到后台线程执行。

4.  **Q: 拍完照预览停了？**
    *   **A:** 是的，`capture` 后某些设备会停止预览流。需要在 `onCaptureCompleted` 回调里重新调用 `setRepeatingRequest` 恢复预览。

---

## 四、 特别篇：架构的核心“万物归一”
**Q: ImageReader 最后也要获取一下 Surface 是吧？统一接口？**

**A:** 是的，完全正确！这正是 Camera2 架构“万物归一”的关键点。

1.  **统一接口**：
    在 Android Camera2 架构中，**Surface 是唯一的数据货币**。
    不管你的数据最终是去哪里（是去屏幕显示、去文件保存、还是去算法识别），相机硬件（Producer）**只认 Surface**。

2.  **ImageReader 的本质**：
    虽然 `ImageReader` 是一个处理图像数据的类，但它为了能从相机接到数据，它必须提供一个**接收口**。这个接收口就是 `Surface`。
    这就是为什么你必须调用 `imageReader.getSurface()`，并把它传给 `captureBuilder.addTarget()`。

3.  **形象比喻**：
    *   **相机**是自来水厂。
    *   **Surface** 是标准水管接口。
    *   **TextureView** 是一个接了水管的**显示屏**。
    *   **ImageReader** 是一个接了水管的**蓄水池**。

    水厂（相机）不管你是显示屏还是蓄水池，它只管往水管接口（Surface）里注水。

    **所以，不仅是 `ImageReader`，包括 `MediaRecorder`（录像）、`MediaCodec`（编解码），只要是接收相机数据的组件，最后都要调用 `.getSurface()` 来获取这个“入场券”。**

---

## 五、 会话创建详解：连接软硬件的必经之路
**Q: `createCaptureSession` 这句话是必须的吗？它做了什么？**

**A:** **是的，这句话是必须的，少了它相机根本动不了。**

### 1. 技术层面：做了什么？
`createCaptureSession` 本质上是在做 **硬件资源的分配与配置**。
当你调用时，底层发生了一系列重型操作：
1.  **配置 ISP**：通知底层硬件即将有几路数据流（如 1080p 预览 + 4K 拍照），准备好带宽和处理管线。
2.  **分配内存**：根据传入的 Surface 计算并申请所需的 Buffer 内存。
3.  **建立连接**：打通传感器到 Surface 的物理数据通道。

### 2. 核心规则：注册制
**凡是你想用来接数据的 Surface，必须在创建 Session 的时候提前注册。**
*   如果你只注册了预览的 Surface，等到拍照时才想用 `ImageReader` 的 Surface，相机会报错：“我不认识这个 Surface！”

### 3. 生动比喻：接线与调试
*   **CameraDevice** 是**摄影机**。
*   **Surface** 是**监视器**和**录像带**。
*   **createCaptureSession** 就是 **“接线”** 的过程。
    
    你必须在开拍前，把所有要用的设备线都插在摄影机上。一旦开拍（Session 建立），接口就封死了。想换设备？必须停机（close session），拔线重插（create new session）。
