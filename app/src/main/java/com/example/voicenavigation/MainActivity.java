package com.example.voicenavigation;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.os.VibrationEffect;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.amap.api.location.AMapLocationClient;
import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.MyLocationStyle;
import com.amap.api.maps.model.Polyline;
import com.amap.api.maps.model.PolylineOptions;
import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.core.PoiItem;
import com.amap.api.services.core.ServiceSettings;
import com.amap.api.services.poisearch.PoiResult;
import com.amap.api.services.poisearch.PoiSearch;
import com.example.voicenavigation.data.AppDatabase;
import com.example.voicenavigation.data.SuggestionAdapter;
import com.example.voicenavigation.data.VoiceRecord;
import com.example.voicenavigation.data.VoiceRecordAdapter;
import com.example.voicenavigation.navigation.NavigationManager;
import com.example.voicenavigation.network.TripPreviewService;
import com.example.voicenavigation.stt.BaiduSpeechManager;
import com.example.voicenavigation.stt.BaiduTtsManager;
import com.example.voicenavigation.voice.LLMFunctionCaller;
import com.example.voicenavigation.voice.VoiceInteractionManager;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.List;
import java.security.MessageDigest;

public class MainActivity extends AppCompatActivity implements
        NavigationManager.NavigationCallback,
        PoiSearch.OnPoiSearchListener,
        VoiceInteractionManager.CommandExecutor,
        VoiceInteractionManager.TextInputListener {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_PERMISSIONS_CODE = 100;

    private AMap mMap;
    private MapView mapView;
    private BaiduSpeechManager speechManager;
    private NavigationManager navigationManager;
    private AppDatabase appDatabase;
    private BaiduTtsManager baiduTts;
    private Handler handler;

    private FrameLayout btnVoiceContainer;
    private TextView tvVoiceHint;
    private View voiceRipple;

    // 语音助手按钮（Function Calling）
    private FrameLayout btnVoiceCommandContainer;
    private TextView tvVoiceCommandHint;
    private View voiceCommandRipple;

    private Button btnStartNavigation;
    private Button btnPreviewRoute;
    private Button btnVisionTest;
    private Button btnStopTts;
    private ImageButton btnMyLocation;
    private EditText etDestination;
    private ImageButton btnClearSearch;
    private CardView cardSuggestions;
    private RecyclerView rvSuggestions;
    private SuggestionAdapter suggestionAdapter;
    private android.os.Vibrator vibrator;

    private LinearLayout layoutNavInfo;
    private TextView tvNavDistance;
    private TextView tvNavDuration;
    private TextView tvNavInstruction;

    private LatLng currentLocation;
    private Marker destinationMarker;
    private Polyline routePolyline;
    private PoiSearch poiSearch;
    private List<PoiItem> poiResults;
    private LatLng selectedDestLatLng;
    private String selectedDestName;
    private String lastSpokenInstruction;

    private BottomNavigationView bottomNav;
    private FrameLayout containerPages;
    private View pageHistoryView;
    private View pageSettingsView;
    private View bottomControls;
    private View searchBarContainer;
    private RecyclerView rvHistory;
    private View layoutHistoryEmpty;
    private TextView tvHistoryCount;
    private TextView tvHistoryDestCount;
    private VoiceRecordAdapter historyAdapter;
    private TripPreviewService tripPreviewService;
    private boolean isSelectingDestination = false;

    // ===== 语音交互（Function Calling）状态 =====
    private VoiceInteractionManager voiceInteractionManager;
    private boolean autoStartNavigationAfterSearch = false;
    private String pendingVoiceDestination = null;
    private String lastAddress = null;
    private boolean isObstacleRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initAmapSdk();

        initViews();
        initServices();
        requestPermissions();

        mapView = findViewById(R.id.map);
        mapView.onCreate(savedInstanceState);
        mMap = mapView.getMap();
        initMap();
    }

    private void initAmapSdk() {
        AMapLocationClient.updatePrivacyShow(this, true, true);
        AMapLocationClient.updatePrivacyAgree(this, true);
        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);
        ServiceSettings.updatePrivacyShow(this, true, true);
        ServiceSettings.updatePrivacyAgree(this, true);

        if (hasValidAmapKey()) {
            MapsInitializer.setApiKey(BuildConfig.AMAP_API_KEY);
            AMapLocationClient.setApiKey(BuildConfig.AMAP_API_KEY);
            ServiceSettings.getInstance().setApiKey(BuildConfig.AMAP_API_KEY);
            Log.d(TAG, "AMap runtime package=" + getPackageName() + ", sha1=" + getAppSignatureSha1());
        } else {
            Log.e(TAG, "AMap API key is missing. Add amap.api.key to local.properties.");
            Toast.makeText(this, "高德Key未配置，定位和搜索不可用", Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasValidAmapKey() {
        return BuildConfig.AMAP_API_KEY != null && !BuildConfig.AMAP_API_KEY.trim().isEmpty();
    }

    private String getAppSignatureSha1() {
        try {
            android.content.pm.PackageInfo packageInfo = getPackageManager()
                    .getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
            if (packageInfo.signatures == null || packageInfo.signatures.length == 0) {
                return "unknown";
            }
            MessageDigest digest = MessageDigest.getInstance("SHA1");
            byte[] sha1 = digest.digest(packageInfo.signatures[0].toByteArray());
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < sha1.length; i++) {
                if (i > 0) builder.append(":");
                builder.append(String.format("%02X", sha1[i]));
            }
            return builder.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to read app signature SHA1", e);
            return "unknown";
        }
    }

    private void initViews() {
        // 语音转文字按钮
        btnVoiceContainer = findViewById(R.id.btn_voice_container);
        tvVoiceHint = findViewById(R.id.tv_voice_hint);
        voiceRipple = findViewById(R.id.voice_ripple);

        // 语音助手按钮（Function Calling）
        btnVoiceCommandContainer = findViewById(R.id.btn_voice_command_container);
        tvVoiceCommandHint = findViewById(R.id.tv_voice_command_hint);
        voiceCommandRipple = findViewById(R.id.voice_command_ripple);

        btnStartNavigation = findViewById(R.id.btn_start_navigation);
        btnPreviewRoute = findViewById(R.id.btn_preview_route);
        btnVisionTest = findViewById(R.id.btn_vision_test);
        btnStopTts = findViewById(R.id.btn_stop_tts);
        btnMyLocation = findViewById(R.id.btn_my_location);
        etDestination = findViewById(R.id.et_destination);
        btnClearSearch = findViewById(R.id.btn_clear_search);
        cardSuggestions = findViewById(R.id.card_suggestions);
        rvSuggestions = findViewById(R.id.rv_suggestions);

        layoutNavInfo = findViewById(R.id.layout_nav_info);
        tvNavDistance = findViewById(R.id.tv_nav_distance);
        tvNavDuration = findViewById(R.id.tv_nav_duration);
        tvNavInstruction = findViewById(R.id.tv_nav_instruction);

        bottomNav = findViewById(R.id.bottom_nav);
        containerPages = findViewById(R.id.container_pages);
        pageHistoryView = findViewById(R.id.page_history);
        pageSettingsView = findViewById(R.id.page_settings);
        bottomControls = findViewById(R.id.bottom_controls);
        searchBarContainer = findViewById(R.id.search_bar_container);
        rvHistory = findViewById(R.id.rv_history);
        rvHistory.setLayoutManager(new LinearLayoutManager(this));
        layoutHistoryEmpty = pageHistoryView.findViewById(R.id.layout_history_empty);
        tvHistoryCount = pageHistoryView.findViewById(R.id.tv_history_count);
        tvHistoryDestCount = pageHistoryView.findViewById(R.id.tv_history_dest_count);

        rvSuggestions.setLayoutManager(new LinearLayoutManager(this));
        suggestionAdapter = new SuggestionAdapter(new ArrayList<>());
        suggestionAdapter.setOnItemClickListener((item, position) -> {
            isSelectingDestination = true;
            LatLonPoint point = item.getLatLonPoint();
            LatLng latLng = new LatLng(point.getLatitude(), point.getLongitude());
            setDestination(latLng, item.getTitle());
            hideSuggestions();
            hideKeyboard();
            isSelectingDestination = false;
        });
        rvSuggestions.setAdapter(suggestionAdapter);

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_tab_nav) {
                switchTab(0);
            } else if (id == R.id.nav_tab_history) {
                switchTab(1);
            } else if (id == R.id.nav_tab_settings) {
                switchTab(2);
            }
            return true;
        });

        vibrator = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        setupVoiceButton();
        setupVoiceCommandButton();
        setupSearchBar();

        btnStartNavigation.setOnClickListener(v -> toggleNavigation());
        btnPreviewRoute.setOnClickListener(v -> sendTripPreview());
        btnVisionTest.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, VisionTestActivity.class)));
        btnMyLocation.setOnClickListener(v -> locateMe());
        btnStopTts.setOnClickListener(v -> {
            if (baiduTts != null) {
                baiduTts.stopPlayback();
                btnStopTts.setVisibility(View.GONE);
            }
        });
    }

    /**
     * 语音转文字按钮 —— 按住说话，识别结果填入搜索框。
     * 使用独立的 BaiduSpeechManager 实例，与语音助手互不影响。
     */
    /**
     * 语音转文字按钮（蓝色「按住说话」）
     * 按下 → 语音识别 → 结果填入搜索框。
     * 松开后延迟 150ms 再发送 ASR_STOP，防止最后一个字被截断。
     */
    private void setupVoiceButton() {
        btnVoiceContainer.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (!checkAudioPermission()) {
                        Toast.makeText(this, R.string.permission_audio_denied, Toast.LENGTH_SHORT).show();
                        requestPermissions();
                        return true;
                    }
                    vibrate(50);
                    tvVoiceHint.setText("松开结束");
                    voiceRipple.setVisibility(View.VISIBLE);
                    voiceInteractionManager.startListening(VoiceInteractionManager.Mode.TEXT_INPUT);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    tvVoiceHint.setText("识别中...");
                    voiceRipple.setVisibility(View.GONE);
                    // 立即停止（不延迟），百度引擎用 VAD Endpoint Timeout 自行采集尾音
                    voiceInteractionManager.stopListening();
                    return true;
                default:
                    return false;
            }
        });
    }

    /**
     * 语音助手按钮（橙色「语音助手」）
     * 按下 → 语音识别 → Function Calling 执行指令。
     */
    private void setupVoiceCommandButton() {
        btnVoiceCommandContainer.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (!checkAudioPermission()) {
                        Toast.makeText(this, R.string.permission_audio_denied, Toast.LENGTH_SHORT).show();
                        requestPermissions();
                        return true;
                    }
                    vibrate(50);
                    tvVoiceCommandHint.setText("正在识别...");
                    voiceCommandRipple.setVisibility(View.VISIBLE);
                    Toast.makeText(this, "开始收听，松开后执行", Toast.LENGTH_SHORT).show();
                    voiceInteractionManager.startListening(VoiceInteractionManager.Mode.COMMAND);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    tvVoiceCommandHint.setText("识别中...");
                    voiceCommandRipple.setVisibility(View.GONE);
                    voiceInteractionManager.stopListening();
                    return true;
                default:
                    return false;
            }
        });
    }

    private void vibrate(long ms) {
        if (vibrator != null && vibrator.hasVibrator()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(ms);
            }
        }
    }

    private void setupSearchBar() {
        etDestination.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (isSelectingDestination) return;
                String text = s.toString().trim();
                btnClearSearch.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
                if (text.length() >= 2) {
                    searchDestination(text);
                } else {
                    hideSuggestions();
                }
            }
        });

        btnClearSearch.setOnClickListener(v -> {
            etDestination.setText("");
            hideSuggestions();
        });

        etDestination.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                String keyword = etDestination.getText().toString().trim();
                if (!keyword.isEmpty()) searchDestination(keyword);
                return true;
            }
            return false;
        });
    }

    private void switchTab(int index) {
        if (index == 0) {
            containerPages.setVisibility(View.GONE);
            bottomControls.setVisibility(View.VISIBLE);
            searchBarContainer.setVisibility(View.VISIBLE);
            btnMyLocation.setVisibility(View.VISIBLE);
        } else {
            containerPages.setVisibility(View.VISIBLE);
            bottomControls.setVisibility(View.GONE);
            searchBarContainer.setVisibility(View.GONE);
            btnMyLocation.setVisibility(View.GONE);
            hideSuggestions();
            pageHistoryView.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
            pageSettingsView.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
            if (index == 1) loadHistory();
            else if (index == 2) loadSettings();
        }
    }

    private void hideSuggestions() {
        cardSuggestions.setVisibility(View.GONE);
    }

    private void showSuggestions(List<PoiItem> items) {
        if (items == null || items.isEmpty()) {
            hideSuggestions();
            return;
        }
        suggestionAdapter.updateData(items);
        cardSuggestions.setVisibility(View.VISIBLE);
    }

    private void loadHistory() {
        new Thread(() -> {
            try {
                List<VoiceRecord> records = appDatabase.voiceRecordDao().getAllRecords();
                int totalCount = appDatabase.voiceRecordDao().getCount();
                int destCount = 0;
                if (records != null) {
                    for (VoiceRecord record : records) {
                        if (record.getDestination() != null && !record.getDestination().isEmpty()) {
                            destCount++;
                        }
                    }
                }
                final int finalDestCount = destCount;
                runOnUiThread(() -> {
                    tvHistoryCount.setText(String.valueOf(totalCount));
                    tvHistoryDestCount.setText(String.valueOf(finalDestCount));
                    if (records == null || records.isEmpty()) {
                        layoutHistoryEmpty.setVisibility(View.VISIBLE);
                        rvHistory.setVisibility(View.GONE);
                    } else {
                        layoutHistoryEmpty.setVisibility(View.GONE);
                        rvHistory.setVisibility(View.VISIBLE);
                        if (historyAdapter == null) {
                            historyAdapter = new VoiceRecordAdapter(records);
                            setupHistoryAdapterListener();
                            rvHistory.setAdapter(historyAdapter);
                        } else {
                            historyAdapter.updateData(records);
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to load history", e);
            }
        }).start();
    }

    private void setupHistoryAdapterListener() {
        historyAdapter.setOnItemActionListener(new VoiceRecordAdapter.OnItemActionListener() {
            @Override
            public void onPlay(VoiceRecord record, int position) {
                if (baiduTts != null) baiduTts.speak(record.getContent());
            }

            @Override
            public void onDelete(VoiceRecord record, int position) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("删除记录")
                        .setMessage("确定要删除这条历史记录吗？")
                        .setPositiveButton("删除", (dialog, which) -> new Thread(() -> {
                            appDatabase.voiceRecordDao().deleteById(record.getId());
                            runOnUiThread(() -> {
                                historyAdapter.removeItem(position);
                                loadHistory();
                                Toast.makeText(MainActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                            });
                        }).start())
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
    }

    private void loadSettings() {
        TextView tvAmapKey = findViewById(R.id.tv_amap_key);
        tvAmapKey.setText(BuildConfig.AMAP_API_KEY);

        EditText etServerUrl = pageSettingsView.findViewById(R.id.et_server_url);
        Button btnSaveUrl = pageSettingsView.findViewById(R.id.btn_save_url);
        EditText etDetectionServerUrl = pageSettingsView.findViewById(R.id.et_detection_server_url);
        Button btnSaveDetectionUrl = pageSettingsView.findViewById(R.id.btn_save_detection_url);

        SharedPreferences prefs = AppConfig.prefs(this);
        String savedUrl = AppConfig.normalizeBaseUrl(
                prefs.getString(AppConfig.KEY_PREVIEW_SERVER_BASE_URL, TripPreviewService.DEFAULT_BASE_URL));
        String savedDetectionUrl = prefs.getString(AppConfig.KEY_DETECTION_SERVER_BASE_URL, "");
        etServerUrl.setText(savedUrl);
        etDetectionServerUrl.setText(savedDetectionUrl);

        btnSaveUrl.setOnClickListener(v -> {
            String url = AppConfig.normalizeBaseUrl(etServerUrl.getText().toString());
            if (url.isEmpty()) {
                Toast.makeText(this, "请输入地图服务地址", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putString(AppConfig.KEY_PREVIEW_SERVER_BASE_URL, url).apply();
            tripPreviewService.setBaseUrl(url);
            Toast.makeText(this, "地图服务地址已保存", Toast.LENGTH_SHORT).show();
        });

        btnSaveDetectionUrl.setOnClickListener(v -> {
            String url = AppConfig.normalizeBaseUrl(etDetectionServerUrl.getText().toString());
            if (url.isEmpty()) {
                Toast.makeText(this, "请输入检测服务地址", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putString(AppConfig.KEY_DETECTION_SERVER_BASE_URL, url).apply();
            Toast.makeText(this, "检测服务地址已保存", Toast.LENGTH_SHORT).show();
        });

        Button btnResetUrl = pageSettingsView.findViewById(R.id.btn_reset_url);
        btnResetUrl.setOnClickListener(v -> {
            String defaultUrl = TripPreviewService.DEFAULT_BASE_URL;
            prefs.edit().putString(AppConfig.KEY_PREVIEW_SERVER_BASE_URL, defaultUrl).apply();
            etServerUrl.setText(defaultUrl);
            tripPreviewService.setBaseUrl(defaultUrl);
            Toast.makeText(this, "已恢复默认地址", Toast.LENGTH_SHORT).show();
        });

        SwitchCompat switchExternal = pageSettingsView.findViewById(R.id.switch_use_external_device);
        boolean useExternal = prefs.getBoolean("use_external_device", false);
        switchExternal.setChecked(useExternal);
        switchExternal.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("use_external_device", isChecked).apply();
            Toast.makeText(this, isChecked ? "已开启外部设备优先" : "已关闭外部设备优先", Toast.LENGTH_SHORT).show();
        });

        // ===== LLM Function Calling 配置 =====
        EditText etLlmBaseUrl = pageSettingsView.findViewById(R.id.et_llm_base_url);
        EditText etLlmApiKey = pageSettingsView.findViewById(R.id.et_llm_api_key);
        EditText etLlmModel = pageSettingsView.findViewById(R.id.et_llm_model);
        Button btnSaveLlm = pageSettingsView.findViewById(R.id.btn_save_llm);
        TextView tvLlmStatus = pageSettingsView.findViewById(R.id.tv_llm_status);

        String savedLlmUrl = AppConfig.normalizeBaseUrl(
                prefs.getString(AppConfig.KEY_LLM_BASE_URL, ""));
        String savedLlmKey = prefs.getString(AppConfig.KEY_LLM_API_KEY, "");
        String savedLlmModel = prefs.getString(AppConfig.KEY_LLM_MODEL, "deepseek-chat");
        etLlmBaseUrl.setText(savedLlmUrl);
        etLlmApiKey.setText(savedLlmKey);
        etLlmModel.setText(savedLlmModel);

        // 显示 LLM 配置状态
        String llmStatus = "状态：";
        if (!savedLlmUrl.isEmpty() && !savedLlmKey.isEmpty()) {
            llmStatus += "已配置（本地不匹配时自动调用云端）";
        } else if (!savedLlmUrl.isEmpty() || !savedLlmKey.isEmpty()) {
            llmStatus += "配置不完整";
        } else {
            llmStatus += "未配置（仅用本地关键词匹配）";
        }
        tvLlmStatus.setText(llmStatus);

        btnSaveLlm.setOnClickListener(v -> {
            String url = AppConfig.normalizeBaseUrl(etLlmBaseUrl.getText().toString());
            String key = etLlmApiKey.getText().toString().trim();
            String model = etLlmModel.getText().toString().trim();
            prefs.edit()
                    .putString(AppConfig.KEY_LLM_BASE_URL, url)
                    .putString(AppConfig.KEY_LLM_API_KEY, key)
                    .putString(AppConfig.KEY_LLM_MODEL, model.isEmpty() ? "deepseek-chat" : model)
                    .apply();
            String statusText = "状态：";
            if (!url.isEmpty() && !key.isEmpty()) {
                statusText += "已配置（本地不匹配时自动调用云端）";
            } else {
                statusText += !url.isEmpty() || !key.isEmpty() ? "配置不完整" : "未配置（仅用本地关键词匹配）";
            }
            tvLlmStatus.setText(statusText);
            Toast.makeText(this, "LLM 配置已保存", Toast.LENGTH_SHORT).show();
        });

        Button btnDataCollection = pageSettingsView.findViewById(R.id.btn_data_collection);
        btnDataCollection.setOnClickListener(v -> startActivity(
                new android.content.Intent(this, com.example.voicenavigation.collection.DataCollectionActivity.class)));
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
    }

    private void initServices() {
        // 唯一的语音识别实例，供两种模式共用
        speechManager = new BaiduSpeechManager(this);

        navigationManager = new NavigationManager(this);
        navigationManager.setNavigationCallback(this);
        appDatabase = AppDatabase.getInstance(this);
        handler = new Handler(Looper.getMainLooper());

        SharedPreferences prefs = AppConfig.prefs(this);
        String savedUrl = AppConfig.normalizeBaseUrl(
                prefs.getString(AppConfig.KEY_PREVIEW_SERVER_BASE_URL, TripPreviewService.DEFAULT_BASE_URL));
        tripPreviewService = new TripPreviewService(savedUrl);
        initTts();

        // LLM Function Calling 客户端（云端兜底，需要网络）
        LLMFunctionCaller llmCaller = new LLMFunctionCaller(this);

        // 语音交互管理器（双模式：TEXT_INPUT + COMMAND），共用同一个 speechManager
        voiceInteractionManager = new VoiceInteractionManager(this, speechManager, baiduTts, llmCaller);
        voiceInteractionManager.setTextInputListener(this);      // 蓝色按钮的 STT → 搜索框
        voiceInteractionManager.setCommandExecutor(this);        // 橙色按钮的 Function Calling
        voiceInteractionManager.setVoiceEventListener(new VoiceInteractionManager.VoiceEventListener() {
            @Override public void onListeningStarted() {}
            @Override public void onListeningStopped() {}
            @Override public void onPartialResultReceived(String text) {}
            @Override public void onPipelineStage(String stage) {
                // 实时更新橙色按钮文字，显示流水线进度
                runOnUiThread(() -> tvVoiceCommandHint.setText(stage));
            }
        });
    }

    private void initTts() {
        baiduTts = new BaiduTtsManager(this,
                getString(R.string.baidu_speech_api_key),
                getString(R.string.baidu_speech_secret_key));
        baiduTts.setCallback(new BaiduTtsManager.TtsCallback() {
            @Override public void onTtsReady() { Log.d(TAG, "TTS ready"); }
            @Override public void onTtsError(String error) { Log.e(TAG, "TTS error: " + error); }
        });
        baiduTts.init();
    }

    private void speak(String text) {
        if (text == null || text.isEmpty() || text.equals(lastSpokenInstruction)) return;
        lastSpokenInstruction = text;
        if (baiduTts != null) {
            baiduTts.speak(text);
            showStopTtsButton();
        }
    }

    private void speakForce(String text) {
        if (text == null || text.isEmpty()) return;
        if (baiduTts != null) {
            baiduTts.speak(text);
            showStopTtsButton();
        }
    }

    private void showStopTtsButton() {
        runOnUiThread(() -> {
            if (btnStopTts != null) btnStopTts.setVisibility(View.VISIBLE);
        });
    }

    private void initMap() {
        if (mMap == null) return;

        MyLocationStyle myLocationStyle = new MyLocationStyle();
        myLocationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER);
        myLocationStyle.interval(2000);
        mMap.setMyLocationStyle(myLocationStyle);
        mMap.getUiSettings().setMyLocationButtonEnabled(false);
        enableMapLocation();

        mMap.setOnMyLocationChangeListener(location -> {
            if (location == null) return;
            boolean shouldMoveCamera = currentLocation == null;
            currentLocation = new LatLng(location.getLatitude(), location.getLongitude());
            if (shouldMoveCamera) {
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLocation, 15));
            }
        });

        mMap.setOnMapClickListener(latLng -> {
            if (navigationManager != null && navigationManager.isNavigating()) return;
            setDestination(latLng, latLng.latitude + ", " + latLng.longitude);
            etDestination.setText("");
            etDestination.setHint("已在地图上选点");
        });
    }

    private void enableMapLocation() {
        if (mMap != null && checkLocationPermission() && hasValidAmapKey()) {
            try {
                mMap.setMyLocationEnabled(true);
            } catch (SecurityException e) {
                Log.e(TAG, "Enable map location failed", e);
            }
        }
    }

    private void locateMe() {
        if (!hasValidAmapKey()) {
            Toast.makeText(this, "高德Key未配置：请在 local.properties 添加 amap.api.key", Toast.LENGTH_LONG).show();
            return;
        }
        if (!checkLocationPermission()) {
            requestPermissions();
            Toast.makeText(this, R.string.permission_location_denied, Toast.LENGTH_SHORT).show();
            return;
        }
        enableMapLocation();
        if (navigationManager != null) {
            navigationManager.requestCurrentLocation();
        }
        if (mMap != null && currentLocation != null) {
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLocation, 16));
        } else {
            Toast.makeText(this, "正在获取当前位置", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean requestPermissions() {
        List<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (!checkLocationPermission()) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (android.os.Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this,
                    permissions.toArray(new String[0]),
                    REQUEST_PERMISSIONS_CODE
            );
        }
        return permissions.isEmpty();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            if (checkLocationPermission() && hasValidAmapKey()) {
                enableMapLocation();
                if (navigationManager != null) {
                    navigationManager.requestCurrentLocation();
                }
            }
            if (!checkAudioPermission() || !checkLocationPermission()) {
                Toast.makeText(this, "部分权限未授予，相关功能可能不可用", Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean checkAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean checkLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void searchDestination(String keyword) {
        if (!hasValidAmapKey()) {
            hideSuggestions();
            Toast.makeText(this, "高德Key未配置，无法搜索地点", Toast.LENGTH_SHORT).show();
            return;
        }

        PoiSearch.Query query = new PoiSearch.Query(keyword, "", "");
        query.setPageSize(10);
        query.setPageNum(0);
        query.setCityLimit(false);

        try {
            if (poiSearch == null) {
                poiSearch = new PoiSearch(this, query);
                poiSearch.setOnPoiSearchListener(this);
            } else {
                poiSearch.setQuery(query);
            }
            poiSearch.searchPOIAsyn();
        } catch (Exception e) {
            Log.e(TAG, "Search failed", e);
            Toast.makeText(this, "搜索失败，请稍后重试", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPoiSearched(PoiResult poiResult, int rCode) {
        if (rCode == 1000 && poiResult != null) {
            poiResults = poiResult.getPois();
            if (poiResults == null || poiResults.isEmpty()) {
                hideSuggestions();
                if (autoStartNavigationAfterSearch) {
                    speakForce("未找到目的地，请换个名称试试");
                    autoStartNavigationAfterSearch = false;
                    pendingVoiceDestination = null;
                } else {
                    Toast.makeText(this, "未找到匹配地点", Toast.LENGTH_SHORT).show();
                }
            } else {
                // 语音导航模式：自动选择第一个结果，不弹搜索列表
                if (autoStartNavigationAfterSearch) {
                    autoStartNavigationAfterSearch = false;
                    hideSuggestions();  // 语音模式不弹列表
                    etDestination.setText("");  // 清空搜索框残留

                    PoiItem firstItem = poiResults.get(0);
                    LatLonPoint point = firstItem.getLatLonPoint();
                    LatLng latLng = new LatLng(point.getLatitude(), point.getLongitude());
                    // 设置 isSelectingDestination 防止 setDestination 中的
                    // etDestination.setText() 触发 TextWatcher 再次发起 POI 搜索
                    isSelectingDestination = true;
                    setDestination(latLng, firstItem.getTitle());
                    isSelectingDestination = false;
                    saveVoiceRecord(firstItem.getTitle());

                    // 播报 + Toast 确认目的地
                    String confirmMsg = "为您找到" + firstItem.getTitle() + "，开始导航";
                    if (voiceInteractionManager != null) {
                        voiceInteractionManager.speakAndToast(confirmMsg);
                    } else {
                        speakForce(confirmMsg);
                    }

                    // TTS 播报队列自动顺序播放，无需延迟
                    if (currentLocation != null) {
                        layoutNavInfo.setVisibility(View.VISIBLE);
                        navigationManager.planRoute(currentLocation, selectedDestLatLng, selectedDestName);
                    } else {
                        locateMe();
                        speakForce("正在获取当前位置，请稍后");
                    }
                } else {
                    showSuggestions(poiResults);
                }
            }
        } else {
            hideSuggestions();
            if (autoStartNavigationAfterSearch) {
                speakForce("地点搜索失败，错误码：" + rCode);
                autoStartNavigationAfterSearch = false;
                pendingVoiceDestination = null;
            } else {
                Toast.makeText(this, "地点搜索失败，错误码：" + rCode, Toast.LENGTH_SHORT).show();
            }
            Log.e(TAG, "POI search failed, rCode=" + rCode);
        }
    }

    @Override
    public void onPoiItemSearched(PoiItem poiItem, int rCode) {}

    private void setDestination(LatLng latLng, String name) {
        selectedDestLatLng = latLng;
        selectedDestName = name;
        addDestinationMarker(latLng);
        if (mMap != null) {
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16));
        }
        etDestination.setText(name);
        etDestination.setSelection(name.length());
    }

    private void toggleNavigation() {
        if (!checkLocationPermission()) {
            requestPermissions();
            Toast.makeText(this, R.string.permission_location_denied, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasValidAmapKey()) {
            Toast.makeText(this, "高德Key未配置，无法使用导航", Toast.LENGTH_SHORT).show();
            return;
        }
        if (navigationManager == null) {
            Toast.makeText(this, "导航服务未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        if (navigationManager.isNavigating()) {
            navigationManager.stopNavigation();
            btnStartNavigation.setText(R.string.start_navigation);
            clearRouteDisplay();
            return;
        }
        if (selectedDestLatLng == null) {
            Toast.makeText(this, "请先选择目的地", Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentLocation == null) {
            locateMe();
            Toast.makeText(this, "正在获取当前位置，请稍后再开始导航", Toast.LENGTH_SHORT).show();
            return;
        }
        layoutNavInfo.setVisibility(View.VISIBLE);
        saveVoiceRecord(selectedDestName);
        navigationManager.planRoute(currentLocation, selectedDestLatLng, selectedDestName);
    }

    private void sendTripPreview() {
        String previewBaseUrl = AppConfig.normalizeBaseUrl(
                AppConfig.prefs(this).getString(AppConfig.KEY_PREVIEW_SERVER_BASE_URL, TripPreviewService.DEFAULT_BASE_URL));
        if (previewBaseUrl.isEmpty()) {
            Toast.makeText(this, "请先在设置中填写后端服务地址", Toast.LENGTH_SHORT).show();
            return;
        }
        tripPreviewService.setBaseUrl(previewBaseUrl);
        if (selectedDestLatLng == null) {
            Toast.makeText(this, R.string.preview_no_destination, Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentLocation == null) {
            locateMe();
            Toast.makeText(this, R.string.preview_no_location, Toast.LENGTH_SHORT).show();
            return;
        }
        tripPreviewService.sendPreviewRequest(
                currentLocation.latitude, currentLocation.longitude,
                selectedDestLatLng.latitude, selectedDestLatLng.longitude,
                new TripPreviewService.PreviewCallback() {
                    @Override public void onSuccess(String response) {
                        parseAndShowPreviewResult(response);
                    }

                    @Override public void onError(String error) {
                        Toast.makeText(MainActivity.this, "行前预览失败：" + error, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void parseAndShowPreviewResult(String responseJson) {
        try {
            org.json.JSONObject root = new org.json.JSONObject(responseJson);
            if (!root.optBoolean("success", false)) {
                Toast.makeText(this, "行前预览返回失败", Toast.LENGTH_SHORT).show();
                return;
            }
            org.json.JSONObject data = root.optJSONObject("data");
            if (data == null) {
                Toast.makeText(this, "行前预览数据为空", Toast.LENGTH_SHORT).show();
                return;
            }
            String broadcastText = data.optString("text", "");
            org.json.JSONObject routeSummary = data.optJSONObject("route_summary");
            org.json.JSONArray keyNodes = data.optJSONArray("key_nodes");
            if (!broadcastText.isEmpty()) {
                speakForce("行前预览：" + broadcastText);
            }
            showPreviewDialog(broadcastText, routeSummary, keyNodes);
        } catch (org.json.JSONException e) {
            Log.e(TAG, "Parse preview failed", e);
            Toast.makeText(this, "行前预览解析失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void showPreviewDialog(String broadcastText,
                                   org.json.JSONObject routeSummary,
                                   org.json.JSONArray keyNodes) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_preview_result, null);
        TextView tvPreviewText = dialogView.findViewById(R.id.tv_preview_text);
        TextView tvPreviewSummary = dialogView.findViewById(R.id.tv_preview_summary);
        LinearLayout layoutKeyNodes = dialogView.findViewById(R.id.layout_key_nodes);
        Button btnSpeak = dialogView.findViewById(R.id.btn_preview_speak);
        Button btnClose = dialogView.findViewById(R.id.btn_preview_close);

        tvPreviewText.setText(broadcastText.isEmpty() ? "暂无播报文案" : broadcastText);
        String summaryText = "";
        if (routeSummary != null) {
            summaryText = "总距离：" + routeSummary.optString("total_distance", "未知")
                    + "\n预计时间：" + routeSummary.optString("total_duration", "未知")
                    + "\n关键节点数：" + routeSummary.optInt("key_node_count", 0);
        }
        tvPreviewSummary.setText(summaryText.isEmpty() ? "暂无概要" : summaryText);

        layoutKeyNodes.removeAllViews();
        if (keyNodes != null && keyNodes.length() > 0) {
            for (int i = 0; i < keyNodes.length(); i++) {
                org.json.JSONObject node = keyNodes.optJSONObject(i);
                if (node == null) continue;
                TextView tvNode = new TextView(this);
                tvNode.setTextSize(14);
                tvNode.setTextColor(getResources().getColor(android.R.color.black));
                tvNode.setPadding(0, 8, 0, 8);
                StringBuilder sb = new StringBuilder();
                sb.append("节点 ").append(i + 1).append("：");
                sb.append(node.optString("relative_direction", ""));
                sb.append(node.optString("action", ""));
                if (node.has("assistant_action")) {
                    sb.append("（").append(node.optString("assistant_action")).append("）");
                }
                String instruction = node.optString("instruction", "");
                if (!instruction.isEmpty()) {
                    sb.append("\n").append(instruction);
                }
                tvNode.setText(sb.toString());
                layoutKeyNodes.addView(tvNode);
                if (i < keyNodes.length() - 1) {
                    View divider = new View(this);
                    divider.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1));
                    divider.setBackgroundColor(0xFFE0E0E0);
                    layoutKeyNodes.addView(divider);
                }
            }
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();
        btnSpeak.setOnClickListener(v -> {
            if (!broadcastText.isEmpty()) speakForce("行前预览：" + broadcastText);
        });
        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void drawRoute(List<LatLng> points) {
        if (mMap == null || points == null || points.isEmpty()) return;
        if (routePolyline != null) {
            routePolyline.remove();
            routePolyline = null;
        }
        PolylineOptions options = new PolylineOptions().addAll(points).color(0xFF3B8EFF).width(12);
        routePolyline = mMap.addPolyline(options);
    }

    private void clearRouteDisplay() {
        if (routePolyline != null) {
            routePolyline.remove();
            routePolyline = null;
        }
        layoutNavInfo.setVisibility(View.GONE);
        clearMarkers();
    }

    private void addDestinationMarker(LatLng latLng) {
        if (mMap == null) return;
        if (destinationMarker != null) destinationMarker.remove();
        destinationMarker = mMap.addMarker(new MarkerOptions()
                .position(latLng)
                .title("目的地")
                .snippet(selectedDestName)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
        destinationMarker.showInfoWindow();
    }

    private void clearMarkers() {
        if (destinationMarker != null) {
            destinationMarker.remove();
            destinationMarker = null;
        }
    }

    private void saveVoiceRecord(String content) {
        if (appDatabase == null || content == null) return;
        new Thread(() -> {
            try {
                VoiceRecord record = new VoiceRecord();
                record.setContent(content);
                record.setFilePath("");
                record.setDestination(etDestination.getText().toString());
                record.setTimestamp(System.currentTimeMillis());
                appDatabase.voiceRecordDao().insert(record);
            } catch (Exception e) {
                Log.e(TAG, "Failed to save voice record", e);
            }
        }).start();
    }

    // ==================== TextInputListener（语音转文字 → 搜索框）====================

    @Override
    public void onTextResult(@NonNull String result) {
        String cleaned = cleanSpeechText(result);
        etDestination.setText(cleaned);
        etDestination.setSelection(cleaned.length());
        if (!cleaned.isEmpty()) {
            searchDestination(cleaned);
            Toast.makeText(this, "已识别：" + cleaned, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onTextPartial(@NonNull String partial) {
        String cleaned = cleanSpeechText(partial);
        etDestination.setText(cleaned);
        etDestination.setSelection(cleaned.length());
    }

    // ==================== CommandExecutor 实现（Function Calling）====================

    @Override
    public void executeNavigateTo(String destination) {
        if (!hasValidAmapKey()) {
            speakForce("高德地图Key未配置，无法导航");
            return;
        }
        pendingVoiceDestination = destination;
        autoStartNavigationAfterSearch = true;
        searchDestination(destination);
    }

    @Override
    public void executeStartObstacleAvoidance() {
        isObstacleRunning = true;
        Intent intent = new Intent(this, VisionTestActivity.class);
        startActivity(intent);
    }

    @Override
    public void executeStopNavigation() {
        if (navigationManager != null && navigationManager.isNavigating()) {
            navigationManager.stopNavigation();
            btnStartNavigation.setText(R.string.start_navigation);
            clearRouteDisplay();
        }
    }

    @Override
    public void executeStopObstacleAvoidance() {
        // 发送广播通知 VisionTestActivity 关闭
        Intent intent = new Intent("com.example.voicenavigation.ACTION_STOP_OBSTACLE");
        sendBroadcast(intent);
        isObstacleRunning = false;
    }

    @Override
    public void executeWhereAmI() {
        // TTS 播报已在 VoiceInteractionManager 中完成，此处执行额外操作
        if (currentLocation != null) {
            if (mMap != null) {
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLocation, 16));
            }
        } else {
            locateMe();
        }
    }

    @Override
    public void executeRepeatLast() {
        // TTS 播报已在 VoiceInteractionManager 中完成
    }

    @Override
    public void executePreviewRoute() {
        sendTripPreview();
    }

    @Override
    public void executeQueryStatus() {
        // TTS 播报已在 VoiceInteractionManager 中完成
    }

    @Override
    public void executeTextSearch(String text) {
        String cleaned = cleanSpeechText(text);
        etDestination.setText(cleaned);
        etDestination.setSelection(cleaned.length());
        if (!cleaned.isEmpty()) {
            searchDestination(cleaned);
        }
    }

    @Override
    public void executeUnknown(String text) {
        // 未识别的指令，作为普通搜索尝试一次
        String cleaned = cleanSpeechText(text);
        if (cleaned.length() >= 2) {
            etDestination.setText(cleaned);
            etDestination.setSelection(cleaned.length());
            searchDestination(cleaned);
        }
    }

    @Override
    public String getLastSpokenText() {
        return lastSpokenInstruction;
    }

    @Override
    public boolean isNavigating() {
        return navigationManager != null && navigationManager.isNavigating();
    }

    @Override
    public boolean isObstacleAvoiding() {
        return isObstacleRunning;
    }

    @Override
    public String getCurrentLocationDescription() {
        if (lastAddress != null && !lastAddress.isEmpty()) {
            return lastAddress;
        }
        if (currentLocation != null) {
            return currentLocation.latitude + "，" + currentLocation.longitude;
        }
        return null;
    }

    private String cleanSpeechText(String result) {
        if (result == null) return "";
        return result.replaceAll("[。 ，、！；：,.!?;:]*$", "").trim();
    }

    @Override
    public void onLocationUpdated(Location location, String address) {
        currentLocation = new LatLng(location.getLatitude(), location.getLongitude());
        if (address != null && !address.isEmpty()) {
            lastAddress = address;
        }
    }

    @Override
    public void onRouteReady(List<LatLng> routePoints, float totalDistance, float totalDuration, List<String> instructions) {
        drawRoute(routePoints);
        tvNavDistance.setText(formatDistance(totalDistance));
        tvNavDuration.setText(formatDuration(totalDuration));
        if (instructions != null && !instructions.isEmpty()) {
            tvNavInstruction.setText(instructions.get(0));
            speak(instructions.get(0));
        }
        if (mMap != null && currentLocation != null) {
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLocation, 14));
        }
        btnStartNavigation.setText(R.string.stop_navigation);
    }

    @Override
    public void onNavigationInfoUpdated(float remainingDistance, float remainingDuration, String nextInstruction) {
        tvNavDistance.setText(formatDistance(remainingDistance));
        tvNavDuration.setText(formatDuration(remainingDuration));
        if (nextInstruction != null && !nextInstruction.isEmpty()) {
            tvNavInstruction.setText(nextInstruction);
            speak(nextInstruction);
        }
    }

    @Override
    public void onReRouting() {
        speakForce("正在重新规划步行路线");
    }

    @Override
    public void onArrived() {
        Toast.makeText(this, "已到达目的地附近", Toast.LENGTH_LONG).show();
        speakForce("您已到达目的地附近");
        btnStartNavigation.setText(R.string.start_navigation);
        clearRouteDisplay();
        selectedDestLatLng = null;
        selectedDestName = null;
    }

    @Override public void onNavigationStarted() { Log.d(TAG, "Nav started"); }

    @Override
    public void onNavigationStopped() {
        lastSpokenInstruction = null;
        btnStartNavigation.setText(R.string.start_navigation);
        clearRouteDisplay();
        selectedDestLatLng = null;
        selectedDestName = null;
    }

    @Override
    public void onNavigationError(String error) {
        Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
        layoutNavInfo.setVisibility(View.GONE);
    }

    private String formatDistance(float meters) {
        if (meters < 50) return "即将到达";
        if (meters < 1000) return (int) meters + "m";
        return String.format("%.1fkm", meters / 1000);
    }

    private String formatDuration(float seconds) {
        if (seconds < 60) return "1分钟";
        int minutes = (int) (seconds / 60);
        if (minutes < 60) return minutes + "分钟";
        return (minutes / 60) + "小时" + (minutes % 60) + "分钟";
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mapView != null) mapView.onPause();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null) mapView.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (baiduTts != null) {
            baiduTts.destroy();
            baiduTts = null;
        }
        if (speechManager != null) speechManager.destroyRecognizer();
        if (navigationManager != null) {
            navigationManager.stopNavigation();
            navigationManager.destroyLocationClient();
        }
        if (tripPreviewService != null) tripPreviewService.cancelAll();
        if (mapView != null) mapView.onDestroy();
    }
}
