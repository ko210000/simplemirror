package com.fei.simplemirror

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
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
    private lateinit var sharedPreferences: SharedPreferences

    /** 提示层当前展示的是哪一类信息；onResume 靠它判断该不该把提示撤掉。 */
    private enum class Panel { NONE, PERMISSION, NO_FRONT_CAMERA, CAMERA_ERROR }

    private var panel = Panel.NONE
    private var isCameraReady = false
    private var isFillLightOn = false

    /** 曝光条的 0 对应的曝光补偿档位（设备范围一般是负数起步）。 */
    private var exposureLowerBound = 0

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
        
        sharedPreferences = getSharedPreferences(SettingsActivity.PREF_NAME, MODE_PRIVATE)
        applySettings()
        
        camera = MirrorCameraController(
            context = this,
            previewView = binding.previewView,
            lifecycleOwner = this,
            listener = this,
        )

        if (hasCameraPermission()) {
            camera.start()
            applySettings()
        } else {
            requestPermission()
        }
        
        // 初始化拍照按钮
        binding.captureButton.setOnClickListener {
            // 拍照时添加缩放动画效果
            binding.captureButton.animate()
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(100)
                .withEndAction {
                    binding.captureButton.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(100)
                        .start()
                }
                .start()
            
            camera.takePicture()
        }
        
        // 相册按钮
        binding.galleryButton.setOnClickListener {
            val intent = Intent(this, PhotoGalleryActivity::class.java)
            startActivity(intent)
            overridePendingTransition(R.anim.slide_in_from_bottom, R.anim.fade_out)
        }
        
        // 设置按钮
        binding.settingsButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
            overridePendingTransition(R.anim.slide_in_from_bottom, R.anim.fade_out)
        }

        // 夜间补光：屏幕拉满最亮当光源 + 相机曝光补偿提亮
        binding.fillLightButton.setOnClickListener { toggleFillLight() }

        // 左侧亮度条：初始位置取系统亮度，拖动即改窗口亮度
        binding.brightnessSlider.progress = systemBrightnessPercent()
        binding.brightnessSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                window.attributes = window.attributes.apply {
                    screenBrightness = progress / 100f
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 右侧曝光条：拖动即改曝光补偿档位
        binding.exposureSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                camera.setExposureCompensationIndex(exposureLowerBound + progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun systemBrightnessPercent(): Int = try {
        val value = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        ((value / 255f).coerceIn(0f, 1f) * 100).toInt()
    } catch (ignored: Exception) {
        50
    }

    /** 相机就绪后按设备支持的曝光范围初始化曝光条；不支持则保持禁用。 */
    private fun syncExposureSlider() {
        val range = camera.exposureCompensationRange()
        if (range == null) {
            binding.exposureSlider.isEnabled = false
            return
        }
        exposureLowerBound = range.first
        binding.exposureSlider.max = range.last - range.first
        binding.exposureSlider.progress =
            (camera.exposureCompensationIndex() - range.first).coerceIn(0, binding.exposureSlider.max)
        binding.exposureSlider.isEnabled = true
    }

    private fun toggleFillLight() {
        isFillLightOn = !isFillLightOn
        binding.fillLightButton.alpha = if (isFillLightOn) 1f else 0.5f

        // 两个滑条同步到补光目标值（setProgress 会触发监听器，随后下面的直接赋值兜底生效）
        binding.brightnessSlider.progress = if (isFillLightOn) 100 else systemBrightnessPercent()
        binding.exposureSlider.progress = if (isFillLightOn) binding.exposureSlider.max else 0

        // 屏幕正对用户，就是镜子场景的补光源；BRIGHTNESS_OVERRIDE 是窗口级亮度，无需系统设置权限
        window.attributes = window.attributes.apply {
            screenBrightness = if (isFillLightOn) {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            } else {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
        camera.setLowLightBoost(isFillLightOn)
    }

    override fun onResume() {
        super.onResume()
        // 设置页盖在上面时本页已 onStop，广播接收器被注销，开关发出的广播收不到，
        // 所以回到本页必须重新读一遍设置（镜像/常亮/存储位置都靠这里兜底）。
        applySettings()
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
        if (this::sharedPreferences.isInitialized && sharedPreferences.getBoolean(SettingsActivity.KEY_KEEP_SCREEN_ON, true)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun applySettings() {
        val mirrorEnabled = if (this::sharedPreferences.isInitialized) {
            sharedPreferences.getBoolean(SettingsActivity.KEY_MIRROR_ENABLED, false)
        } else {
            false
        }
        binding.previewView.scaleX = if (mirrorEnabled) -1f else 1f
        keepScreenOn()

        // 相机构造在首次 applySettings 之后，需要判空
        if (this::camera.isInitialized) {
            val saveToGallery = sharedPreferences.getBoolean(SettingsActivity.KEY_SAVE_TO_GALLERY, false)
            camera.setSaveToGallery(saveToGallery)
        }
    }

    // 处理设置更新广播
    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                SettingsActivity.UPDATE_MIRROR_SETTING -> {
                    val mirrorEnabled = sharedPreferences.getBoolean(SettingsActivity.KEY_MIRROR_ENABLED, false)
                    // 直接修改预览视图的镜像设置
                    binding.previewView.scaleX = if (mirrorEnabled) -1f else 1f
                }
                SettingsActivity.UPDATE_KEEP_SCREEN_ON -> {
                    keepScreenOn()
                }
                SettingsActivity.UPDATE_SAVE_TO_GALLERY -> {
                    val saveToGallery = sharedPreferences.getBoolean(SettingsActivity.KEY_SAVE_TO_GALLERY, false)
                    camera.setSaveToGallery(saveToGallery)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(updateReceiver, IntentFilter().apply {
            addAction(SettingsActivity.UPDATE_MIRROR_SETTING)
            addAction(SettingsActivity.UPDATE_KEEP_SCREEN_ON)
            addAction(SettingsActivity.UPDATE_SAVE_TO_GALLERY)
        }, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(updateReceiver)
    }

    // endregion

    // region MirrorCameraController.Listener

    override fun onFrontCameraUnavailable() {
        binding.captureButton.visibility = View.GONE
        showMessage(
            panel = Panel.NO_FRONT_CAMERA,
            title = R.string.message_title_no_front_camera,
            body = getString(R.string.message_body_no_front_camera),
        )
    }

    override fun onPreviewReady() {
        hideMessage()
        isCameraReady = true
        binding.captureButton.visibility = View.VISIBLE
        syncExposureSlider()
    }

    override fun onCameraError(detail: String) {
        isCameraReady = false
        binding.captureButton.visibility = View.GONE
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

    override fun onPhotoCaptured(filePath: String) {
        Toast.makeText(this, "拍照成功！照片已保存", Toast.LENGTH_SHORT).show()
        // 打开照片查看器
        val intent = Intent(this, PhotoViewerActivity::class.java)
        intent.putExtra(PhotoViewerActivity.PHOTO_PATH_EXTRA, filePath)
        startActivity(intent)
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
