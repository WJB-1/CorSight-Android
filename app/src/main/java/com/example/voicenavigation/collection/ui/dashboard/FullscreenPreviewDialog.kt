package com.example.voicenavigation.collection.ui.dashboard

import android.app.Dialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.voicenavigation.R
import com.example.voicenavigation.collection.data.PhotoRecord

/**
 * 全屏可滑动照片预览对话框。
 *
 * 展示某个 Task 内的所有照片，支持左右滑动切换。
 * 底部可直接上传当前照片或删除。
 */
class FullscreenPreviewDialog : DialogFragment() {

    companion object {
        private const val ARG_TASK_ID = "task_id"
        private const val ARG_START_INDEX = "start_index"

        fun newInstance(taskId: String, photos: List<PhotoRecord>, startIndex: Int = 0): FullscreenPreviewDialog {
            // 存储到静态缓存，避免 Bundle 超限
            cachedPhotos = photos
            return FullscreenPreviewDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TASK_ID, taskId)
                    putInt(ARG_START_INDEX, startIndex)
                }
            }
        }

        // 静态缓存（Dialog 生命周期内有效）
        private var cachedPhotos: List<PhotoRecord> = emptyList()
    }

    private var currentIndex: Int = 0
    private var taskId: String = ""

    var onUploadPhoto: ((String, PhotoRecord) -> Unit)? = null
    var onDeletePhoto: ((String, String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        taskId = arguments?.getString(ARG_TASK_ID) ?: ""
        currentIndex = arguments?.getInt(ARG_START_INDEX, 0) ?: 0
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_fullscreen_preview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val photos = cachedPhotos
        if (photos.isEmpty()) {
            dismiss()
            return
        }

        val viewPager = view.findViewById<ViewPager2>(R.id.viewPager)
        val tvPageIndicator = view.findViewById<TextView>(R.id.tvPageIndicator)
        val tvPhotoMeta = view.findViewById<TextView>(R.id.tvPhotoMeta)
        val btnUpload = view.findViewById<Button>(R.id.btnUploadThis)
        val btnDelete = view.findViewById<Button>(R.id.btnDeleteThis)

        // Adapter
        viewPager.adapter = PhotoPreviewAdapter(photos)
        viewPager.setCurrentItem(currentIndex, false)
        updateUI(photos, currentIndex, tvPageIndicator, tvPhotoMeta)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentIndex = position
                updateUI(photos, position, tvPageIndicator, tvPhotoMeta)
            }
        })

        // 点击空白处关闭
        view.setOnClickListener { dismiss() }
        // ViewPager 区域不关闭
        viewPager.setOnClickListener { /* consume, don't dismiss */ }

        // 上传当前照片
        btnUpload.setOnClickListener {
            val photo = photos.getOrNull(currentIndex) ?: return@setOnClickListener
            onUploadPhoto?.invoke(taskId, photo)
        }

        // 删除当前照片
        btnDelete.setOnClickListener {
            val photo = photos.getOrNull(currentIndex) ?: return@setOnClickListener
            onDeletePhoto?.invoke(taskId, photo.id)
        }
    }

    private fun updateUI(photos: List<PhotoRecord>, position: Int, tvIndicator: TextView, tvMeta: TextView) {
        tvIndicator.text = "${position + 1} / ${photos.size}"

        val photo = photos.getOrNull(position) ?: return
        val label = photo.direction ?: photo.label
        val status = when (photo.uploadStatus.name) {
            "UPLOADED" -> "✓ 已上传"
            "UPLOADING" -> "↑ 上传中"
            "FAILED" -> "✗ 失败"
            else -> "• 待上传"
        }
        tvMeta.text = "$label  ${String.format("%.1f", photo.bearing)}°  $status"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 清理回调引用
        onUploadPhoto = null
        onDeletePhoto = null
    }

    // ===== 内部 Adapter =====

    private class PhotoPreviewAdapter(
        private val photos: List<PhotoRecord>
    ) : RecyclerView.Adapter<PhotoPreviewAdapter.VH>() {

        class VH(val imageView: ImageView) : RecyclerView.ViewHolder(imageView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val iv = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            return VH(iv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val photo = photos[position]
            try {
                // 全屏预览用较大采样目标，但仍避免解码超大原图
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(photo.filePath, opts)
                val screenW = holder.imageView.resources.displayMetrics.widthPixels
                val screenH = holder.imageView.resources.displayMetrics.heightPixels
                val targetPx = maxOf(screenW, screenH)
                opts.inSampleSize = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / targetPx)
                opts.inJustDecodeBounds = false
                val bitmap = BitmapFactory.decodeFile(photo.filePath, opts)
                holder.imageView.setImageBitmap(bitmap)
            } catch (_: Exception) {
                holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery)
            }
        }

        override fun getItemCount() = photos.size
    }
}
