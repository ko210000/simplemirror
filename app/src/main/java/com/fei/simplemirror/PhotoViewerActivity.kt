package com.fei.simplemirror

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.fei.simplemirror.databinding.ActivityPhotoViewerBinding

class PhotoViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enterFullscreen()

        val photoPath = intent.getStringExtra(PHOTO_PATH_EXTRA)
        if (photoPath.isNullOrEmpty()) {
            finish()
            return
        }

        val photoUri = Uri.parse(photoPath)
        binding.photoView.setImageURI(photoUri)
        // 图片加载完成动画
        binding.photoView.animation = AnimationUtils.loadAnimation(this, R.anim.zoom_in)

        binding.backButton.setOnClickListener {
            val intent = Intent()
            setResult(RESULT_OK, intent)
            finish()
            overridePendingTransition(R.anim.fade_in, R.anim.slide_out_to_bottom)
        }
        
        binding.deleteButton.setOnClickListener {
            // 删除按钮点击动画
            binding.deleteButton.animate()
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(100)
                .withEndAction {
                    binding.deleteButton.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(100)
                        .start()
                }
                .start()
            
            deletePhoto(photoUri)
        }
    }

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    private fun deletePhoto(uri: Uri) {
        // content uri（公共相册）走 MediaStore 删除；私有目录是文件路径，
        // ContentResolver 不认，得直接删文件
        val deleted = if (uri.scheme == "content") {
            runCatching { applicationContext.contentResolver.delete(uri, null, null) }
                .getOrDefault(0) > 0
        } else {
            uri.path?.let { java.io.File(it).delete() } ?: false
        }

        if (deleted) {
            finish()
        }
    }

    companion object {
        const val PHOTO_PATH_EXTRA = "photo_path"
    }
}