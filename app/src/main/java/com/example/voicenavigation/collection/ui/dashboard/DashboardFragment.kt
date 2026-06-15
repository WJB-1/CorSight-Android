package com.example.voicenavigation.collection.ui.dashboard

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
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
 * 功能：
 * - 查看采样任务列表 + 缩略图网格
 * - 多选模式：勾选照片后批量串行上传（适合弱网）
 * - 单张预览：全屏可滑动
 * - 单张重传 / 补拍替换
 * - 全部上传（低频操作）
 */
@AndroidEntryPoint
class DashboardFragment : Fragment() {

    private val viewModel: DashboardViewModel by viewModels()

    private lateinit var rvTasks: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnSelectMode: Button
    private lateinit var btnUploadSelected: Button
    private lateinit var btnUploadAll: Button
    private lateinit var btnClearDone: Button

    // 多选模式
    private var isSelectionMode = false
    private val selectedPhotoKeys = mutableSetOf<String>() // 格式: "taskId::photoId"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvTasks = view.findViewById(R.id.rvTasks)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        btnSelectMode = view.findViewById(R.id.btnSelectMode)
        btnUploadSelected = view.findViewById(R.id.btnUploadSelected)
        btnUploadAll = view.findViewById(R.id.btnUploadAll)
        btnClearDone = view.findViewById(R.id.btnClearDone)

        rvTasks.layoutManager = LinearLayoutManager(requireContext())

        btnSelectMode.setOnClickListener { toggleSelectionMode() }
        btnUploadSelected.setOnClickListener { uploadSelected() }

        btnUploadAll.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("全部上传")
                .setMessage("确定上传所有待上传的任务？")
                .setPositiveButton("确定") { _, _ -> viewModel.uploadAll() }
                .setNegativeButton("取消", null)
                .show()
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

    // ===== 多选模式 =====

    private fun toggleSelectionMode() {
        isSelectionMode = !isSelectionMode
        selectedPhotoKeys.clear()
        btnSelectMode.text = if (isSelectionMode) "取消" else "选择"
        btnUploadSelected.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
        btnUploadAll.visibility = if (isSelectionMode) View.GONE else View.VISIBLE
        // 刷新
        (rvTasks.adapter as? TaskAdapter)?.notifyDataSetChanged()
    }

    private fun uploadSelected() {
        if (selectedPhotoKeys.isEmpty()) {
            Toast.makeText(requireContext(), "请先选择照片", Toast.LENGTH_SHORT).show()
            return
        }
        val pairs = selectedPhotoKeys.map { key ->
            val (taskId, photoId) = key.split("::")
            taskId to photoId
        }
        viewModel.uploadSelectedPhotos(pairs)
        toggleSelectionMode()
        Toast.makeText(requireContext(), "开始串行上传 ${pairs.size} 张照片", Toast.LENGTH_SHORT).show()
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

            val (statusText, statusColor) = when (task.status) {
                TaskStatus.PENDING -> "待上传" to Color.GRAY
                TaskStatus.UPLOADING -> "上传中" to Color.BLUE
                TaskStatus.SUCCESS -> "已完成" to Color.parseColor("#4CAF50")
                TaskStatus.FAILED -> "失败" to Color.RED
            }
            holder.tvStatus.text = statusText
            holder.tvStatus.setTextColor(statusColor)

            // 照片网格（96dp × 96dp，3 列）
            holder.rvPhotos.layoutManager = GridLayoutManager(requireContext(), 3)
            holder.rvPhotos.adapter = PhotoAdapter(task, task.photos)
            holder.rvPhotos.isNestedScrollingEnabled = false

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
            val cbSelect: CheckBox = root.findViewById(R.id.cbSelect)
            val selectedOverlay: View = root.findViewById(R.id.selectedOverlay)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_photo_grid, parent, false) as FrameLayout
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val photo = photos[position]
            val photoKey = "${task.pointId}::${photo.id}"

            // 缩略图
            try {
                val bitmap = BitmapFactory.decodeFile(photo.filePath)
                holder.ivThumb.setImageBitmap(bitmap)
            } catch (_: Exception) {
                holder.ivThumb.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            holder.tvLabel.text = photo.direction ?: photo.label

            val (badge, badgeColor) = when (photo.uploadStatus) {
                UploadStatus.PENDING -> "⏳" to Color.GRAY
                UploadStatus.UPLOADING -> "↑" to Color.BLUE
                UploadStatus.UPLOADED -> "✓" to Color.parseColor("#4CAF50")
                UploadStatus.FAILED -> "✗" to Color.RED
            }
            holder.tvBadge.text = badge
            holder.tvBadge.setTextColor(badgeColor)

            // 多选模式
            if (isSelectionMode) {
                holder.cbSelect.visibility = View.VISIBLE
                holder.cbSelect.setOnCheckedChangeListener(null) // 先清掉再设，防复用污染
                holder.cbSelect.isChecked = selectedPhotoKeys.contains(photoKey)
                holder.cbSelect.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedPhotoKeys.add(photoKey)
                    else selectedPhotoKeys.remove(photoKey)
                }
                holder.selectedOverlay.visibility =
                    if (selectedPhotoKeys.contains(photoKey)) View.VISIBLE else View.GONE
                holder.root.setOnClickListener {
                    holder.cbSelect.isChecked = !holder.cbSelect.isChecked
                }
            } else {
                holder.cbSelect.visibility = View.GONE
                holder.selectedOverlay.visibility = View.GONE
                holder.cbSelect.setOnCheckedChangeListener(null)
                // 非选择模式：点击弹出操作菜单
                holder.root.setOnClickListener { showPhotoActions(task, photo) }
            }

            // 进度条（上传中时覆盖缩略图）
            val progress = viewModel.photoProgress.value[photo.id]
            if (photo.uploadStatus == UploadStatus.UPLOADING && progress != null) {
                holder.tvBadge.text = "↑${progress}%"
            }
        }

        override fun getItemCount() = photos.size
    }

    // ===== 照片操作 =====

    private fun showPhotoActions(task: CaptureTask, photo: PhotoRecord) {
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        options.add("全屏预览")
        actions.add { showFullscreenPreview(task, photo) }

        if (photo.uploadStatus == UploadStatus.FAILED || photo.uploadStatus == UploadStatus.PENDING) {
            options.add("上传此张")
            actions.add { viewModel.retryPhoto(task.pointId, photo.id) }
        }

        options.add("补拍替换")
        actions.add { startRetake(task.pointId, photo) }

        AlertDialog.Builder(requireContext())
            .setTitle(photo.direction ?: photo.label)
            .setItems(options.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun showFullscreenPreview(task: CaptureTask, clickedPhoto: PhotoRecord) {
        val startIndex = task.photos.indexOfFirst { it.id == clickedPhoto.id }.coerceAtLeast(0)
        val dialog = FullscreenPreviewDialog.newInstance(task.pointId, task.photos, startIndex)

        dialog.onUploadPhoto = { taskId, photo ->
            viewModel.retryPhoto(taskId, photo.id)
            Toast.makeText(requireContext(), "开始上传 ${photo.direction ?: photo.label}", Toast.LENGTH_SHORT).show()
        }
        dialog.onDeletePhoto = { taskId, photoId ->
            viewModel.deletePhoto(taskId, photoId)
            dialog.dismiss()
        }

        dialog.show(parentFragmentManager, "fullscreen_preview")
    }

    private fun startRetake(taskId: String, originalPhoto: PhotoRecord) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.viewPager, RetakeFragment.newInstance(taskId, originalPhoto.id, originalPhoto.bearing))
            .addToBackStack(null)
            .commit()
    }
}
