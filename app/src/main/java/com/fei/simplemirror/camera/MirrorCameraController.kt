package com.fei.simplemirror.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

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
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var startRequested = false

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

        try {
            // 重试路径上如果还留着上一次的绑定，bindToLifecycle 会抛 already bound。
            provider.unbindAll()
            val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview)
            observeCameraState(camera)
            listener.onPreviewReady()
        } catch (t: Throwable) {
            Log.e(TAG, "绑定前置摄像头失败", t)
            listener.onCameraError(describe(t))
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

    private companion object {
        const val TAG = "MirrorCamera"
    }
}
