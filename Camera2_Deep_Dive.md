# Camera2 API 深度解析：从应用层到底层架构

本文档由浅入深地解析了 Android Camera2 API 的内部实现原理。我们将从你最熟悉的应用层代码出发，一路向下挖掘到 Linux 内核驱动层，揭示相机背后的工作机制。

---

## 一、 第一层：应用层 (Application Layer)
**—— 你看得见的代码**

这一层是开发者日常接触的领域，主要由 Java API 构成。

### 1. 核心类与概念
*   **CameraManager (管家)**
    *   **定义**：系统服务的管理者，负责检测、打开相机。
    *   **原理**：它是一个**代理 (Proxy)**。当你调用 `openCamera` 时，它并不亲自干活，而是通过 Binder 电话线通知系统服务。
*   **CameraDevice (相机)**
    *   **定义**：代表物理相机硬件的 Java 对象。
    *   **原理**：它是硬件的抽象，用于创建 CaptureSession。
*   **CameraCaptureSession (会话)**
    *   **定义**：连接 App 和底层相机硬件的**数据管道**。
    *   **原理**：它是一个昂贵的资源对象。创建它意味着底层硬件开始配置数据流（Stream），准备向指定的 Surface 输送数据。
*   **CaptureRequest.Builder (工单)**
    *   **定义**：包含所有拍摄参数（ISO、曝光、对焦）的配置对象。
    *   **原理**：每次预览或拍照，本质上都是在向会话提交一个 Request。

### 2. 核心数据容器
*   **Surface / SurfaceTexture**
    *   **Surface**：系统通用的**数据接口**，生产者（相机）只认它。
    *   **SurfaceTexture**：OpenGL 纹理缓冲区，用于**预览**。它接收图像数据并转为纹理供 GPU 渲染。
*   **ImageReader**
    *   **定义**：用于获取原始图像数据（如 JPEG/YUV）的类。
    *   **原理**：它内部管理着一个 **BufferQueue**（图形缓冲区队列），通过 JNI 直接访问底层的共享内存。

---

## 二、 第二层：系统框架层 (Framework Layer)
**—— 幕后的调度中心**

当你调用 API 时，真正的逻辑发生在这里。这一层主要由 System Server 和 Native Service 组成。

### 1. IPC 跨进程通信 (Binder)
*   **概念**：Android 进程间通信的机制。
*   **场景**：
    *   你的 App 是进程 A。
    *   相机的管理者（CameraService）是进程 B。
    *   你调用 `manager.openCamera()`，实际上是进程 A 发送了一个 **Binder 事务** 给进程 B。
*   **AIDL (ICameraDevice)**
    *   **定义**：接口定义语言。
    *   **作用**：它规定了进程 A 和进程 B 之间“说话”的协议。`ICameraDevice` 就是这个协议接口，定义了 `submitRequest` 等底层方法。

### 2. System Server 与 Native Service 的区别
**这是 Android 系统中两类最重要的后台进程。**

*   **System Server (系统服务进程)**
    *   **身份**：Android 系统的“大管家”，一个巨大的 **Java 进程**（也会加载很多 JNI 库）。
    *   **职责**：它里面运行着几十个核心服务，比如：
        *   `ActivityManagerService` (AMS)：管理你的 Activity 生命周期。
        *   `WindowManagerService` (WMS)：管理屏幕窗口。
        *   `PackageManagerService` (PMS)：管理 App 安装。
    *   **对相机的作用**：负责相机的**权限检查**（PermissionService）和**窗口合成**（配合 SurfaceFlinger）。
    *   **特点**：如果它挂了，手机会自动软重启（看到开机动画）。

*   **Native Service (原生服务，如 CameraService)**
    *   **身份**：专门干脏活累活的 **C++ 进程**。
    *   **职责**：负责可以直接操作底层硬件或需要高性能的任务。
        *   `cameraserver`：运行 CameraService，管理相机硬件。
        *   `mediaserver`：管理音频和视频编解码。
    *   **对相机的作用**：它是相机的**直接管理者**。它加载 HAL 动态库，直接给硬件发指令。
    *   **特点**：如果它挂了，只会导致正在使用相机的 App 崩溃或报错，手机系统通常不会重启。**为什么要独立出来？** 以前相机服务也是在 System Server 里的，但相机驱动容易崩溃，一崩手机就重启，用户体验极差。Android 7.0 之后把它剥离出来，提高了系统的稳定性（Project Treble）。

