package com.example.voicenavigation.ui.dialog

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.voicenavigation.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * 行前预览结果对话框。从 MainActivity.showPreviewDialog() 抽出。
 */
object TripPreviewDialog {

    /**
     * 显示行前预览结果对话框。
     *
     * @param context 上下文
     * @param broadcastText 播报文案
     * @param routeSummary 路线概要 JSON
     * @param keyNodes 关键节点 JSON 数组
     * @param onSpeak 点击"朗读"按钮的回调
     */
    fun show(
        context: Context,
        broadcastText: String,
        routeSummary: JSONObject?,
        keyNodes: JSONArray?,
        onSpeak: (String) -> Unit
    ) {
        val dialogView = View.inflate(context, R.layout.dialog_preview_result, null)

        val tvPreviewText: TextView = dialogView.findViewById(R.id.tv_preview_text)
        val tvPreviewSummary: TextView = dialogView.findViewById(R.id.tv_preview_summary)
        val layoutKeyNodes: LinearLayout = dialogView.findViewById(R.id.layout_key_nodes)
        val btnSpeak: Button = dialogView.findViewById(R.id.btn_preview_speak)
        val btnClose: Button = dialogView.findViewById(R.id.btn_preview_close)

        tvPreviewText.text = broadcastText.ifEmpty { "暂无播报文案" }

        val summaryText = if (routeSummary != null) {
            "总距离：${routeSummary.optString("total_distance", "未知")}\n" +
            "预计时间：${routeSummary.optString("total_duration", "未知")}\n" +
            "关键节点数：${routeSummary.optInt("key_node_count", 0)}"
        } else "暂无概要"
        tvPreviewSummary.text = summaryText

        layoutKeyNodes.removeAllViews()
        if (keyNodes != null && keyNodes.length() > 0) {
            for (i in 0 until keyNodes.length()) {
                val node = keyNodes.optJSONObject(i) ?: continue
                val tvNode = TextView(context).apply {
                    textSize = 14f
                    setTextColor(context.getColor(android.R.color.black))
                    setPadding(0, 8, 0, 8)
                    text = buildNodeText(i, node)
                }
                layoutKeyNodes.addView(tvNode)
                if (i < keyNodes.length() - 1) {
                    val divider = View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1
                        )
                        setBackgroundColor(0xFFE0E0E0.toInt())
                    }
                    layoutKeyNodes.addView(divider)
                }
            }
        }

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        btnSpeak.setOnClickListener { onSpeak("行前预览：$broadcastText") }
        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun buildNodeText(index: Int, node: JSONObject): String {
        val sb = StringBuilder()
        sb.append("节点 ${index + 1}：")
        sb.append(node.optString("relative_direction", ""))
        sb.append(node.optString("action", ""))
        if (node.has("assistant_action")) {
            sb.append("（${node.optString("assistant_action")}）")
        }
        val instruction = node.optString("instruction", "")
        if (instruction.isNotEmpty()) {
            sb.append("\n").append(instruction)
        }
        return sb.toString()
    }
}
