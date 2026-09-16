package com.fei.simplemirror

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.fei.simplemirror.camera.MirrorCameraController
import com.fei.simplemirror.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), MirrorCameraController.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var camera: MirrorCameraController

    /** 提示层当前展示的是哪一类信息；onResume 靠它判断该不该把提示撤掉。 */
    private enum class Panel { NONE, PERMISSION, NO_FRONT_CAMERA, CAMERA_ERROR }

    private var panel = Panel.NONE

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                hideMessage()
                camera.start()
            } else if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                // 拒绝过一次但还能再问：给说明，并提供再次申请的入口
                showMessage(
                    panel = Panel.PERMISSION,
                    title = R.string.message_title_permission,
                    body = getString(R.string.message_body_permission),
                    actionText = R.string.action_grant,
                ) { requestPermission() }
            } else {
                // 已经「不再询问」，只能引导用户去系统设置里开
                showPermissionBlockedMessage()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enterFullscreen()
        keepScreenOn()

        camera = MirrorCameraController(
            context = this,
            previewView = binding.previewView,
            lifecycleOwner = this,
            listener = this,
        )

        if (hasCameraPermission()) {
            camera.start()
        } else {
            requestPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚从系统设置里把权限打开再切回来。
        // 只处理「之前卡在权限提示」这一种情况，免得把相机错误等提示误撤掉。
        if (hasCameraPermission() && panel == Panel.PERMISSION) {
            hideMessage()
            camera.start()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 系统权限弹窗、跳到设置页再回来，系统会把状态栏/导航栏重新显示出来，
        // 这里补隐藏一次。首次进入的全屏由 onCreate 负责。
        if (hasFocus) enterFullscreen()
    }

    // region 权限

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPermission() {
        requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun showPermissionBlockedMessage() {
        showMessage(
            panel = Panel.PERMISSION,
            title = R.string.message_title_permission_denied,
            body = getString(R.string.message_body_permission_denied),
            actionText = R.string.action_open_settings,
        ) { openAppSettings() }
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                )
            )
        } catch (e: ActivityNotFoundException) {
            // 极少数精简 ROM 没有应用详情页，退回设置首页
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS))
            } catch (ignored: ActivityNotFoundException) {
                showPermissionBlockedMessage()
            }
        }
    }

    // endregion

    // region 窗口

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            // 从边缘上滑可以临时唤出系统栏，几秒后自动隐藏
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun keepScreenOn() {
        // 比 WakeLock 省事：不需要 WAKE_LOCK 权限，窗口不可见时会自动失效
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // endregion

    // region MirrorCameraController.Listener

    override fun onFrontCameraUnavailable() {
        showMessage(
            panel = Panel.NO_FRONT_CAMERA,
            title = R.string.message_title_no_front_camera,
            body = getString(R.string.message_body_no_front_camera),
        )
    }

    override fun onPreviewReady() {
        hideMessage()
    }

    override fun onCameraError(detail: String) {
        showMessage(
            panel = Panel.CAMERA_ERROR,
            title = R.string.message_title_camera_error,
            body = getString(R.string.message_body_camera_error_format, detail),
            actionText = R.string.action_retry,
        ) {
            hideMessage()
            camera.rebind()
        }
    }

    // endregion

    private fun showMessage(
        panel: Panel,
        @StringRes title: Int,
        body: CharSequence,
        @StringRes actionText: Int? = null,
        onAction: (() -> Unit)? = null,
    ) {
        this.panel = panel
        binding.messageTitle.setText(title)
        binding.messageBody.text = body
        if (actionText != null && onAction != null) {
            binding.messageAction.setText(actionText)
            binding.messageAction.setOnClickListener { onAction() }
            binding.messageAction.visibility = View.VISIBLE
        } else {
            binding.messageAction.setOnClickListener(null)
            binding.messageAction.visibility = View.GONE
        }
        binding.messagePanel.visibility = View.VISIBLE
    }

    private fun hideMessage() {
        panel = Panel.NONE
        binding.messagePanel.visibility = View.GONE
    }
}
