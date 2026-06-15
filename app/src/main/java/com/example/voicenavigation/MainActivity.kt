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
import android.view.ViewGroup
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
// Room import removed — AppDatabase is Hilt-injected
import com.example.voicenavigation.data.VoiceRecord
import com.example.voicenavigation.data.VoiceRecordAdapter
import com.example.voicenavigation.navigation.NavigationManager
import com.example.voicenavigation.network.TripPreviewService
import com.example.voicenavigation.stt.BaiduSpeechManager
import com.example.voicenavigation.stt.BaiduTtsManager
import com.example.voicenavigation.voice.LLMFunctionCaller
import com.example.voicenavigation.ui.ringmenu.RingMenuView
import com.example.voicenavigation.ui.ringmenu.RingMenuItem
import com.example.voicenavigation.command.CommandRouter
import com.example.voicenavigation.menu.MenuConfig
import com.example.voicenavigation.ui.dialog.TripPreviewDialog
import com.example.voicenavigation.ui.voice.GestureVoiceLauncher
import com.example.voicenavigation.config.AppConfigProvider
import com.example.voicenavigation.animation.Animations
import com.example.voicenavigation.animation.AnimatorUtils
import com.example.voicenavigation.animation.AnimatorUtils.cancelAndClear
import com.example.voicenavigation.animation.AnimatorUtils.onEnd
import com.example.voicenavigation.animation.ViewTransition
import com.example.voicenavigation.util.FormatUtils
import com.example.voicenavigation.util.SecurityUtils
import com.example.voicenavigation.voice.VoiceInteractionManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import dagger.hilt.android.AndroidEntryPoint
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONException
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity(),
    NavigationManager.NavigationCallback,
    PoiSearch.OnPoiSearchListener,
    VoiceInteractionManager.CommandExecutor,
    VoiceInteractionManager.TextInputListener,
    GestureVoiceLauncher.GestureCallback {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_PERMISSIONS_CODE = 100
        private const val FIXED_ROUTE_ID = "gzdx_stadium"
    }

    // ── Hilt-injected singletons (eliminates dual instance problem) ──
    @Inject lateinit var navigationManager: NavigationManager
    @Inject lateinit var speechManager: BaiduSpeechManager
    @Inject lateinit var baiduTts: BaiduTtsManager
    @Inject lateinit var voiceInteractionManager: VoiceInteractionManager
    @Inject lateinit var appDatabase: AppDatabase
    @Inject lateinit var tripPreviewService: TripPreviewService
    @Inject lateinit var menuConfig: MenuConfig

    private var mMap: AMap? = null
    private lateinit var mapView: MapView
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
    private var isSelectingDestination = false

    // ===== 语音交互（Function Calling）状态 =====
    private var autoStartNavigationAfterSearch = false
    private var pendingVoiceDestination: String? = null
    private var lastAddress: String? = null
    private var isObstacleRunning = false

    // 环形菜单
    private var ringMenuView: RingMenuView? = null
    private lateinit var ringMenuContainer: FrameLayout

    // 动画：语音按钮脉冲效果
    private var voicePulseAnim: android.animation.ValueAnimator? = null
    private var voiceCommandPulseAnim: android.animation.ValueAnimator? = null

    @Inject lateinit var commandRouter: CommandRouter

    private val appConfigProvider by lazy { AppConfigProvider(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initAmapSdk()

        initViews()
        initServices()
        setupRingMenu()
        requestPermissions()

        // 全局长按唤醒语音助手
        GestureVoiceLauncher.attach(this, voiceInteractionManager, this)

        mapView = findViewById(R.id.map)
        mapView.onCreate(savedInstanceState)
        mMap = mapView.map
        initMap()

        // 从其他页面长按跳转回来时，自动启动语音助手
        handleVoiceCommandIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleVoiceCommandIntent(intent)
    }

    private fun handleVoiceCommandIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("START_VOICE_COMMAND", false) == true) {
            intent.removeExtra("START_VOICE_COMMAND")
            voiceInteractionManager.startListening(VoiceInteractionManager.Mode.COMMAND)
            Toast.makeText(this, getString(R.string.msg_voice_assistant_ready), Toast.LENGTH_SHORT).show()
        }
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
            Toast.makeText(this, getString(R.string.msg_amap_key_missing_location), Toast.LENGTH_LONG).show()
        }
    }

    private fun hasValidAmapKey(): Boolean {
        return SecurityUtils.hasValidAmapKey()
    }

    private fun getAppSignatureSha1(): String {
        return SecurityUtils.getAppSignatureSha1(this)
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
        suggestionAdapter.setOnItemClickListener(object : SuggestionAdapter.OnItemClickListener {
            override fun onItemClick(item: PoiItem, position: Int) {
                isSelectingDestination = true
                val point = item.latLonPoint
                val latLng = LatLng(point.latitude, point.longitude)
                setDestination(latLng, item.title)
                hideSuggestions()
                hideKeyboard()
                isSelectingDestination = false
            }
        })
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
            ViewTransition.scaleOut(btnStopTts, 150)
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
                    tvVoiceHint.text = getString(R.string.ui_release_to_stop)
                    // 动画：涟漪淡入 + 呼吸脉冲
                    ViewTransition.fadeIn(voiceRipple, 150)
                    voicePulseAnim?.cancel()
                    voicePulseAnim = Animations.Ambient.breathingScale(voiceRipple, 1f, 1.15f, 600)
                    voicePulseAnim?.start()
                    voiceInteractionManager.startListening(VoiceInteractionManager.Mode.TEXT_INPUT)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tvVoiceHint.text = getString(R.string.ui_recognizing)
                    // 动画：停止脉冲 + 涟漪淡出
                    voicePulseAnim?.cancel()
                    voicePulseAnim = null
                    voiceRipple.scaleX = 1f
                    voiceRipple.scaleY = 1f
                    ViewTransition.fadeOut(voiceRipple, 150)
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
                    tvVoiceCommandHint.text = getString(R.string.ui_recognizing_active)
                    // 动画：涟漪淡入 + 呼吸脉冲
                    ViewTransition.fadeIn(voiceCommandRipple, 150)
                    voiceCommandPulseAnim?.cancel()
                    voiceCommandPulseAnim = Animations.Ambient.breathingScale(voiceCommandRipple, 1f, 1.15f, 600)
                    voiceCommandPulseAnim?.start()
                    Toast.makeText(this, getString(R.string.msg_listening_start), Toast.LENGTH_SHORT).show()
                    voiceInteractionManager.startListening(VoiceInteractionManager.Mode.COMMAND)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tvVoiceCommandHint.text = getString(R.string.ui_recognizing)
                    // 动画：停止脉冲 + 涟漪淡出
                    voiceCommandPulseAnim?.cancel()
                    voiceCommandPulseAnim = null
                    voiceCommandRipple.scaleX = 1f
                    voiceCommandRipple.scaleY = 1f
                    ViewTransition.fadeOut(voiceCommandRipple, 150)
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
        // TODO: History/Settings pages now managed by Fragments.
        // Retained for bottomNav compatibility; will be fully replaced
        // when Navigation Component is integrated.
        if (index == 0) {
            containerPages?.let { ViewTransition.fadeOut(it, 200) }
            btnMyLocation?.let { ViewTransition.fadeIn(it, 200) }
        }
    }

    private fun hideSuggestions() {
        ViewTransition.fadeOut(cardSuggestions, 150)
    }

    private fun showSuggestions(items: List<PoiItem>?) {
        if (items.isNullOrEmpty()) {
            hideSuggestions()
            return
        }
        suggestionAdapter.updateData(items)
        ViewTransition.slideUp(cardSuggestions, 200)
    }

    private fun loadHistory() {
        // TODO: History page logic moved to HistoryFragment.
    }

    private fun setupHistoryAdapterListener() {
        historyAdapter?.setOnItemActionListener(object : VoiceRecordAdapter.OnItemActionListener {
            override fun onPlay(record: VoiceRecord, position: Int) {
                baiduTts?.speak(record.content)
            }

            override fun onDelete(record: VoiceRecord, position: Int) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.title_delete_record)
                    .setMessage(R.string.msg_confirm_delete)
                    .setPositiveButton(R.string.btn_delete) { _, _ ->
                        Thread {
                            appDatabase.voiceRecordDao().deleteById(record.id)
                            runOnUiThread {
                                historyAdapter?.removeItem(position)
                                loadHistory()
                                Toast.makeText(this@MainActivity, getString(R.string.msg_deleted), Toast.LENGTH_SHORT).show()
                            }
                        }.start()
                    }
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show()
            }
        })
    }

    private fun loadSettings() {
        // TODO: Settings page logic moved to SettingsFragment.
        // Will be fully replaced when Navigation Component is integrated.
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        if (imm != null && currentFocus != null) {
            imm.hideSoftInputFromWindow(currentFocus!!.windowToken, 0)
        }
    }

    private fun initServices() {
        // All singletons are Hilt-injected — only set up callbacks here
        handler = Handler(Looper.getMainLooper())

        // NavigationManager callback
        navigationManager.setNavigationCallback(this)

        // BaiduTTS init (token fetch)
        baiduTts.callback = object : BaiduTtsManager.TtsCallback {
            override fun onTtsReady() { Log.d(TAG, "TTS ready") }
            override fun onTtsError(error: String) { Log.e(TAG, "TTS error: $error") }
        }
        baiduTts.init()

        // Load preview server URL from prefs
        val prefs = AppConfig.prefs(this)
        val savedUrl = AppConfig.normalizeBaseUrl(
            prefs.getString(AppConfig.KEY_PREVIEW_SERVER_BASE_URL, TripPreviewService.DEFAULT_BASE_URL)
        )
        tripPreviewService.baseUrl = savedUrl

        // Voice interaction callbacks
        voiceInteractionManager.setTextInputListener(this)
        voiceInteractionManager.setCommandExecutor(this)
        voiceInteractionManager.setVoiceEventListener(object : VoiceInteractionManager.VoiceEventListener {
            override fun onListeningStarted() {}
            override fun onListeningStopped() {}
            override fun onPartialResultReceived(text: String) {}
            override fun onPipelineStage(stage: String) {
                // 实时更新橙色按钮文字，显示流水线进度
                runOnUiThread { tvVoiceCommandHint.text = stage }
            }
        })
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
            ViewTransition.scaleIn(btnStopTts, 250)
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
            val newLoc = LatLng(location.latitude, location.longitude)
            currentLocation = newLoc
            if (shouldMoveCamera) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(newLoc, 15f))
            }
        }

        map.setOnMapClickListener { latLng ->
            if (navigationManager.isNavigating()) return@setOnMapClickListener
            setDestination(latLng, latLng.latitude.toString() + ", " + latLng.longitude)
            etDestination.setText("")
            etDestination.hint = getString(R.string.ui_map_point_selected)
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
            Toast.makeText(this, getString(R.string.msg_amap_key_missing), Toast.LENGTH_LONG).show()
            return
        }
        if (!checkLocationPermission()) {
            requestPermissions()
            Toast.makeText(this, R.string.permission_location_denied, Toast.LENGTH_SHORT).show()
            return
        }
        enableMapLocation()
        navigationManager.requestCurrentLocation()
        val loc = currentLocation
        if (mMap != null && loc != null) {
            mMap!!.animateCamera(CameraUpdateFactory.newLatLngZoom(loc, 16f))
        } else {
            Toast.makeText(this, getString(R.string.msg_getting_location), Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, getString(R.string.msg_permission_partial), Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, getString(R.string.msg_amap_key_missing_search), Toast.LENGTH_SHORT).show()
            return
        }

        val query = PoiSearch.Query(keyword, "", "")
        query.pageSize = 10
        query.pageNum = 0
        query.setCityLimit(false)

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
            Toast.makeText(this, getString(R.string.msg_search_failed), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPoiSearched(poiResult: PoiResult?, rCode: Int) {
        if (rCode == 1000 && poiResult != null) {
            poiResults = poiResult.pois
            if (poiResults.isNullOrEmpty()) {
                hideSuggestions()
                if (autoStartNavigationAfterSearch) {
                    speakForce(getString(R.string.tts_destination_not_found))
                    autoStartNavigationAfterSearch = false
                    pendingVoiceDestination = null
                } else {
                    Toast.makeText(this, getString(R.string.msg_no_match), Toast.LENGTH_SHORT).show()
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
                    val confirmMsg = getString(R.string.tts_navigation_started, firstItem.title)
                    voiceInteractionManager.speakAndToast(confirmMsg)

                    // TTS 播报队列自动顺序播放，无需延迟
                    val loc = currentLocation
                    if (loc != null) {
                        ViewTransition.slideUp(layoutNavInfo, 250)
                        navigationManager.planRoute(loc, selectedDestLatLng!!, selectedDestName)
                    } else {
                        locateMe()
                        speakForce(getString(R.string.tts_getting_location_wait))
                    }
                } else {
                    showSuggestions(poiResults)
                }
            }
        } else {
            hideSuggestions()
            if (autoStartNavigationAfterSearch) {
                speakForce(getString(R.string.tts_location_search_failed, rCode))
                autoStartNavigationAfterSearch = false
                pendingVoiceDestination = null
            } else {
                Toast.makeText(this, getString(R.string.msg_location_search_failed, rCode), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, getString(R.string.msg_amap_key_missing_nav), Toast.LENGTH_SHORT).show()
            return
        }
        if (!::navigationManager.isInitialized) {
            Toast.makeText(this, getString(R.string.msg_nav_service_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        if (navigationManager.isNavigating()) {
            navigationManager.stopNavigation()
            btnStartNavigation.setText(R.string.start_navigation)
            clearRouteDisplay()
            return
        }
        if (selectedDestLatLng == null) {
            Toast.makeText(this, getString(R.string.msg_select_destination_first), Toast.LENGTH_SHORT).show()
            return
        }
        if (currentLocation == null) {
            locateMe()
            Toast.makeText(this, getString(R.string.msg_getting_location_wait), Toast.LENGTH_SHORT).show()
            return
        }
        ViewTransition.slideUp(layoutNavInfo, 250)
        saveVoiceRecord(selectedDestName)
        navigationManager.planRoute(currentLocation!!, selectedDestLatLng!!, selectedDestName)
    }

    private fun sendTripPreview() {
        val previewBaseUrl = AppConfig.normalizeBaseUrl(
            AppConfig.prefs(this).getString(AppConfig.KEY_PREVIEW_SERVER_BASE_URL, TripPreviewService.DEFAULT_BASE_URL)
        )
        if (previewBaseUrl.isEmpty()) {
            Toast.makeText(this, getString(R.string.msg_set_backend_url), Toast.LENGTH_SHORT).show()
            return
        }
        tripPreviewService.baseUrl = previewBaseUrl

        val callback = object : TripPreviewService.PreviewCallback {
            override fun onSuccess(response: String) {
                parseAndShowPreviewResult(response)
            }

            override fun onError(error: String) {
                Toast.makeText(this@MainActivity, getString(R.string.msg_preview_failed_with_error, error), Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, getString(R.string.msg_preview_fixed_route), Toast.LENGTH_SHORT).show()
            tripPreviewService.sendFixedPreviewRequest(FIXED_ROUTE_ID, callback)
        }
    }

    private fun parseAndShowPreviewResult(responseJson: String?) {
        try {
            val root = JSONObject(responseJson)
            if (!root.optBoolean("success", false)) {
                Toast.makeText(this, getString(R.string.msg_preview_return_failed), Toast.LENGTH_SHORT).show()
                return
            }
            val data = root.optJSONObject("data")
            if (data == null) {
                Toast.makeText(this, getString(R.string.msg_preview_data_empty), Toast.LENGTH_SHORT).show()
                return
            }
            val broadcastText = data.optString("text", "")
            val routeSummary = data.optJSONObject("route_summary")
            val keyNodes = data.optJSONArray("key_nodes")
            if (broadcastText.isNotEmpty()) {
                speakForce(getString(R.string.tts_preview, broadcastText))
            }
            showPreviewDialog(broadcastText, routeSummary, keyNodes)
        } catch (e: JSONException) {
            Log.e(TAG, "Parse preview failed", e)
            Toast.makeText(this, getString(R.string.msg_preview_parse_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPreviewDialog(
        broadcastText: String,
        routeSummary: JSONObject?,
        keyNodes: JSONArray?
    ) {
        TripPreviewDialog.show(this, broadcastText, routeSummary, keyNodes) { text ->
            speakForce(text)
        }
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
        ViewTransition.slideDown(layoutNavInfo, 200)
        clearMarkers()
    }

    private fun addDestinationMarker(latLng: LatLng) {
        if (mMap == null) return
        destinationMarker?.remove()
        destinationMarker = mMap!!.addMarker(
            MarkerOptions()
                .position(latLng)
                .title(getString(R.string.destination_hint))
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
            Toast.makeText(this, getString(R.string.msg_recognized, cleaned), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onTextPartial(partial: String) {
        val cleaned = cleanSpeechText(partial)
        etDestination.setText(cleaned)
        etDestination.setSelection(cleaned.length)
    }

    // ==================== CommandExecutor 实现（Function Calling）====================

    override fun executeNavigateTo(destination: String) {
        if (!hasValidAmapKey()) {
            speakForce(getString(R.string.msg_amap_key_missing_nav_tts))
            return
        }
        pendingVoiceDestination = destination
        autoStartNavigationAfterSearch = true
        searchDestination(destination)
    }

    override fun executeStartObstacleAvoidance() {
        isObstacleRunning = true
        val intent = Intent(this, VisionTestActivity::class.java)
        startActivity(intent)
    }

    override fun executeStopNavigation() {
        if (navigationManager.isNavigating()) {
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
        val loc = currentLocation
        if (loc != null) {
            mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(loc, 16f))
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

    override fun executeTextSearch(text: String) {
        val cleaned = cleanSpeechText(text)
        etDestination.setText(cleaned)
        etDestination.setSelection(cleaned.length)
        if (cleaned.isNotEmpty()) {
            searchDestination(cleaned)
        }
    }

    override fun executeUnknown(text: String) {
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
        return navigationManager.isNavigating()
    }

    override fun isObstacleAvoiding(): Boolean {
        return isObstacleRunning
    }

    override fun getCurrentLocationDescription(): String? {
        if (!lastAddress.isNullOrEmpty()) {
            return lastAddress
        }
        val loc = currentLocation
        if (loc != null) {
            return loc.latitude.toString() + "，" + loc.longitude
        }
        return null
    }

    private fun cleanSpeechText(text: String?): String {
        return com.example.voicenavigation.util.TextUtils.cleanSpeechText(text)
    }

    // ==================== 环形菜单（数据来自 menu_config.json） ====================

    private fun setupRingMenu() {
        // MenuConfig is Hilt-injected (no manual creation needed)

        // Collect CommandRouter events — THIS is what makes ring menu commands actually work
        lifecycleScope.launch {
            commandRouter.events.collect { event ->
                handleCommandEvent(event)
            }
        }

        ringMenuContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        val rootLayout = findViewById<ViewGroup>(android.R.id.content)
        rootLayout.addView(ringMenuContainer)

        ringMenuView = RingMenuView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setMenuItems(menuConfig.getItems())

            onItemExecuted = { item ->
                hideRingMenu()
                commandRouter.execute(item.command)
            }
            onCenterClicked = {
                hideRingMenu()
            }
        }
        ringMenuContainer.addView(ringMenuView)
    }

    private fun showRingMenu(centerX: Float, centerY: Float) {
        // B3 修复：先设 VISIBLE 再动画，确保 View 能立即接收触摸事件
        ringMenuContainer.visibility = View.VISIBLE
        ViewTransition.scaleInFrom(ringMenuContainer, centerX, centerY, 350)
        ringMenuView?.invalidate()
    }

    private fun hideRingMenu() {
        ViewTransition.scaleOut(ringMenuContainer, 200)
    }

    /**
     * Handle events emitted by CommandRouter (from ring menu, voice commands, or gestures).
     * This is the central dispatch point that connects commands to UI actions.
     */
    private fun handleCommandEvent(event: com.example.voicenavigation.command.CommandEvent) {
        when (event) {
            is com.example.voicenavigation.command.CommandEvent.NavigateTo -> {
                autoStartNavigationAfterSearch = true
                pendingVoiceDestination = event.destination
                searchDestination(event.destination)
            }
            is com.example.voicenavigation.command.CommandEvent.StopNavigation -> {
                if (navigationManager.isNavigating()) {
                    navigationManager.stopNavigation()
                    btnStartNavigation.text = getString(R.string.start_navigation)
                    clearRouteDisplay()
                } else {
                    Toast.makeText(this, getString(R.string.msg_no_navigation), Toast.LENGTH_SHORT).show()
                }
            }
            is com.example.voicenavigation.command.CommandEvent.OpenObstacleAvoidance -> {
                startActivity(Intent(this, VisionTestActivity::class.java))
            }
            is com.example.voicenavigation.command.CommandEvent.StopObstacleAvoidance -> {
                val intent = Intent(com.example.voicenavigation.config.AppConstants.BROADCAST_ACTION_STOP_OBSTACLE)
                sendBroadcast(intent)
            }
            is com.example.voicenavigation.command.CommandEvent.PreviewRoute -> {
                sendTripPreview()
            }
            is com.example.voicenavigation.command.CommandEvent.AnnounceLocation -> {
                val locDesc = lastAddress ?: currentLocation?.let { "${it.latitude}, ${it.longitude}" }
                if (!locDesc.isNullOrEmpty()) {
                    speakForce(getString(R.string.tts_currently_at, locDesc))
                } else {
                    speakForce(getString(R.string.msg_locating))
                }
            }
            is com.example.voicenavigation.command.CommandEvent.RepeatLast -> {
                val lastText = lastSpokenInstruction
                if (!lastText.isNullOrEmpty()) {
                    speakForce(lastText)
                } else {
                    speakForce(getString(R.string.msg_nothing_to_repeat))
                }
            }
            is com.example.voicenavigation.command.CommandEvent.AnnounceStatus -> {
                val nav = navigationManager.isNavigating()
                val obs = isObstacleRunning
                val status = (if (nav) getString(R.string.status_navigating) else getString(R.string.status_not_navigating)) +
                        "，" + (if (obs) getString(R.string.status_obstacle_on) else getString(R.string.status_obstacle_off))
                speakForce(status)
            }
            is com.example.voicenavigation.command.CommandEvent.SearchDestination -> {
                val cleaned = com.example.voicenavigation.util.TextUtils.cleanSpeechText(event.keyword)
                if (cleaned.isNotEmpty()) {
                    etDestination.setText(cleaned)
                    searchDestination(cleaned)
                }
            }
            is com.example.voicenavigation.command.CommandEvent.ShowHistory -> {
                switchTab(1)
            }
            is com.example.voicenavigation.command.CommandEvent.ShowSettings -> {
                switchTab(2)
            }
            is com.example.voicenavigation.command.CommandEvent.OpenDataCollection -> {
                startActivity(Intent(this, com.example.voicenavigation.collection.ui.hub.CaptureHubActivity::class.java))
            }
            is com.example.voicenavigation.command.CommandEvent.StartVoiceAssistant -> {
                voiceInteractionManager.startListening(VoiceInteractionManager.Mode.COMMAND)
                Toast.makeText(this, getString(R.string.msg_voice_assistant_ready), Toast.LENGTH_SHORT).show()
            }
            is com.example.voicenavigation.command.CommandEvent.UnknownCommand -> {
                speakForce(getString(R.string.msg_unknown_command))
                executeUnknown(event.rawText)
            }
            is com.example.voicenavigation.command.CommandEvent.QueryResult -> { /* display result */ }
        }
    }

    // ==================== GestureVoiceLauncher.GestureCallback ====================

    override fun onVoiceAssistant() {
        voiceInteractionManager.startListening(VoiceInteractionManager.Mode.COMMAND)
        Toast.makeText(this, getString(R.string.msg_voice_assistant_ready), Toast.LENGTH_SHORT).show()
    }

    override fun onRingMenuShow(centerX: Float, centerY: Float) {
        showRingMenu(centerX, centerY)
    }

    override fun onRingMenuConfirm() {
        // RingMenuView handles the selection internally via onTouchEvent
        // The view's onItemExecuted callback will fire
    }

    override fun onCancel() {
        hideRingMenu()
    }

    override fun onLocationUpdated(location: Location, address: String?) {
        currentLocation = LatLng(location.latitude, location.longitude)
        if (!address.isNullOrEmpty()) {
            lastAddress = address
        }
    }

    override fun onRouteReady(
        routePoints: List<LatLng>,
        totalDistance: Float,
        totalDuration: Float,
        instructions: List<String>
    ) {
        drawRoute(routePoints)
        tvNavDistance.text = formatDistance(totalDistance)
        tvNavDuration.text = formatDuration(totalDuration)
        if (instructions.isNotEmpty()) {
            tvNavInstruction.text = instructions[0]
            speak(instructions[0])
        }
        val loc = currentLocation
        val map = mMap
        if (map != null && loc != null) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(loc, 14f))
        }
        btnStartNavigation.setText(R.string.stop_navigation)
    }

    override fun onNavigationInfoUpdated(remainingDistance: Float, remainingDuration: Float, nextInstruction: String) {
        tvNavDistance.text = formatDistance(remainingDistance)
        tvNavDuration.text = formatDuration(remainingDuration)
        if (!nextInstruction.isNullOrEmpty()) {
            tvNavInstruction.text = nextInstruction
            speak(nextInstruction)
        }
    }

    override fun onReRouting() {
        speakForce(getString(R.string.tts_rerouting))
    }

    override fun onArrived() {
        Toast.makeText(this, getString(R.string.msg_arrived), Toast.LENGTH_LONG).show()
        speakForce(getString(R.string.tts_arrived))
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

    override fun onNavigationError(error: String) {
        Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        ViewTransition.fadeOut(layoutNavInfo, 200)
    }

    private fun formatDistance(meters: Float): String {
        return FormatUtils.formatDistance(meters, appConfigProvider)
    }

    private fun formatDuration(seconds: Float): String {
        return FormatUtils.formatDuration(seconds, appConfigProvider)
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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        GestureVoiceLauncher.onDispatchTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        GestureVoiceLauncher.detach()
        voicePulseAnim?.cancel()
        voicePulseAnim = null
        voiceCommandPulseAnim?.cancel()
        voiceCommandPulseAnim = null
        super.onDestroy()
        baiduTts.destroy()
        speechManager.destroyRecognizer()
        navigationManager.stopNavigation()
        navigationManager.destroyLocationClient()
        tripPreviewService.cancelAll()
        mapView.onDestroy()
    }
}
