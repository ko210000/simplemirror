package com.fei.simplemirror.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.fei.simplemirror.R
import java.util.concurrent.Executors

/**
 * CameraX 的绑定细节，让 MainActivity 只关心权限和提示 UI。
 *
 * 这里刻意不写 onPause/onResume 里的解绑与重绑：把 Activity 当 LifecycleOwner 交给
 * bindToLifecycle 之后，CameraX 会自己在 ON_STOP 释放相机、ON_START 重新获取。
 */
class MirrorCameraController(
    private val context: Context,
    private val previewView: PreviewView,
    private val lifecycleOwner: LifecycleOwner,
    private val listener: Listener,
) {

    interface Listener {
        /** 设备没有前置摄像头。 */
        fun onFrontCameraUnavailable()

        /** 预览已可用（首次绑定成功，或从错误中恢复）。 */
        fun onPreviewReady()

        /** 绑定失败或相机中途出错，通常是相机被别的应用占用。 */
        fun onCameraError(detail: String)

        /** 拍照完成，返回保存的文件路径 */
        fun onPhotoCaptured(filePath: String)
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var startRequested = false
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    /** 夜间补光开关状态。重绑相机后要重新套用，所以记在这里。 */
    private var lowLightBoostEnabled = false

    /** true 时照片经 MediaStore 存入公共相册；false 存应用私有目录。 */
    private var saveToGallery = false

    /** 拍照后处理（解码/旋转/写盘）用的后台线程，避免阻塞主线程。 */
    private val captureExecutor = Executors.newSingleThreadExecutor()

    /**
     * 幂等。权限刚被授予、或从设置页返回时重复调用都是安全的，只有第一次会真的绑定。
     */
    fun start() {
        if (startRequested) return
        startRequested = true

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                bind(provider)
            } catch (t: Throwable) {
                Log.e(TAG, "获取 CameraProvider 失败", t)
                listener.onCameraError(describe(t))
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** 用户点「重试」时调用：先全部解绑再绑一次。 */
    fun rebind() {
        val provider = cameraProvider ?: return
        bind(provider)
    }

    fun stop() {
        cameraProvider?.unbindAll()
        imageCapture = null
        camera = null
    }

    private fun bind(provider: ProcessCameraProvider) {
        val selector = CameraSelector.DEFAULT_FRONT_CAMERA

        if (!provider.hasCamera(selector)) {
            listener.onFrontCameraUnavailable()
            return
        }

        val preview = Preview.Builder()
            .build()
            .apply { surfaceProvider = previewView.surfaceProvider }

        val imageCapture = ImageCapture.Builder()
            // 这台类目的设备 HAL 对带旋转元数据（setTargetRotation）的 JPEG 请求
            // 以及最大分辨率的直拍都会出问题：要么 "Processing failed" 要么 JPEG
            // 尺寸元数据错乱。所以这里不设 targetRotation、限制到常规分辨率，
            // 拍摄方向改为在内存回调里按 rotationDegrees 自己旋转（见 takePicture）。
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(1600, 1200),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
            )
            .build()

        try {
            // 重试路径上如果还留着上一次的绑定，bindToLifecycle 会抛 already bound。
            provider.unbindAll()
            this.imageCapture = imageCapture
            val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            this.camera = camera
            applyLowLightBoost()
            observeCameraState(camera)
            listener.onPreviewReady()
        } catch (t: Throwable) {
            Log.e(TAG, "绑定前置摄像头失败", t)
            listener.onCameraError(describe(t))
        }
    }

    /**
     * 夜间补光：把曝光补偿拉到设备支持的最大值提亮画面，预览和拍照同时生效。
     * 设备不支持曝光补偿时静默忽略（屏幕补光仍由 Activity 侧负责）。
     */
    fun setLowLightBoost(enabled: Boolean) {
        lowLightBoostEnabled = enabled
        applyLowLightBoost()
    }

    private fun applyLowLightBoost() {
        val camera = this.camera ?: return
        val exposureState = camera.cameraInfo.exposureState
        if (!exposureState.isExposureCompensationSupported) return

        val range = exposureState.exposureCompensationRange
        val target = if (lowLightBoostEnabled) range.upper else 0
        if (target != exposureState.exposureCompensationIndex) {
            camera.cameraControl.setExposureCompensationIndex(target)
        }
    }

    /**
     * 相机被别的应用抢走时 CameraX 会重开失败并写入 error，这里把它报到 UI；
     * 之后只要重开成功（state 变回 OPEN）就把提示撤掉，所以不需要自己重试。
     */
    private fun observeCameraState(camera: Camera) {
        camera.cameraInfo.cameraState.observe(lifecycleOwner) { state ->
            val error = state?.error
            if (error != null) {
                listener.onCameraError(
                    error.cause?.let { describe(it) } ?: "code=${error.code}"
                )
            } else if (state?.type == CameraState.Type.OPEN) {
                listener.onPreviewReady()
            }
        }
    }

    private fun describe(t: Throwable): String =
        t.javaClass.simpleName + ": " + (t.message ?: "无详细信息")

    /** 设置照片存储位置：true 存公共相册，false 存应用私有目录（拍照前随时可切换）。 */
    fun setSaveToGallery(enabled: Boolean) {
        saveToGallery = enabled
    }

    /** 曝光补偿可调范围；不支持时返回 null。 */
    fun exposureCompensationRange(): IntRange? =
        camera?.cameraInfo?.exposureState
            ?.takeIf { it.isExposureCompensationSupported }
            ?.exposureCompensationRange
            ?.let { it.lower..it.upper }

    /** 当前曝光补偿档位。 */
    fun exposureCompensationIndex(): Int =
        camera?.cameraInfo?.exposureState?.exposureCompensationIndex ?: 0

    /** 手动设置曝光补偿档位（相机未就绪时忽略）。 */
    fun setExposureCompensationIndex(index: Int) {
        camera?.cameraControl?.setExposureCompensationIndex(index)
    }

    /**
     * 拍照：用内存回调拿原始 JPEG，再按 imageInfo.rotationDegrees 自己旋转后落盘。
     *
     * 不走 OutputFileOptions 是因为该设备的 HAL 不肯正确写旋转元数据/直拍带旋转
     * 请求会失败；自己旋转后写出的照片方向永远正确，且不依赖 HAL 的 EXIF 行为。
     * 解码旋转放在后台线程，完成后回调切回主线程。
     */
    fun takePicture() {
        val imageCapture = imageCapture ?: run {
            Log.w(TAG, "相机未初始化，无法拍照")
            return
        }

        imageCapture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rotationDegrees = image.imageInfo.rotationDegrees
                    val buffer = image.planes[0].buffer
                    val jpeg = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    image.close()
                    savePhoto(jpeg, rotationDegrees)
                } catch (t: Throwable) {
                    Log.e(TAG, "照片后处理失败", t)
                    notifyError("保存照片失败: ${t.message}")
                } finally {
                    runCatching { image.close() }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "拍照失败", exception)
                notifyError(
                    "拍照失败: code=${exception.imageCaptureError} ${exception.message}"
                        + (exception.cause?.let { " (${it.message})" } ?: "")
                )
            }
        })
    }

    /** 旋转到正立后按设置保存。 */
    private fun savePhoto(jpeg: ByteArray, rotationDegrees: Int) {
        val data = uprightJpeg(jpeg, rotationDegrees)
        if (saveToGallery && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(data)
        } else {
            saveToPrivateFile(data)
        }
    }

    /** rotationDegrees != 0 时解码、旋转、重编码；为 0 直接用原始字节（无损快路径）。 */
    private fun uprightJpeg(jpeg: ByteArray, rotationDegrees: Int): ByteArray {
        if (rotationDegrees == 0) return jpeg

        val source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: throw IllegalStateException("照片解码失败")
        val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        val output = java.io.ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        if (rotated != source) source.recycle()
        rotated.recycle()
        return output.toByteArray()
    }

    private fun saveToPrivateFile(data: ByteArray) {
        val file = createFile()
        file.writeBytes(data)
        Log.i(TAG, "拍照成功: ${file.absolutePath}")
        notifyCaptured(file.absolutePath)
    }

    /**
     * 存入公共相册 Pictures/<应用名>/。API 29+ 的 MediaStore 让本应用写入
     * 公共图片目录不需要任何存储权限，应用内相册通过 OWNER_PACKAGE_NAME 查回。
     */
    private fun saveToMediaStore(data: ByteArray) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${System.currentTimeMillis()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + context.getString(R.string.app_name))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法创建相册条目")

        try {
            resolver.openOutputStream(uri)?.use { it.write(data) }
                ?: throw IllegalStateException("无法写入相册")
            // IS_PENDING=1 的条目其他应用看不到，写完要发布
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            Log.i(TAG, "拍照成功: $uri")
            notifyCaptured(uri.toString())
        } catch (t: Throwable) {
            // 保存失败清掉占位条目，避免相册里留空壳
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun notifyCaptured(path: String) {
        ContextCompat.getMainExecutor(context).execute { listener.onPhotoCaptured(path) }
    }

    private fun notifyError(message: String) {
        ContextCompat.getMainExecutor(context).execute { listener.onCameraError(message) }
    }

    private fun createFile(): java.io.File {
        val directory = context.getExternalFilesDir(null)
            ?: throw IllegalStateException("无法访问外部存储")
        
        directory.mkdirs()
        return java.io.File(directory, "IMG_${System.currentTimeMillis()}.jpg")
    }

    private companion object {
        const val TAG = "MirrorCamera"

        /** 手动旋转后的重编码质量。 */
        const val JPEG_QUALITY = 90
    }
}
