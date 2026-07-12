package com.example.voicenavigation.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "参观金造"模式专用网络服务。
 *
 * 三个后端接口：
 * 1. GET  /api/annotations?node_type=path   — 预加载路网（可选，启动时 1 次）
 * 2. POST /api/navigation/path-guide       — 传 GPS 坐标，拿指引文字（每 2 秒）
 * 3. POST /api/navigation/vlm-guide        — 拍照校准方向（每 15 秒/用户触发）
 *
 * 调用方通过定时器驱动 path-guide 周期上报。
 */
@Singleton
class TourNavigateService @Inject constructor(
    private val httpClient: OkHttpClient
) {

    companion object {
        private const val TAG = "TourNavigateService"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** path-guide 上报间隔（毫秒） */
        const val PATH_GUIDE_INTERVAL_MS = 2000L

        /** 路网预加载缓存 TTL（30 秒，与后端匹配） */
        const val ANNOTATIONS_CACHE_TTL_MS = 30_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── 路网预加载缓存 ──
    @Volatile private var cachedAnnotations: List<JSONObject>? = null
    @Volatile private var cacheTimestamp: Long = 0L

    // ═══════════════════════════════════════════
    // 1. 路网预加载
    // ═══════════════════════════════════════════

    /**
     * 预加载路网数据（启动时调用一次）。
     * GET /api/annotations?node_type=path
     *
     * 结果缓存在内存中，30 秒内不重复请求。
     */
    fun loadPathNetwork(baseUrl: String, callback: PathNetworkCallback) {
        val now = System.currentTimeMillis()
        val cached = cachedAnnotations
        if (cached != null && (now - cacheTimestamp) < ANNOTATIONS_CACHE_TTL_MS) {
            Log.d(TAG, "Using cached annotations (${cached.size} paths)")
            mainHandler.post { callback.onSuccess(cached) }
            return
        }

        val url = "$baseUrl/api/annotations?node_type=path"
        val request = Request.Builder().url(url).get().build()

        Log.d(TAG, "Loading path network → $url")

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Load path network failed: ${e.message}", e)
                mainHandler.post { callback.onError(e.message ?: "网络错误") }
            }

            override fun onResponse(call: Call, response: Response) {
                val respBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "Load path network error code=${response.code}: $respBody")
                    mainHandler.post { callback.onError("HTTP ${response.code}") }
                    return
                }

                try {
                    val json = JSONObject(respBody)
                    val success = json.optBoolean("success", false)
                    if (!success) {
                        Log.w(TAG, "Load path network not successful: ${json.optString("message")}")
                        mainHandler.post { callback.onError(json.optString("message", "获取路网失败")) }
                        return
                    }

                    val dataArr = json.optJSONArray("data")
                    if (dataArr == null) {
                        mainHandler.post { callback.onSuccess(emptyList()) }
                        return
                    }

                    val list = (0 until dataArr.length()).map { i -> dataArr.getJSONObject(i) }
                    cachedAnnotations = list
                    cacheTimestamp = now
                    Log.d(TAG, "Loaded ${list.size} path annotations")
                    mainHandler.post { callback.onSuccess(list) }
                } catch (e: Exception) {
                    Log.e(TAG, "Parse path network failed", e)
                    mainHandler.post { callback.onError("解析路网数据失败") }
                }
            }
        })
    }

    // ═══════════════════════════════════════════
    // 2. path-guide — 核心定位引导
    // ═══════════════════════════════════════════

    /**
     * 上报当前 GPS 坐标到后端，获取行走指引。
     *
     * POST /api/navigation/path-guide
     * Body: { "lng": 112.96, "lat": 24.10 }
     *
     * 后端返回:
     * {
     *   "success": true,
     *   "data": {
     *     "on_path": true|false,
     *     "path_id": "...",
     *     "path_label": "2-18",
     *     "guidance": "前方约7米，注意大概5米后需要右转上楼梯...",
     *     "next_action": "turn_right" | "turn_left" | "pass_by" | "arrive" | null,
     *     "surface": "gravel" | "cobblestone" | ... | null,
     *     "distance_m": 7,
     *     "waypoint_seq": 1,
     *     "waypoint_description": "...",
     *     "visual_clue": "注意楼梯在前方右侧"
     *   }
     * }
     */
    fun sendPathGuide(
        baseUrl: String,
        lng: Double,
        lat: Double,
        callback: PathGuideCallback
    ) {
        val url = "$baseUrl/api/navigation/path-guide"
        val body = JSONObject().apply {
            put("lng", lng)
            put("lat", lat)
        }

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Path-guide failed: ${e.message}")
                mainHandler.post { callback.onError(e.message ?: "网络错误") }
            }

            override fun onResponse(call: Call, response: Response) {
                val respBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.w(TAG, "Path-guide error code=${response.code}: $respBody")
                    mainHandler.post { callback.onError("HTTP ${response.code}") }
                    return
                }

                try {
                    val json = JSONObject(respBody)
                    val success = json.optBoolean("success", false)
                    if (!success) {
                        Log.w(TAG, "Path-guide not successful: ${json.optString("message")}")
                        mainHandler.post { callback.onError(json.optString("message", "路径引导失败")) }
                        return
                    }

                    val data = json.optJSONObject("data") ?: run {
                        mainHandler.post { callback.onError("返回数据为空") }
                        return
                    }

                    val result = PathGuideResult(
                        onPath = data.optBoolean("on_path", false),
                        pathId = data.optString("path_id", ""),
                        pathLabel = data.optString("path_label", ""),
                        guidance = data.optString("guidance", ""),
                        nextAction = data.optString("next_action", null),
                        surface = data.optString("surface", null),
                        distanceM = data.optInt("distance_m", -1),
                        waypointSeq = data.optInt("waypoint_seq", -1),
                        visualClue = data.optString("visual_clue", null)
                    )
                    Log.d(TAG, "Path-guide: on_path=${result.onPath}, action=${result.nextAction}, dist=${result.distanceM}m")
                    mainHandler.post { callback.onResult(result) }
                } catch (e: Exception) {
                    Log.e(TAG, "Parse path-guide response failed: $respBody", e)
                    mainHandler.post { callback.onError("解析引导数据失败") }
                }
            }
        })
    }

    // ═══════════════════════════════════════════
    // 3. vlm-guide — 拍照校准方向
    // ═══════════════════════════════════════════

    /**
     * 发送照片到后端进行 VLM 视觉分析，校准方向。
     *
     * POST /api/navigation/vlm-guide
     * Body: { "image_base64": "...", "lng": 112.96, "lat": 24.10, "heading": 90 }
     *
     * 后端返回:
     * {
     *   "success": true,
     *   "data": {
     *     "vlm_result": {
     *       "can_see_path": true|false,
     *       "path_direction": "正前方" | "左前方" | ...,
     *       "angle_offset": -30...30,
     *       "surface_match": "鹅卵石" | ...,
     *       "obstacles": [...],
     *       "guidance": "一句汉语导航指引"
     *     },
     *     "path_label": "2-17",
     *     "next_action": "turn_left"
     *   }
     * }
     */
    fun sendVlmGuide(
        baseUrl: String,
        imageBase64: String,
        lng: Double?,
        lat: Double?,
        heading: Float?,
        callback: VlmGuideCallback
    ) {
        val url = "$baseUrl/api/navigation/vlm-guide"
        val body = JSONObject().apply {
            put("image_base64", imageBase64)
            put("mime", "image/jpeg")
            if (lng != null) put("lng", lng)
            if (lat != null) put("lat", lat)
            if (heading != null) put("heading", heading.toDouble())
        }

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
            .build()

        Log.d(TAG, "VLM-guide request → $url")

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "VLM-guide failed: ${e.message}", e)
                mainHandler.post { callback.onError(e.message ?: "网络错误") }
            }

            override fun onResponse(call: Call, response: Response) {
                val respBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "VLM-guide error code=${response.code}: $respBody")
                    mainHandler.post { callback.onError("HTTP ${response.code}") }
                    return
                }

                try {
                    val json = JSONObject(respBody)
                    val success = json.optBoolean("success", false)
                    if (!success) {
                        Log.w(TAG, "VLM-guide not successful: ${json.optString("message")}")
                        mainHandler.post { callback.onError(json.optString("message", "VLM分析失败")) }
                        return
                    }

                    val data = json.optJSONObject("data")
                    val vlm = data?.optJSONObject("vlm_result")
                    val vlmResult = if (vlm != null) VlmResult(
                        canSeePath = vlm.optBoolean("can_see_path", false),
                        pathDirection = vlm.optString("path_direction", ""),
                        angleOffset = vlm.optInt("angle_offset", 0),
                        surfaceMatch = vlm.optString("surface_match", ""),
                        distanceEstimate = vlm.optString("distance_estimate", ""),
                        obstacles = vlm.optJSONArray("obstacles")?.let { arr ->
                            (0 until arr.length()).map { i -> arr.optString(i) ?: "" }.filter { it.isNotEmpty() }
                        } ?: emptyList(),
                        guidance = vlm.optString("guidance", "")
                    ) else null

                    val pathLabel = data?.optString("path_label", null)
                    val nextAction = data?.optString("next_action", null)

                    Log.d(TAG, "VLM-guide: canSeePath=${vlmResult?.canSeePath}, direction=${vlmResult?.pathDirection}")
                    mainHandler.post { callback.onResult(vlmResult, pathLabel, nextAction) }
                } catch (e: Exception) {
                    Log.e(TAG, "Parse VLM-guide response failed: $respBody", e)
                    mainHandler.post { callback.onError("解析VLM结果失败") }
                }
            }
        })
    }

    // ═══════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════

    fun clearAnnotationCache() {
        cachedAnnotations = null
        cacheTimestamp = 0L
    }

    // ═══════════════════════════════════════════
    // 数据类
    // ═══════════════════════════════════════════

    data class PathGuideResult(
        val onPath: Boolean,
        val pathId: String,
        val pathLabel: String,
        val guidance: String,
        val nextAction: String?,
        val surface: String?,
        val distanceM: Int,
        val waypointSeq: Int,
        val visualClue: String?
    )

    data class VlmResult(
        val canSeePath: Boolean,
        val pathDirection: String,
        val angleOffset: Int,
        val surfaceMatch: String,
        val distanceEstimate: String,
        val obstacles: List<String>,
        val guidance: String
    )

    // ═══════════════════════════════════════════
    // 回调接口
    // ═══════════════════════════════════════════

    interface PathNetworkCallback {
        fun onSuccess(annotations: List<JSONObject>)
        fun onError(error: String)
    }

    interface PathGuideCallback {
        fun onResult(result: PathGuideResult)
        fun onError(error: String)
    }

    interface VlmGuideCallback {
        fun onResult(vlmResult: VlmResult?, pathLabel: String?, nextAction: String?)
        fun onError(error: String)
    }
}
