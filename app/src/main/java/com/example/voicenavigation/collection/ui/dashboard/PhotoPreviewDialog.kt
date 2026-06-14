package com.example.voicenavigation.collection.ui.dashboard

import android.app.Dialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.voicenavigation.R
import com.example.voicenavigation.collection.data.PhotoRecord

/**
 * 全屏照片预览对话框。
 * 显示照片 + 元信息（bearing、fov、GPS、上传状态）。
 */
class PhotoPreviewDialog : DialogFragment() {

    companion object {
        private const val ARG_FILE_PATH = "file_path"
        private const val ARG_BEARING = "bearing"
        private const val ARG_FOV = "fov"
        private const val ARG_LABEL = "label"

        fun newInstance(photo: PhotoRecord): PhotoPreviewDialog {
            return PhotoPreviewDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_FILE_PATH, photo.filePath)
                    putFloat(ARG_BEARING, photo.bearing)
                    putFloat(ARG_FOV, photo.fov)
                    putString(ARG_LABEL, photo.direction ?: photo.label)
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val args = requireArguments()

        val filePath = args.getString(ARG_FILE_PATH) ?: ""
        val bearing = args.getFloat(ARG_BEARING)
        val fov = args.getFloat(ARG_FOV)
        val label = args.getString(ARG_LABEL, "")

        val dialogView = layoutInflater.inflate(R.layout.dialog_photo_preview, null)
        val ivPreview = dialogView.findViewById<ImageView>(R.id.ivFullPreview)

        try {
            val bitmap = BitmapFactory.decodeFile(filePath)
            ivPreview?.setImageBitmap(bitmap)
        } catch (_: Exception) {
            // ignore
        }

        return AlertDialog.Builder(ctx)
            .setTitle("$label — ${String.format("%.1f", bearing)}° (FOV: ${String.format("%.0f", fov)}°)")
            .setView(dialogView)
            .setPositiveButton("关闭", null)
            .create()
    }
}
