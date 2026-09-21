package com.fei.simplemirror

import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.fei.simplemirror.databinding.ItemPhotoBinding
import java.io.File

class PhotoAdapter(
    private val onPhotoClick: (String) -> Unit,
    private val onPhotoLongClick: (String) -> Unit,
) : RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder>() {

    private var photos = mutableListOf<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PhotoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val photoPath = photos[position]
        holder.bind(photoPath)
    }

    override fun getItemCount() = photos.size

    fun updatePhotos(newPhotos: List<String>) {
        photos = newPhotos.toMutableList()
        notifyDataSetChanged()
    }

    inner class PhotoViewHolder(private val binding: ItemPhotoBinding) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(photoPath: String) {
            binding.root.setOnClickListener { onPhotoClick(photoPath) }
            binding.root.setOnLongClickListener {
                onPhotoLongClick(photoPath)
                true
            }

            if (photoPath.startsWith("content:")) {
                // 公共相册的照片是 MediaStore uri，交给系统解码
                binding.photoImageView.setImageURI(Uri.parse(photoPath))
            } else {
                // 使用 BitmapFactory 加载图片缩略图
                val file = File(photoPath)
                if (file.exists()) {
                    val bitmap = BitmapFactory.decodeFile(photoPath)
                    if (bitmap != null) {
                        binding.photoImageView.setImageBitmap(bitmap)
                    } else {
                        binding.photoImageView.setImageResource(R.drawable.ic_error)
                    }
                } else {
                    binding.photoImageView.setImageResource(R.drawable.ic_error)
                }
            }
        }
    }
}