package com.fei.simplemirror

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.LayoutAnimationController
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fei.simplemirror.databinding.ActivityPhotoGalleryBinding
import java.io.File

class PhotoGalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoGalleryBinding
    private lateinit var photoAdapter: PhotoAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enterFullscreen()
        
        // 进入动画
        binding.root.animation = AnimationUtils.loadAnimation(this, R.anim.slide_in_from_bottom)
        
        setupRecyclerView()
    }

    override fun onResume() {
        super.onResume()
        // 从查看器删除照片返回后也要刷新列表
        loadPhotos()
    }

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    private fun setupRecyclerView() {
        photoAdapter = PhotoAdapter(
            onPhotoClick = { photoPath ->
                val intent = Intent(this, PhotoViewerActivity::class.java)
                intent.putExtra(PhotoViewerActivity.PHOTO_PATH_EXTRA, photoPath)
                startActivity(intent)
                overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            },
            onPhotoLongClick = { photoPath -> confirmDelete(photoPath) },
        )
        
        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(this@PhotoGalleryActivity, 3)
            adapter = photoAdapter
            // 添加进入动画
            layoutAnimation = LayoutAnimationController(
                AnimationUtils.loadAnimation(this@PhotoGalleryActivity, R.anim.slide_in_from_bottom)
            )
        }
    }

    private fun loadPhotos() {
        val photos = getSavedPhotos()
        photoAdapter.updatePhotos(photos)
        
        if (photos.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.emptyView.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }
    }

    /** 长按照片弹出删除确认。 */
    private fun confirmDelete(photoPath: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_photo)
            .setMessage(R.string.delete_confirm_body)
            .setPositiveButton(R.string.delete_photo) { _, _ -> deletePhoto(photoPath) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 文件路径走 File.delete，公共相册的 content uri 走 MediaStore 删除。 */
    private fun deletePhoto(photoPath: String) {
        val deleted = if (photoPath.startsWith("content:")) {
            runCatching { contentResolver.delete(Uri.parse(photoPath), null, null) }
                .getOrDefault(0) > 0
        } else {
            File(photoPath).delete()
        }

        if (deleted) {
            loadPhotos()
        } else {
            Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun getSavedPhotos(): List<String> {
        // 收集 (时间戳, 路径)，两个来源合并后按时间倒序展示
        val entries = mutableListOf<Pair<Long, String>>()

        // 来源一：应用私有目录
        val photosDir = File(getExternalFilesDir(null), "")
        if (photosDir.exists()) {
            photosDir.listFiles { file -> file.name.endsWith(".jpg") || file.name.endsWith(".png") }
                ?.forEach { entries.add(it.lastModified() to it.absolutePath) }
        }

        // 来源二：存入公共相册的照片（按所属应用过滤，只看本应用保存的）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)
            val selection = "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
            contentResolver.query(collection, projection, selection, arrayOf(packageName), null)
                ?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    while (cursor.moveToNext()) {
                        val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                        entries.add(cursor.getLong(dateColumn) * 1000 to uri.toString())
                    }
                }
        }

        return entries.sortedByDescending { it.first }.map { it.second }
    }

    companion object {
        const val REQUEST_DELETE_PERMISSION = 1001
    }
}