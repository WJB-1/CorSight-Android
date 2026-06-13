package com.example.voicenavigation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.PoiItem
import com.amap.api.services.core.ServiceSettings
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import com.example.voicenavigation.data.AppDatabase
import com.example.voicenavigation.data.SuggestionAdapter
import com.example.voicenavigation.data.VoiceRecord
import com.example.voicenavigation.data.VoiceRecordAdapter
import com.example.voicenavigation.navigation.NavigationManager
import com.example.voicenavigation.network.TripPreviewService
import com.example.voicenavigation.stt.BaiduSpeechManager
import com.example.voicenavigation.stt.BaiduTtsManager
import com.example.voicenavigation.voice.LLMFunctionCaller
import com.example.voicenavigation.voice.VoiceInteractionManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.security.MessageDigest
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONException

class MainActivity : AppCompatActivity(),
    NavigationManager.NavigationCallback,
    PoiSearch.OnPoiSearchListener,
    VoiceInteractionManager.CommandExecutor,
    VoiceInteractionManager.TextInputListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_PERMISSIONS_CODE = 100
        private const val FIXED_ROUTE_ID = "gzdx_stadium"
    }

    private var mMap: AMap? = null
    private lateinit var mapView: MapView
    private lateinit var speechManager: BaiduSpeechManager
    private lateinit var navigationManager: NavigationManager
    private lateinit var appDatabase: AppDatabase
    private var baiduTts: BaiduTtsManager? = null
    private lateinit var handler: Handler

    private lateinit var btnVoiceContainer: FrameLayout
    private lateinit var tvVoiceHint: TextView
    private lateinit var voiceRipple: View

    // 语音助手按钮（Function Calling）
    private lateinit var btnVoiceCommandContainer: FrameLayout
    private lateinit var tvVoiceCommandHint: TextView
    private lateinit var voiceCommandRipple: View

    private lateinit var btnStartNavigation: Button
    private lateinit var btnPreviewRoute: Button
    private lateinit var btnVisionTest: Button
    private lateinit var btnStopTts: Button
    private lateinit var btnMyLocation: ImageButton
    private lateinit var etDestination: EditText
    private lateinit var btnClearSearch: ImageButton
    private lateinit var cardSuggestions: CardView
    private lateinit var rvSuggestions: RecyclerView
    private lateinit var suggestionAdapter: SuggestionAdapter
    private var vibrator: android.os.Vibrator? = null

    private lateinit var layoutNavInfo: LinearLayout
    private lateinit var tvNavDistance: TextView
    private lateinit var tvNavDuration: TextView
    private lateinit var tvNavInstruction: TextView

    private var currentLocation: LatLng? = null
    private var destinationMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var poiSearch: PoiSearch? = null
    private var poiResults: List<PoiItem>? = null
    private var selectedDestLatLng: LatLng? = null
    private var selectedDestName: String? = null
    private var lastSpokenInstruction: String? = null

    private lateinit var bottomNav: BottomNavigationView
    private lateinit var containerPages: FrameLayout
    private lateinit var pageHistoryView: View
    private lateinit var pageSettingsView: View
    private lateinit var bottomControls: View
    private lateinit var searchBarContainer: View
    private lateinit var rvHistory: RecyclerView
    private lateinit var layoutHistoryEmpty: View
    private lateinit var tvHistoryCount: TextView
    private lateinit var tvHistoryDestCount: TextView
    private var historyAdapter: VoiceRecordAdapter? = null
    private lateinit var tripPreviewService: TripPreviewService
    private var isSelectingDestination = false

    // ===== 语音交互（Function Calling）状态 =====
    private lateinit var voiceInteractionManager: VoiceInteractionManager
    private var autoStartNavigationAfterSearch = false
    private var pendingVoiceDestination: String? = null
    private var lastAddress: String? = null
    private var isObstacleRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initAmapSdk()

        initViews()
        initServices()
        requestPermissions()

        mapView = findViewById(R.id.map)
        mapView.onCreate(savedInstanceState)
        mMap = mapView.map
        initMap()
    }

    private fun initAmapSdk() {
        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, true)
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
        ServiceSettings.updatePrivacyShow(this, true, true)
        ServiceSettings.updatePrivacyAgree(this, true)

        if (hasValidAmapKey()) {
            MapsInitializer.setApiKey(BuildConfig.AMAP_API_KEY)
            AMapLocationClient.setApiKey(BuildConfig.AMAP_API_KEY)
            ServiceSettings.getInstance().setApiKey(BuildConfig.AMAP_API_KEY)
            Log.d(TAG, "AMap runtime package=${packageName}, sha1=${getAppSignatureSha1()}")
        } else {
            Log.e(TAG, "AMap API key is missing. Add amap.api.key to local.properties.")
            Toast.makeText(this, "高德Key未配置，定位和搜索不可用", Toast.LENGTH_LONG).show()
        }
    }

    private fun hasValidAmapKey(): Boolean {
        return BuildConfig.AMAP_API_KEY != null && BuildConfig.AMAP_API_KEY.trim().isNotEmpty()
    }

    private fun getAppSignatureSha1(): String {
        return try {
            val packageInfo = packageManager
                .getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            if (packageInfo.signatures.isNullOrEmpty()) {
                return "unknown"
            }
            val digest = MessageDigest.getInstance("SHA1")
            val sha1 = digest.digest(packageInfo.signatures[0].toByteArray())
            val builder = StringBuilder()
            for (i in sha1.indices) {
                if (i > 0) builder.append(":")
                builder.append(String.format("%02X", sha1[i]))
            }
            builder.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read app signature SHA1", e)
            "unknown"
        }
    }

    private fun initViews() {
        // 语音转文字按钮
        btnVoiceContainer = findViewById(R.id.btn_voice_container)
        tvVoiceHint = findViewById(R.id.tv_voice_hint)
        voiceRipple = findViewById(R.id.voice_ripple)

        // 语音助手按钮（Function Calling）
        btnVoiceCommandContainer = findViewById(R.id.btn_voice_command_container)
        tvVoiceCommandHint = findViewById(R.id.tv_voice_command_hint)
        voiceCommandRipple = findViewById(R.id.voice_command_ripple)

        btnStartNavigation = findViewById(R.id.btn_start_navigation)
        btnPreviewRoute = findViewById(R.id.btn_preview_route)
        btnVisionTest = findViewById(R.id.btn_vision_test)
        btnStopTts = findViewById(R.id.btn_stop_tts)
        btnMyLocation = findViewById(R.id.btn_my_location)
        etDestination = findViewById(R.id.et_destination)
        btnClearSearch = findViewById(R.id.btn_clear_search)
        cardSuggestions = findViewById(R.id.card_suggestions)
        rvSuggestions = findViewById(R.id.rv_suggestions)

        layoutNavInfo = findViewById(R.id.layout_nav_info)
        tvNavDistance = findViewById(R.id.tv_nav_distance)
        tvNavDuration = findViewById(R.id.tv_nav_duration)
        tvNavInstruction = findViewById(R.id.tv_nav_instruction)

        bottomNav = findViewById(R.id.bottom_nav)
        containerPages = findViewById(R.id.container_pages)
        pageHistoryView = findViewById(R.id.page_history)
        pageSettingsView = findViewById(R.id.page_settings)
        bottomControls = findViewById(R.id.bottom_controls)
        searchBarContainer = findViewById(R.id.search_bar_container)
        rvHistory = findViewById(R.id.rv_history)
        rvHistory.layoutManager = LinearLayoutManager(this)
        layoutHistoryEmpty = pageHistoryView.findViewById(R.id.layout_history_empty)
        tvHistoryCount = pageHistoryView.findViewById(R.id.tv_history_count)
        tvHistoryDestCount = pageHistoryView.findViewById(R.id.tv_history_dest_count)

        rvSuggestions.layoutManager = LinearLayoutManager(this)
        suggestionAdapter = SuggestionAdapter(ArrayList())
        suggestionAdapter.setOnItemClickListener { item, _ ->
            isSelectingDestination = true
            val point = item.latLonPoint
            val latLng = LatLng(point.latitude, point.longitude)
            setDestination(latLng, item.title)
            hideSuggestions()
            hideKeyboard()
            isSelectingDestination = false
        }
        rvSuggestions.adapter = suggestionAdapter

        bottomNav.setOnItemSelectedListener { item ->
            val id = item.itemId
            if (id == R.id.nav_tab_nav) {
                switchTab(0)
            } else if (id == R.id.nav_tab_history) {
                switchTab(1)
            } else if (id == R.id.nav_tab_settings) {
                switchTab(2)
            }
            true
        }

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator?
        setupVoiceButton()
        setupVoiceCommandButton()
        setupSearchBar()

        btnStartNavigation.setOnClickListener { toggleNavigation() }
        btnPreviewRoute.setOnClickListener { sendTripPreview() }
        btnVisionTest.setOnClickListener {
            startActivity(Intent(this, VisionTestActivity::class.java))
        }
        btnMyLocation.setOnClickListener { locateMe() }
        btnStopTts.setOnClickListener {
            baiduTts?.stopPlayback()
            btnStopTts.visibility = View.GONE
        }
    }

    /**
     * 语音转文字按钮（蓝色「按住说话」）
     * 按下 → 语音识别 → 结果填入搜索框。
     * 松开后延迟 150ms 再发送 ASR_STOP，防止最后一个字被截断。
     */
    private fun setupVoiceButton() {
        btnVoiceContainer.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!checkAudioPermission()) {
                        Toast.makeText(this, R.string.permission_audio_denied, Toast.LENGTH_SHORT).show()
                        requestPermissions()
                        return@setOnTouchListener true
                    }
                    vibrate(50)
                    tvVoiceHint.text = "松开结束"
                    voiceRipple.visibility = View.VISIBLE
                    voiceInteractionManager.startListening(VoiceInteractionManager.Mode.TEXT_INPUT)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tvVoiceHint.text = "识别中..."
                    voiceRipple.visibility = View.GONE
                    // 立即停止（不延迟），百度引擎用 VAD Endpoint Timeout 自行采集尾音
                    voiceInteractionManager.stopListening()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 语音助手按钮（橙色「语音助手」）
     * 按下 → 语音识别 → Function Calling 执行指令。
     */
    private fun setupVoiceCommandButton() {
        btnVoiceCommandContainer.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!checkAudioPermission()) {
                        Toast.makeText(this, R.string.permission_audio_denied, Toast.LENGTH_SHORT).show()
                        requestPermissions()
                        return@setOnTouchListener true
                    }
                    vibrate(50)
                    tvVoiceCommandHint.text = "正在识别..."
                    voiceCommandRipple.visibility = View.VISIBLE
                    Toast.makeText(this, "开始收听，松开后执行", Toast.LENGTH_SHORT).show()
                    voiceInteractionManager.startListening(VoiceInteractionManager.Mode.COMMAND)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tvVoiceCommandHint.text = "识别中..."
                    voiceCommandRipple.visibility = View.GONE
                    voiceInteractionManager.stopListening()
                    true
                }
                else -> false
            }
        }
    }

    private fun vibrate(ms: Long) {
        vibrator?.let { v ->
            if (v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(ms)
                }
            }
        }
    }

    private fun setupSearchBar() {
        etDestination.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isSelectingDestination) return
                val text = s.toString().trim()
                btnClearSearch.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                if (text.length >= 2) {
                    searchDestination(text)
                } else {
                    hideSuggestions()
                }
            }
        })

        btnClearSearch.setOnClickListener {
            etDestination.setText("")
            hideSuggestions()
        }

        etDestination.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                val keyword = etDestination.text.toString().trim()
                if (keyword.isNotEmpty()) searchDestination(keyword)
                return@setOnEditorActionListener true
            }
            false
        }
    }

    private fun switchTab(index: Int) {
        if (index == 0) {
            containerPages.visibility = View.GONE
            bottomControls.visibility = View.VISIBLE
            searchBarContainer.visibility = View.VISIBLE
            btnMyLocation.visibility = View.VISIBLE
        } else {
            containerPages.visibility = View.VISIBLE
            bottomControls.visibility = View.GONE
            searchBarContainer.visibility = View.GONE
            btnMyLocation.visibility = View.GONE
            hideSuggestions()
            pageHistoryView.visibility = if (index == 1) View.VISIBLE else View.GONE
            pageSettingsView.visibility = if (index == 2) View.VISIBLE else View.GONE
            if (index == 1) loadHistory()
            else if (index == 2) loadSettings()
        }
    }

    private fun hideSuggestions() {
        cardSuggestions.visibility = View.GONE
    }

    private fun showSuggestions(items: List<PoiItem>?) {
        if (items.isNullOrEmpty()) {
            hideSuggestions()
            return
        }
        suggestionAdapter.updateData(items)
        cardSuggestions.visibility = View.VISIBLE
    }

    private fun loadHistory() {
        Thread {
            try {
                val records = appDatabase.voiceRecordDao().getAllRecords()
                val totalCount = appDatabase.voiceRecordDao().getCount()
                var destCount = 0
                if (records != null) {
                    for (record in records) {
                        if (record.destination != null && record.destination.isNotEmpty()) {
                            destCount++
                        }
                    }
                }
                val finalDestCount = destCount
                runOnUiThread {
                    tvHistoryCount.text = totalCount.toString()
                    tvHistoryDestCount.text = finalDestCount.toString()
                    if (records.isNullOrEmpty()) {
                        layoutHistoryEmpty.visibility = View.VISIBLE
                        rvHistory.visibility = View.GONE
                    } else {
                        layoutHistoryEmpty.visibility = View.GONE
                        rvHistory.visibility = View.VISIBLE
                        if (historyAdapter == null) {
                            historyAdapter = VoiceRecordAdapter(records)
                            setupHistoryAdapterListener()
                            rvHistory.adapter = historyAdapter
                        } else {
                            historyAdapter!!.updateData(records)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load history", e)
            }
        }.start()
    }

    private fun setupHistoryAdapterListener() {
        historyAdapter?.setOnItemActionListener(object : VoiceRecordAdapter.OnItemActionListener {
            override fun onPlay(record: VoiceRecord, position: Int) {
                baiduTts?.speak(record.content)
            }

            override fun onDelete(record: VoiceRecord, position: Int) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("删除记录")
                    .setMessage("确定要删除这条历史记录吗？")
                    .setPositiveButton("删除") { _, _ ->
                        Thread {
                            appDatabase.voiceRecordDao().deleteById(record.id)
                            runOnUiThread {
                                historyAdapter?.removeItem(position)
                                loadHistory()
                                Toast.makeText(this@MainActivity, "已删除", Toast.LENGTH_SHORT).show()
                            }
                        }.start()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        })
    }

    private fun loadSettings() {
        val tvAmapKey: TextView = findViewById(R.id.tv_amap_key)
        tvAmapKey.text = BuildConfig.AMAP_API_KEY

        val etServerUrl: EditText = pageSettingsView.findViewById(R.id.et_server_url)
        val btnSaveUrl: Button = pageSettingsView.findViewById(R.id.btn_save_url)
        val etDetectionServerUrl: EditText = pageSettingsView.findViewById(R.id.et_detection_server_url)
        val btnSaveDetectionUrl: Button = pageSettingsView.findViewById(R.id.btn_save_detection_url)

        val prefs: SharedPreferences = AppConfig.prefs(this)
        val savedUrl = AppConfig.normalizeBaseUrl(
            prefs.getString(AppConfig.KEY_PREVIEW_SERVER_BASE_URL, TripPreviewService.DEFAULT_BASE_URL)
        )
        val savedDetectionUrl = prefs.getString(AppConfig.KEY_DETECTION_SERVER_BASE_URL, "")
        etServerUrl.setText(savedUrl)
        etDetectionServerUrl.setText(savedDetectionUrl)

        btnSaveUrl.setOnClickListener {
            val url = AppConfig.normalizeBaseUrl(etServerUrl.text.toString())
            if (url.isEmpty()) {
                Toast.makeText(this, "请输入地图服务地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString(AppConfig.KEY_PREVIEW_SERVER_BASE_URL, url).apply()
            tripPreviewService.setBaseUrl(url)
            Toast.makeText(this, "地图服务地址已保存", Toast.LENGTH_SHORT).show()
        }

        btnSaveDetectionUrl.setOnClickListener {
            val url = AppConfig.normalizeBaseUrl(etDetectionServerUrl.text.toString())
            if (url.isEmpty()) {
                Toast.makeText(this, "请输入检测服务地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString(AppConfig.KEY_DETECTION_SERVER_BASE_URL, url).apply()
            Toast.makeText(this, "检测服务地址已保存", Toast.LENGTH_SHORT).show()
        }

        val btnResetUrl: Button = pageSettingsView.findViewById(R.id.btn_reset_url)
        btnResetUrl.setOnClickListener {
            val defaultUrl = TripPreviewService.DEFAULT_BASE_URL
            prefs.edit().putString(AppConfig.KEY_PREVIEW_SERVER_BASE_URL, defaultUrl).apply()
            etServerUrl.setText(defaultUrl)
            tripPreviewService.setBaseUrl(defaultUrl)
            Toast.makeText(this, "已恢复默认地址", Toast.LENGTH_SHORT).show()
        }

        val switchExternal: SwitchCompat = pageSettingsView.findViewById(R.id.switch_use_external_device)
        val useExternal = prefs.getBoolean("use_external_device", false)
        switchExternal.isChecked = useExternal
        switchExternal.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("use_external_device", isChecked).apply()
            Toast.makeText(
                this,
                if (isChecked) "已开启外部设备优先" else "已关闭外部设备优先",
                Toast.LENGTH_SHORT
            ).show()
        }

        // ===== LLM Function Calling 配置 =====
        val etLlmBaseUrl: EditText = pageSettingsView.findViewById(R.id.et_llm_base_url)
        val etLlmApiKey: EditText = pageSettingsView.findViewById(R.id.et_llm_api_key)
        val etLlmModel: EditText = pageSettingsView.findViewById(R.id.et_llm_model)
        val btnSaveLlm: Button = pageSettingsView.findViewById(R.id.btn_save_llm)
        val tvLlmStatus: TextView = pageSettingsView.findViewById(R.id.tv_llm_status)

        val savedLlmUrl = AppConfig.normalizeBaseUrl(
            prefs.getString(AppConfig.KEY_LLM_BASE_URL, "")
        )
        val savedLlmKey = prefs.getString(AppConfig.KEY_LLM_API_KEY, "")
        val savedLlmModel = prefs.getString(AppConfig.KEY_LLM_MODEL, "deepseek-chat")
        etLlmBaseUrl.setText(savedLlmUrl)
        etLlmApiKey.setText(savedLlmKey)
        etLlmModel.setText(savedLlmModel)

        // 显示 LLM 配置状态
        var llmStatus = "状态："
        llmStatus += if (!savedLlmUrl.isNullOrEmpty() && !savedLlmKey.isNullOrEmpty()) {
            "已配置（本地不匹配时自动调用云端）"
        } else if (!savedLlmUrl.isNullOrEmpty() || !savedLlmKey.isNullOrEmpty()) {
            "配置不完整"
        } else {
            "未配置（仅用本地关键词匹配）"
        }
        tvLlmStatus.text = llmStatus

        btnSaveLlm.setOnClickListener {
            val url = AppConfig.normalizeBaseUrl(etLlmBaseUrl.text.toString())
            val key = etLlmApiKey.text.toString().trim()
            val model = etLlmModel.text.toString().trim()
            prefs.edit()
                .putString(AppConfig.KEY_LLM_BASE_URL, url)
                .putString(AppConfig.KEY_LLM_API_KEY, key)
                .putString(AppConfig.KEY_LLM_MODEL, model.ifEmpty { "deepseek-chat" })
                .apply()
            var statusText = "状态："
            statusText += if (url.isNotEmpty() && key.isNotEmpty()) {
                "已配置（本地不匹配时自动调用云端）"
            } else {
                if (url.isNotEmpty() || key.isNotEmpty()) "配置不完整" else "未配置（仅用本地关键词匹配）"
            }
            tvLlmStatus.text = statusText
            Toast.makeText(this, "LLM 配置已保存", Toast.LENGTH_SHORT).show()
        }

        val btnDataCollection: Button = pageSettingsView.findViewById(R.id.btn_data_collection)
        btnDataCollection.setOnClickListener {
            startActivity(Intent(this, com.example.voicenavigation.collection.DataCollectionActivity::class.java))
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        if (imm != null && currentFocus != null) {
            imm.hideSoftInputFromWindow(currentFocus!!.windowToken, 0)
        }
    }

    private fun initServices() {
        // 唯一的语音识别实例，供两种模式共用
        speechManager = BaiduSpeechManager(this)

        navigationManager = NavigationManager(this)
        navigationManager.setNavigationCallback(this)
        appDatabase = AppDatabase.getInstance(this)
        handler = Handler(Looper.getMainLooper())

        val prefs = AppConfig.prefs(this)
        val savedUrl = AppConfig.normalizeBaseUrl(
            prefs.getString(AppConfig.KEY_PREVIEW_SERVER_BASE_URL, TripPreviewService.DEFAULT_BASE_URL)
        )
        tripPreviewService = TripPreviewService(savedUrl)
        initTts()

        // LLM Function Calling 客户端（云端兜底，需要网络）
        val llmCaller = LLMFunctionCaller(this)

        // 语音交互管理器（双模式：TEXT_INPUT + COMMAND），共用同一个 speechManager
        voiceInteractionManager = VoiceInteractionManager(this, speechManager, baiduTts, llmCaller)
        voiceInteractionManager.setTextInputListener(this)      // 蓝色按钮的 STT → 搜索框
        voiceInteractionManager.setCommandExecutor(this)        // 橙色按钮的 Function Calling
        voiceInteractionManager.setVoiceEventListener(object : VoiceInteractionManager.VoiceEventListener {
            override fun onListeningStarted() {}
            override fun onListeningStopped() {}
            override fun onPartialResultReceived(text: String?) {}
            override fun onPipelineStage(stage: String?) {
                // 实时更新橙色按钮文字，显示流水线进度
                runOnUiThread { tvVoiceCommandHint.text = stage }
            }
        })
    }

    private fun initTts() {
        baiduTts = BaiduTtsManager(
            this,
            getString(R.string.baidu_speech_api_key),
            getString(R.string.baidu_speech_secret_key)
        )
        baiduTts?.setCallback(object : BaiduTtsManager.TtsCallback {
            override fun onTtsReady() { Log.d(TAG, "TTS ready") }
            override fun onTtsError(error: String?) { Log.e(TAG, "TTS error: $error") }
        })
        baiduTts?.init()
    }

    private fun speak(text: String?) {
        if (text.isNullOrEmpty() || text == lastSpokenInstruction) return
        lastSpokenInstruction = text
        baiduTts?.let {
            it.speak(text)
            showStopTtsButton()
        }
    }

    private fun speakForce(text: String?) {
        if (text.isNullOrEmpty()) return
        baiduTts?.let {
            it.speak(text)
            showStopTtsButton()
        }
    }

    private fun showStopTtsButton() {
        runOnUiThread {
            btnStopTts.visibility = View.VISIBLE
        }
    }

    private fun initMap() {
        val map = mMap ?: return

        val myLocationStyle = MyLocationStyle()
        myLocationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
        myLocationStyle.interval(2000)
        map.setMyLocationStyle(myLocationStyle)
        map.uiSettings.isMyLocationButtonEnabled = false
        enableMapLocation()

        map.setOnMyLocationChangeListener { location ->
            if (location == null) return@setOnMyLocationChangeListener
            val shouldMoveCamera = currentLocation == null
            currentLocation = LatLng(location.latitude, location.longitude)
            if (shouldMoveCamera) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLocation, 15f))
            }
        }

        map.setOnMapClickListener { latLng ->
            if (navigationManager.isNavigating) return@setOnMapClickListener
            setDestination(latLng, latLng.latitude.toString() + ", " + latLng.longitude)
            etDestination.setText("")
            etDestination.hint = "已在地图上选点"
        }
    }

    private fun enableMapLocation() {
        if (mMap != null && checkLocationPermission() && hasValidAmapKey()) {
            try {
                mMap!!.isMyLocationEnabled = true
            } catch (e: SecurityException) {
                Log.e(TAG, "Enable map location failed", e)
            }
        }
    }

    private fun locateMe() {
        if (!hasValidAmapKey()) {
            Toast.makeText(this, "高德Key未配置：请在 local.properties 添加 amap.api.key", Toast.LENGTH_LONG).show()
            return
        }
        if (!checkLocationPermission()) {
            requestPermissions()
            Toast.makeText(this, R.string.permission_location_denied, Toast.LENGTH_SHORT).show()
            return
        }
        enableMapLocation()
        navigationManager.requestCurrentLocation()
        if (mMap != null && currentLocation != null) {
            mMap!!.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLocation, 16f))
        } else {
            Toast.makeText(this, "正在获取当前位置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (!checkLocationPermission()) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33
            && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(),
                REQUEST_PERMISSIONS_CODE
            )
        }
        return permissions.isEmpty()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            if (checkLocationPermission() && hasValidAmapKey()) {
                enableMapLocation()
                navigationManager.requestCurrentLocation()
            }
            if (!checkAudioPermission() || !checkLocationPermission()) {
                Toast.makeText(this, "部分权限未授予，相关功能可能不可用", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun checkLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun searchDestination(keyword: String) {
        if (!hasValidAmapKey()) {
            hideSuggestions()
            Toast.makeText(this, "高德Key未配置，无法搜索地点", Toast.LENGTH_SHORT).show()
            return
        }

        val query = PoiSearch.Query(keyword, "", "")
        query.pageSize = 10
        query.pageNum = 0
        query.isCityLimit = false

        try {
            if (poiSearch == null) {
                poiSearch = PoiSearch(this, query)
                poiSearch?.setOnPoiSearchListener(this)
            } else {
                poiSearch?.setQuery(query)
            }
            poiSearch?.searchPOIAsyn()
        } catch (e: Exception) {
            Log.e(TAG, "Search failed", e)
            Toast.makeText(this, "搜索失败，请稍后重试", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPoiSearched(poiResult: PoiResult?, rCode: Int) {
        if (rCode == 1000 && poiResult != null) {
            poiResults = poiResult.pois
            if (poiResults.isNullOrEmpty()) {
                hideSuggestions()
                if (autoStartNavigationAfterSearch) {
                    speakForce("未找到目的地，请换个名称试试")
                    autoStartNavigationAfterSearch = false
                    pendingVoiceDestination = null
                } else {
                    Toast.makeText(this, "未找到匹配地点", Toast.LENGTH_SHORT).show()
                }
            } else {
                // 语音导航模式：自动选择第一个结果，不弹搜索列表
                if (autoStartNavigationAfterSearch) {
                    autoStartNavigationAfterSearch = false
                    hideSuggestions()  // 语音模式不弹列表
                    etDestination.setText("")  // 清空搜索框残留

                    val firstItem = poiResults!![0]
                    val point = firstItem.latLonPoint
                    val latLng = LatLng(point.latitude, point.longitude)
                    // 设置 isSelectingDestination 防止 setDestination 中的
                    // etDestination.setText() 触发 TextWatcher 再次发起 POI 搜索
                    isSelectingDestination = true
                    setDestination(latLng, firstItem.title)
                    isSelectingDestination = false
                    saveVoiceRecord(firstItem.title)

                    // 播报 + Toast 确认目的地
                    val confirmMsg = "为您找到${firstItem.title}，开始导航"
                    voiceInteractionManager.speakAndToast(confirmMsg)

                    // TTS 播报队列自动顺序播放，无需延迟
                    if (currentLocation != null) {
                        layoutNavInfo.visibility = View.VISIBLE
                        navigationManager.planRoute(currentLocation, selectedDestLatLng, selectedDestName)
                    } else {
                        locateMe()
                        speakForce("正在获取当前位置，请稍后")
                    }
                } else {
                    showSuggestions(poiResults)
                }
            }
        } else {
            hideSuggestions()
            if (autoStartNavigationAfterSearch) {
                speakForce("地点搜索失败，错误码：$rCode")
                autoStartNavigationAfterSearch = false
                pendingVoiceDestination = null
            } else {
                Toast.makeText(this, "地点搜索失败，错误码：$rCode", Toast.LENGTH_SHORT).show()
            }
            Log.e(TAG, "POI search failed, rCode=$rCode")
        }
    }

    override fun onPoiItemSearched(poiItem: PoiItem?, rCode: Int) {}

    private fun setDestination(latLng: LatLng, name: String?) {
        selectedDestLatLng = latLng
        selectedDestName = name
        addDestinationMarker(latLng)
        mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
        etDestination.setText(name)
        etDestination.setSelection(name?.length ?: 0)
    }

    private fun toggleNavigation() {
        if (!checkLocationPermission()) {
            requestPermissions()
            Toast.makeText(this, R.string.permission_location_denied, Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasValidAmapKey()) {
            Toast.makeText(this, "高德Key未配置，无法使用导航", Toast.LENGTH_SHORT).show()
            return
        }
        if (!::navigationManager.isInitialized) {
            Toast.makeText(this, "导航服务未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        if (navigationManager.isNavigating) {
            navigationManager.stopNavigation()
            btnStartNavigation.setText(R.string.start_navigation)
            clearRouteDisplay()
            return
        }
        if (selectedDestLatLng == null) {
            Toast.makeText(this, "请先选择目的地", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentLocation == null) {
            locateMe()
            Toast.makeText(this, "正在获取当前位置，请稍后再开始导航", Toast.LENGTH_SHORT).show()
            return
        }
        layoutNavInfo.visibility = View.VISIBLE
        saveVoiceRecord(selectedDestName)
        navigationManager.planRoute(currentLocation, selectedDestLatLng, selectedDestName)
    }

    private fun sendTripPreview() {
        val previewBaseUrl = AppConfig.normalizeBaseUrl(
            AppConfig.prefs(this).getString(AppConfig.KEY_PREVIEW_SERVER_BASE_URL, TripPreviewService.DEFAULT_BASE_URL)
        )
        if (previewBaseUrl.isEmpty()) {
            Toast.makeText(this, "请先在设置中填写后端服务地址", Toast.LENGTH_SHORT).show()
            return
        }
        tripPreviewService.setBaseUrl(previewBaseUrl)

        val callback = object : TripPreviewService.PreviewCallback {
            override fun onSuccess(response: String?) {
                parseAndShowPreviewResult(response)
            }

            override fun onError(error: String?) {
                Toast.makeText(this@MainActivity, "行前预览失败：$error", Toast.LENGTH_LONG).show()
            }
        }

        if (selectedDestLatLng != null && currentLocation != null) {
            // 有目的地：使用标准路线预览（高德规划）
            tripPreviewService.sendPreviewRequest(
                currentLocation!!.latitude, currentLocation!!.longitude,
                selectedDestLatLng!!.latitude, selectedDestLatLng!!.longitude,
                callback
            )
        } else {
            // 没有目的地：使用固定路线预览（跳过高德，直接用预设采样点路线）
            Toast.makeText(this, "使用固定路线预览（广大→体育场）", Toast.LENGTH_SHORT).show()
            tripPreviewService.sendFixedPreviewRequest(FIXED_ROUTE_ID, callback)
        }
    }

    private fun parseAndShowPreviewResult(responseJson: String?) {
        try {
            val root = JSONObject(responseJson)
            if (!root.optBoolean("success", false)) {
                Toast.makeText(this, "行前预览返回失败", Toast.LENGTH_SHORT).show()
                return
            }
            val data = root.optJSONObject("data")
            if (data == null) {
                Toast.makeText(this, "行前预览数据为空", Toast.LENGTH_SHORT).show()
                return
            }
            val broadcastText = data.optString("text", "")
            val routeSummary = data.optJSONObject("route_summary")
            val keyNodes = data.optJSONArray("key_nodes")
            if (broadcastText.isNotEmpty()) {
                speakForce("行前预览：$broadcastText")
            }
            showPreviewDialog(broadcastText, routeSummary, keyNodes)
        } catch (e: JSONException) {
            Log.e(TAG, "Parse preview failed", e)
            Toast.makeText(this, "行前预览解析失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPreviewDialog(
        broadcastText: String,
        routeSummary: JSONObject?,
        keyNodes: JSONArray?
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_preview_result, null)
        val tvPreviewText: TextView = dialogView.findViewById(R.id.tv_preview_text)
        val tvPreviewSummary: TextView = dialogView.findViewById(R.id.tv_preview_summary)
        val layoutKeyNodes: LinearLayout = dialogView.findViewById(R.id.layout_key_nodes)
        val btnSpeak: Button = dialogView.findViewById(R.id.btn_preview_speak)
        val btnClose: Button = dialogView.findViewById(R.id.btn_preview_close)

        tvPreviewText.text = if (broadcastText.isEmpty()) "暂无播报文案" else broadcastText
        var summaryText = ""
        if (routeSummary != null) {
            summaryText = "总距离：" + routeSummary.optString("total_distance", "未知") +
                    "\n预计时间：" + routeSummary.optString("total_duration", "未知") +
                    "\n关键节点数：" + routeSummary.optInt("key_node_count", 0)
        }
        tvPreviewSummary.text = if (summaryText.isEmpty()) "暂无概要" else summaryText

        layoutKeyNodes.removeAllViews()
        if (keyNodes != null && keyNodes.length() > 0) {
            for (i in 0 until keyNodes.length()) {
                val node = keyNodes.optJSONObject(i) ?: continue
                val tvNode = TextView(this)
                tvNode.textSize = 14f
                tvNode.setTextColor(resources.getColor(android.R.color.black))
                tvNode.setPadding(0, 8, 0, 8)
                val sb = StringBuilder()
                sb.append("节点 ").append(i + 1).append("：")
                sb.append(node.optString("relative_direction", ""))
                sb.append(node.optString("action", ""))
                if (node.has("assistant_action")) {
                    sb.append("（").append(node.optString("assistant_action")).append("）")
                }
                val instruction = node.optString("instruction", "")
                if (instruction.isNotEmpty()) {
                    sb.append("\n").append(instruction)
                }
                tvNode.text = sb.toString()
                layoutKeyNodes.addView(tvNode)
                if (i < keyNodes.length() - 1) {
                    val divider = View(this)
                    divider.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                    divider.setBackgroundColor(0xFFE0E0E0.toInt())
                    layoutKeyNodes.addView(divider)
                }
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        btnSpeak.setOnClickListener {
            if (broadcastText.isNotEmpty()) speakForce("行前预览：$broadcastText")
        }
        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun drawRoute(points: List<LatLng>?) {
        if (mMap == null || points.isNullOrEmpty()) return
        routePolyline?.remove()
        routePolyline = null
        val options = PolylineOptions().addAll(points).color(0xFF3B8EFF.toInt()).width(12f)
        routePolyline = mMap!!.addPolyline(options)
    }

    private fun clearRouteDisplay() {
        routePolyline?.remove()
        routePolyline = null
        layoutNavInfo.visibility = View.GONE
        clearMarkers()
    }

    private fun addDestinationMarker(latLng: LatLng) {
        if (mMap == null) return
        destinationMarker?.remove()
        destinationMarker = mMap!!.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("目的地")
                .snippet(selectedDestName)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
        )
        destinationMarker?.showInfoWindow()
    }

    private fun clearMarkers() {
        destinationMarker?.remove()
        destinationMarker = null
    }

    private fun saveVoiceRecord(content: String?) {
        if (content == null) return
        Thread {
            try {
                val record = VoiceRecord()
                record.content = content
                record.filePath = ""
                record.destination = etDestination.text.toString()
                record.timestamp = System.currentTimeMillis()
                appDatabase.voiceRecordDao().insert(record)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save voice record", e)
            }
        }.start()
    }

    // ==================== TextInputListener（语音转文字 → 搜索框）====================

    override fun onTextResult(result: String) {
        val cleaned = cleanSpeechText(result)
        etDestination.setText(cleaned)
        etDestination.setSelection(cleaned.length)
        if (cleaned.isNotEmpty()) {
            searchDestination(cleaned)
            Toast.makeText(this, "已识别：$cleaned", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onTextPartial(partial: String) {
        val cleaned = cleanSpeechText(partial)
        etDestination.setText(cleaned)
        etDestination.setSelection(cleaned.length)
    }

    // ==================== CommandExecutor 实现（Function Calling）====================

    override fun executeNavigateTo(destination: String?) {
        if (!hasValidAmapKey()) {
            speakForce("高德地图Key未配置，无法导航")
            return
        }
        pendingVoiceDestination = destination
        autoStartNavigationAfterSearch = true
        searchDestination(destination!!)
    }

    override fun executeStartObstacleAvoidance() {
        isObstacleRunning = true
        val intent = Intent(this, VisionTestActivity::class.java)
        startActivity(intent)
    }

    override fun executeStopNavigation() {
        if (navigationManager.isNavigating) {
            navigationManager.stopNavigation()
            btnStartNavigation.setText(R.string.start_navigation)
            clearRouteDisplay()
        }
    }

    override fun executeStopObstacleAvoidance() {
        // 发送广播通知 VisionTestActivity 关闭
        val intent = Intent("com.example.voicenavigation.ACTION_STOP_OBSTACLE")
        sendBroadcast(intent)
        isObstacleRunning = false
    }

    override fun executeWhereAmI() {
        // TTS 播报已在 VoiceInteractionManager 中完成，此处执行额外操作
        if (currentLocation != null) {
            mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLocation, 16f))
        } else {
            locateMe()
        }
    }

    override fun executeRepeatLast() {
        // TTS 播报已在 VoiceInteractionManager 中完成
    }

    override fun executePreviewRoute() {
        sendTripPreview()
    }

    override fun executeQueryStatus() {
        // TTS 播报已在 VoiceInteractionManager 中完成
    }

    override fun executeTextSearch(text: String?) {
        val cleaned = cleanSpeechText(text)
        etDestination.setText(cleaned)
        etDestination.setSelection(cleaned.length)
        if (cleaned.isNotEmpty()) {
            searchDestination(cleaned)
        }
    }

    override fun executeUnknown(text: String?) {
        // 未识别的指令，作为普通搜索尝试一次
        val cleaned = cleanSpeechText(text)
        if (cleaned.length >= 2) {
            etDestination.setText(cleaned)
            etDestination.setSelection(cleaned.length)
            searchDestination(cleaned)
        }
    }

    override fun getLastSpokenText(): String? {
        return lastSpokenInstruction
    }

    override fun isNavigating(): Boolean {
        return navigationManager.isNavigating
    }

    override fun isObstacleAvoiding(): Boolean {
        return isObstacleRunning
    }

    override fun getCurrentLocationDescription(): String? {
        if (!lastAddress.isNullOrEmpty()) {
            return lastAddress
        }
        if (currentLocation != null) {
            return currentLocation!!.latitude.toString() + "，" + currentLocation!!.longitude
        }
        return null
    }

    private fun cleanSpeechText(result: String?): String {
        if (result == null) return ""
        return result.replace(Regex("[。 ，、！；：,.!?;:]*$"), "").trim()
    }

    override fun onLocationUpdated(location: Location, address: String?) {
        currentLocation = LatLng(location.latitude, location.longitude)
        if (!address.isNullOrEmpty()) {
            lastAddress = address
        }
    }

    override fun onRouteReady(
        routePoints: List<LatLng>?,
        totalDistance: Float,
        totalDuration: Float,
        instructions: List<String>?
    ) {
        drawRoute(routePoints)
        tvNavDistance.text = formatDistance(totalDistance)
        tvNavDuration.text = formatDuration(totalDuration)
        if (!instructions.isNullOrEmpty()) {
            tvNavInstruction.text = instructions[0]
            speak(instructions[0])
        }
        if (mMap != null && currentLocation != null) {
            mMap!!.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLocation, 14f))
        }
        btnStartNavigation.setText(R.string.stop_navigation)
    }

    override fun onNavigationInfoUpdated(remainingDistance: Float, remainingDuration: Float, nextInstruction: String?) {
        tvNavDistance.text = formatDistance(remainingDistance)
        tvNavDuration.text = formatDuration(remainingDuration)
        if (!nextInstruction.isNullOrEmpty()) {
            tvNavInstruction.text = nextInstruction
            speak(nextInstruction)
        }
    }

    override fun onReRouting() {
        speakForce("正在重新规划步行路线")
    }

    override fun onArrived() {
        Toast.makeText(this, "已到达目的地附近", Toast.LENGTH_LONG).show()
        speakForce("您已到达目的地附近")
        btnStartNavigation.setText(R.string.start_navigation)
        clearRouteDisplay()
        selectedDestLatLng = null
        selectedDestName = null
    }

    override fun onNavigationStarted() {
        Log.d(TAG, "Nav started")
    }

    override fun onNavigationStopped() {
        lastSpokenInstruction = null
        btnStartNavigation.setText(R.string.start_navigation)
        clearRouteDisplay()
        selectedDestLatLng = null
        selectedDestName = null
    }

    override fun onNavigationError(error: String?) {
        Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        layoutNavInfo.visibility = View.GONE
    }

    private fun formatDistance(meters: Float): String {
        if (meters < 50) return "即将到达"
        if (meters < 1000) return meters.toInt().toString() + "m"
        return String.format("%.1fkm", meters / 1000)
    }

    private fun formatDuration(seconds: Float): String {
        if (seconds < 60) return "1分钟"
        val minutes = (seconds / 60).toInt()
        if (minutes < 60) return minutes.toString() + "分钟"
        return (minutes / 60).toString() + "小时" + (minutes % 60) + "分钟"
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        baiduTts?.destroy()
        baiduTts = null
        speechManager.destroyRecognizer()
        navigationManager.stopNavigation()
        navigationManager.destroyLocationClient()
        tripPreviewService.cancelAll()
        mapView.onDestroy()
    }
}
