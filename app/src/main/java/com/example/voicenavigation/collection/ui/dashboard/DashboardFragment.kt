package com.example.voicenavigation.collection.ui.dashboard

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.FrameLayout
import com.example.voicenavigation.R
import com.example.voicenavigation.collection.data.CaptureTask
import com.example.voicenavigation.collection.data.PhotoRecord
import com.example.voicenavigation.collection.data.TaskStatus
import com.example.voicenavigation.collection.data.UploadStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 后台管理 Fragment。
 *
 * 功能：查看采集结果（缩略图预览）、补拍（含定位锁）、上传管理（进度条+单图重传）。
 */
@AndroidEntryPoint
class DashboardFragment : Fragment() {

    private val viewModel: DashboardViewModel by viewModels()

    private lateinit var rvTasks: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnUploadAll: Button
    private lateinit var btnClearDone: Button

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvTasks = view.findViewById(R.id.rvTasks)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        btnUploadAll = view.findViewById(R.id.btnUploadAll)
        btnClearDone = view.findViewById(R.id.btnClearDone)

        rvTasks.layoutManager = LinearLayoutManager(requireContext())

        btnUploadAll.setOnClickListener {
            Toast.makeText(requireContext(), "开始上传所有待上传任务...", Toast.LENGTH_SHORT).show()
            viewModel.uploadAll()
        }

        btnClearDone.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("清空已完成任务")
                .setMessage("确定删除所有已上传成功的任务？")
                .setPositiveButton("确定") { _, _ -> viewModel.clearSuccessTasks() }
                .setNegativeButton("取消", null)
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.tasks.collectLatest { tasks ->
                tvEmpty.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
                rvTasks.visibility = if (tasks.isEmpty()) View.GONE else View.VISIBLE
                rvTasks.adapter = TaskAdapter(tasks)
            }
        }
    }

    // ===== Task 适配器 =====

    inner class TaskAdapter(private val tasks: List<CaptureTask>) :
        RecyclerView.Adapter<TaskAdapter.VH>() {

        inner class VH(val root: View) : RecyclerView.ViewHolder(root) {
            val tvPointId: TextView = root.findViewById(R.id.tvPointId)
            val tvMode: TextView = root.findViewById(R.id.tvMode)
            val tvStatus: TextView = root.findViewById(R.id.tvStatus)
            val tvCoords: TextView = root.findViewById(R.id.tvCoords)
            val rvPhotos: RecyclerView = root.findViewById(R.id.rvPhotos)
            val btnDeleteTask: Button = root.findViewById(R.id.btnDeleteTask)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_task_card, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val task = tasks[position]

            holder.tvPointId.text = task.pointId
            holder.tvMode.text = "[${task.mode}]"
            holder.tvCoords.text = "${String.format("%.6f", task.latitude)}, ${String.format("%.6f", task.longitude)}"

            // 状态颜色
            val (statusText, statusColor) = when (task.status) {
                TaskStatus.PENDING -> "待上传" to Color.GRAY
                TaskStatus.UPLOADING -> "上传中" to Color.BLUE
                TaskStatus.SUCCESS -> "已完成" to Color.parseColor("#4CAF50")
                TaskStatus.FAILED -> "失败" to Color.RED
            }
            holder.tvStatus.text = statusText
            holder.tvStatus.setTextColor(statusColor)

            // 照片网格
            holder.rvPhotos.layoutManager = GridLayoutManager(requireContext(), 4)
            holder.rvPhotos.adapter = PhotoAdapter(task, task.photos)

            holder.btnDeleteTask.setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("删除采样点")
                    .setMessage("确定删除 ${task.pointId}？此操作不可恢复。")
                    .setPositiveButton("删除") { _, _ -> viewModel.deleteTask(task.pointId) }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        override fun getItemCount() = tasks.size
    }

    // ===== Photo 适配器 =====

    inner class PhotoAdapter(
        private val task: CaptureTask,
        private val photos: List<PhotoRecord>
    ) : RecyclerView.Adapter<PhotoAdapter.VH>() {

        inner class VH(val root: FrameLayout) : RecyclerView.ViewHolder(root) {
            val ivThumb: ImageView = root.findViewById(R.id.ivThumbnail)
            val tvLabel: TextView = root.findViewById(R.id.tvLabel)
            val tvBadge: TextView = root.findViewById(R.id.tvStatusBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_photo_grid, parent, false) as FrameLayout
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val photo = photos[position]

            // 缩略图
            try {
                val bitmap = BitmapFactory.decodeFile(photo.filePath)
                holder.ivThumb.setImageBitmap(bitmap)
            } catch (_: Exception) {
                holder.ivThumb.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            // 标签：方向或场景类型
            holder.tvLabel.text = photo.direction ?: photo.label

            // 上传状态角标
            val (badge, badgeColor) = when (photo.uploadStatus) {
                UploadStatus.PENDING -> "⏳" to Color.GRAY
                UploadStatus.UPLOADING -> "↑" to Color.BLUE
                UploadStatus.UPLOADED -> "✓" to Color.parseColor("#4CAF50")
                UploadStatus.FAILED -> "✗" to Color.RED
            }
            holder.tvBadge.text = badge
            holder.tvBadge.setTextColor(badgeColor)

            // 点击：预览 / 补拍 / 重传
            holder.root.setOnClickListener { showPhotoActions(task, photo) }
        }

        override fun getItemCount() = photos.size
    }

    // ===== 照片操作对话框 =====

    private fun showPhotoActions(task: CaptureTask, photo: PhotoRecord) {
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        // 预览
        options.add("预览大图")
        actions.add { showPhotoPreview(photo) }

        // 重传（如果失败或待上传）
        if (photo.uploadStatus == UploadStatus.FAILED || photo.uploadStatus == UploadStatus.PENDING) {
            if (task.uploadSessionId != null) {
                options.add("重新上传")
                actions.add { viewModel.retryPhoto(task.pointId, photo.id) }
            }
        }

        // 补拍
        options.add("补拍替换")
        actions.add { startRetake(task.pointId, photo) }

        AlertDialog.Builder(requireContext())
            .setTitle(photo.direction ?: photo.label)
            .setItems(options.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun showPhotoPreview(photo: PhotoRecord) {
        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_photo_preview, null)
        val iv = dialogView.findViewById<ImageView>(R.id.ivFullPreview)
        try {
            val bitmap = BitmapFactory.decodeFile(photo.filePath)
            iv.setImageBitmap(bitmap)
        } catch (_: Exception) {
            iv.setImageResource(android.R.drawable.ic_menu_gallery)
        }
        AlertDialog.Builder(ctx)
            .setTitle("${photo.direction ?: photo.label} — ${String.format("%.1f", photo.bearing)}°")
            .setView(dialogView)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun startRetake(taskId: String, originalPhoto: PhotoRecord) {
        // 启动补拍 Fragment
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, RetakeFragment.newInstance(taskId, originalPhoto.id, originalPhoto.bearing))
            .addToBackStack(null)
            .commit()
    }
}