### 3. CameraService (C++ 核心服务)
*   **位置**：运行在独立的 `cameraserver` 进程中（Native C++）。
*   **职责**：
    *   **权限控制**：检查你有没有 `CAMERA` 权限。
    *   **资源仲裁**：防止两个 App 同时抢占相机。
    *   **连接器**：它一手拉着 App (Binder)，一手拉着 HAL (硬件层)。

---

## 三、 第三层：硬件抽象层 (HAL Layer)
**—— 翻译官与标准接口**

这是 Android 解决硬件碎片化的关键设计。

### 1. 为什么需要 HAL？
*   **问题**：索尼、三星、豪威的摄像头驱动各不相同，Android 源码不想为每家厂商写一套代码。
*   **解决**：Android 定义了一套**标准接口 (HAL Interface)**。所有厂商必须把自己的驱动封装成这套接口。

### 2. HAL 3.0 (Camera2 的基石)
*   **核心机制**：**Request 驱动模型**。
    *   旧版 (Camera1)：发送命令式（“开始预览”、“拍照”）。
    *   **新版 (Camera2/HAL3)**：一切皆 Request。预览是 Request，拍照也是 Request，只是参数不同。
*   **流程**：`CameraService` 把你的 `CaptureRequest` 转换成 HAL 能看懂的结构体，塞给 HAL 层。

### 3. HAL Buffer 队列 (传送带)
*   **概念**：连接相机硬件和 App 的**共享内存队列**。
*   **原理**：
    *   内存由 Kernel 分配（Gralloc）。
    *   相机硬件（ISP）往内存里**填**数据。
    *   App（ImageReader）从内存里**读**数据。
    *   **零拷贝**：数据不需要在进程间拷贝，大家操作的是同一块物理内存的不同映射。

---

## 四、 第四层：内核与驱动层 (Kernel & Driver Layer)
**—— 真正的搬运工**

这是操作硬件的最底层。

### 1. V4L2 (Video for Linux 2)
*   **定义**：Linux 系统中视频设备的标准驱动框架。
*   **作用**：底层的摄像头节点（如 `/dev/video0`）通常基于此框架。HAL 层通过 `ioctl` 系统调用与它交互，控制传感器上电、下电。

### 2. ISP (Image Signal Processor)
*   **定义**：图像信号处理器（硬件芯片）。
*   **作用**：真正的幕后英雄。它负责：
    *   **3A 算法**：自动对焦 (AF)、自动曝光 (AE)、自动白平衡 (AWB)。
    *   **格式转换**：把传感器出来的 RAW 数据转为 YUV 或 JPEG。

---

## 五、 全流程总结：当你按下“拍照”时发生了什么？

1.  **App 层**：你调用 `captureSession.capture(request)`。
2.  **IPC 层**：请求通过 Binder 发送给 `cameraserver` 进程。
3.  **Service 层**：`CameraService` 校验请求，并通过 HIDL 接口转发给 HAL。
4.  **HAL 层**：厂商 HAL 接收请求，解析参数，配置 ISP 硬件。
5.  **Kernel 层**：ISP 芯片开始工作，控制镜头马达对焦，传感器曝光。
6.  **数据回流**：
    *   传感器产生数据 -> ISP 处理 -> 填入 **Shared Buffer**。
    *   HAL 通知 Framework：“数据好了”。
7.  **App 层**：
    *   `ImageReader` 的 `onImageAvailable` 被回调。
    *   你通过 `acquireLatestImage` 拿到了那块共享内存的句柄，保存为文件。

---

**学习建议**：
*   日常开发关注 **第一层**。
*   解决崩溃和性能问题关注 **第二层**（Binder 异常）和 **第三层**（Buffer 泄漏）。
*   除非你是手机厂商工程师，否则很少涉及 **第四层**。
