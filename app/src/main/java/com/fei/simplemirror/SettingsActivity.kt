package com.fei.simplemirror

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.fei.simplemirror.databinding.ActivitySettingsBinding
import kotlinx.coroutines.delay

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enterFullscreen()
        
        // 进入动画
        binding.root.animation = AnimationUtils.loadAnimation(this, R.anim.slide_in_from_bottom)
        
        sharedPreferences = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        
        setupSettings()
        setupClickListeners()
    }

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    private fun setupSettings() {
        // 镜像开关
        binding.mirrorSwitch.isChecked = sharedPreferences.getBoolean(KEY_MIRROR_ENABLED, false)

        // 屏幕常亮开关
        binding.keepScreenOnSwitch.isChecked = sharedPreferences.getBoolean(KEY_KEEP_SCREEN_ON, true)

        // 存入公共相册开关
        binding.saveToGallerySwitch.isChecked = sharedPreferences.getBoolean(KEY_SAVE_TO_GALLERY, false)
    }

    private fun setupClickListeners() {
        binding.backButton.setOnClickListener {
            finish()
        }
        
        binding.mirrorSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(KEY_MIRROR_ENABLED, isChecked).apply()
            // 发送广播通知 MainActivity 更新镜像设置
            sendBroadcast(Intent(UPDATE_MIRROR_SETTING))
        }
        
        binding.keepScreenOnSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(KEY_KEEP_SCREEN_ON, isChecked).apply()
            // 发送广播通知 MainActivity 更新屏幕常亮设置
            sendBroadcast(Intent(UPDATE_KEEP_SCREEN_ON))
        }

        binding.saveToGallerySwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(KEY_SAVE_TO_GALLERY, isChecked).apply()
            // 发送广播通知 MainActivity 更新照片存储位置
            sendBroadcast(Intent(UPDATE_SAVE_TO_GALLERY))
        }
        
        // 返回按钮动画
        binding.backButton.setOnClickListener {
            binding.root.animation = AnimationUtils.loadAnimation(this, R.anim.slide_out_to_bottom)
            finish()
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
        
        // 开关动画
        binding.mirrorSwitch.setOnClickListener {
            binding.mirrorSwitch.animate()
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(100)
                .withEndAction {
                    binding.mirrorSwitch.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(100)
                        .start()
                }
                .start()
        }
        
        binding.keepScreenOnSwitch.setOnClickListener {
            binding.keepScreenOnSwitch.animate()
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(100)
                .withEndAction {
                    binding.keepScreenOnSwitch.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(100)
                        .start()
                }
                .start()
        }

        binding.saveToGallerySwitch.setOnClickListener {
            binding.saveToGallerySwitch.animate()
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(100)
                .withEndAction {
                    binding.saveToGallerySwitch.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(100)
                        .start()
                }
                .start()
        }
    }

    companion object {
        const val PREF_NAME = "simple_mirror_settings"
        const val KEY_MIRROR_ENABLED = "mirror_enabled"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_SAVE_TO_GALLERY = "save_to_gallery"
        const val UPDATE_MIRROR_SETTING = "com.fei.simplemirror.UPDATE_MIRROR_SETTING"
        const val UPDATE_KEEP_SCREEN_ON = "com.fei.simplemirror.UPDATE_KEEP_SCREEN_ON"
        const val UPDATE_SAVE_TO_GALLERY = "com.fei.simplemirror.UPDATE_SAVE_TO_GALLERY"
    }
}