package com.example.voicenavigation.collection.ui.freemode

import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.voicenavigation.R
import com.example.voicenavigation.collection.data.PhotoRecord
import com.example.voicenavigation.collection.ui.base.BaseCaptureFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * 自由拍照模式。
 *
 * 无方向约束，用户按需拍摄有意义的街景。
 * 继承 [BaseCaptureFragment] 获得相机、传感器、手势、生命周期管理。
 */
@AndroidEntryPoint
class FreeCaptureFragment : BaseCaptureFragment() {

    private val viewModel: FreeCaptureViewModel by viewModels()

    private lateinit var tvPhotoCount: TextView
    private lateinit var btnSaveTask: View

    private val sceneLabels = listOf("天桥", "复杂路口", "斑马线", "公交站台", "隧道/地道", "普通道路", "其他")

    override val layoutResId: Int = R.layout.fragment_free_capture

    override fun onPreviewViewReady(view: View) {
        tvPhotoCount = view.findViewById(R.id.tvPhotoCount)
        btnSaveTask = view.findViewById(R.id.btnSaveTask)
        btnSaveTask.setOnClickListener { saveTask() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.photos.collectLatest { photos ->
                tvPhotoCount.text = "已拍: ${photos.size}"
                btnSaveTask.visibility = if (photos.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    // 自由模式：始终允许拍照
    override fun canTakePhoto(): Boolean = true

    override fun onPhotoTaken(file: File, bearing: Float, fov: Float, zoom: Float, isWide: Boolean) {
        val photo = PhotoRecord(
            filePath = file.absolutePath,
            bearing = bearing,
            fov = fov
        )
        showLabelDialog(photo)
    }

    // ===== 标注对话框 =====

    private fun showLabelDialog(photo: PhotoRecord) {
        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_photo_label, null)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinnerLabel)
        val tvBearing = dialogView.findViewById<TextView>(R.id.tvBearing)
        val etDesc = dialogView.findViewById<EditText>(R.id.etDescription)

        spinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, sceneLabels)
        tvBearing.text = "方位角: ${String.format("%.1f", photo.bearing)}°"

        AlertDialog.Builder(ctx)
            .setTitle("场景标注")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val label = spinner.selectedItem.toString()
                val description = etDesc.text.toString().trim()
                val finalPhoto = photo.copy(
                    label = label,
                    description = description.ifEmpty { label }
                )
                viewModel.addPhoto(finalPhoto)
                Toast.makeText(ctx, "已保存 ($label)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("放弃") { _, _ ->
                File(photo.filePath).delete()
            }
            .setCancelable(false)
            .show()
    }

    // ===== 保存任务 =====

    private fun saveTask() {
        showSaveDialog(
            title = "保存采样点",
            photoCount = viewModel.photoCount,
            onSave = { desc -> viewModel.saveTask(currentLat, currentLng, desc) },
            onDiscard = {
                viewModel.photos.value.forEach { File(it.filePath).delete() }
                viewModel.clearPhotos()
            }
        )
    }
}
