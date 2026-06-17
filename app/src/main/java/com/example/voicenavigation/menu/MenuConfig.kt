package com.example.voicenavigation.menu

import android.content.Context
import android.graphics.Color
import android.util.Log
import com.example.voicenavigation.menu.RingMenuItem
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 从 assets/menu_config.json 加载环形菜单配置。
 *
 * 新增菜单项只需编辑 JSON 文件，不改代码。
 */
@Singleton
class MenuConfig @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "MenuConfig"
        private const val CONFIG_FILE = "menu_config.json"
    }

    private var items: List<RingMenuItem>? = null

    fun getItems(): List<RingMenuItem> {
        items?.let { return it }
        return try {
            val json = context.assets.open(CONFIG_FILE).bufferedReader().readText()
            parseItems(JSONObject(json)).also { items = it }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $CONFIG_FILE", e)
            emptyList()
        }
    }

    private fun parseItems(root: JSONObject): List<RingMenuItem> {
        val array = root.optJSONArray("items") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            parseItem(array.optJSONObject(i) ?: return@mapNotNull null)
        }
    }

    private fun parseItem(obj: JSONObject): RingMenuItem {
        val id = obj.optString("id", "")
        val label = obj.optString("label", "")
        val color = parseColor(obj.optString("color", "#9E9E9E"))
        val command = obj.optString("command", "")

        val childrenArray = obj.optJSONArray("children")
        val children = if (childrenArray != null) {
            (0 until childrenArray.length()).mapNotNull { i ->
                parseItem(childrenArray.optJSONObject(i) ?: return@mapNotNull null)
            }
        } else null

        return RingMenuItem(
            id = id,
            label = label,
            color = color,
            command = command,
            children = children
        )
    }

    private fun parseColor(hex: String): Int {
        return try {
            Color.parseColor(hex)
        } catch (e: Exception) {
            Color.GRAY
        }
    }
}
