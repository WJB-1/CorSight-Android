package com.example.voicenavigation;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.amap.api.location.AMapLocationClient;
import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.MyLocationStyle;
import com.amap.api.maps.model.Polyline;
import com.amap.api.maps.model.PolylineOptions;
import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.core.PoiItem;
import com.amap.api.services.poisearch.PoiResult;
import com.amap.api.services.poisearch.PoiSearch;
import com.example.voicenavigation.data.AppDatabase;
import com.example.voicenavigation.data.VoiceRecord;
import com.example.voicenavigation.data.VoiceRecordAdapter;
import com.example.voicenavigation.navigation.NavigationManager;
import com.example.voicenavigation.network.TripPreviewService;
import com.example.voicenavigation.stt.BaiduSpeechManager;
import com.example.voicenavigation.stt.BaiduTtsManager;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.List;

public class MainActivity extends AppCompatActivity implements
        BaiduSpeechManager.STTCallback, NavigationManager.NavigationCallback,
        PoiSearch.OnPoiSearchListener {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_PERMISSIONS_CODE = 100;

    private AMap mMap;
    private MapView mapView;
    private BaiduSpeechManager speechManager;
    private NavigationManager navigationManager;
    private AppDatabase appDatabase;
    private BaiduTtsManager baiduTts;
    private Handler handler;

    private Button btnVoiceInput;
    private Button btnSearch;
    private Button btnStartNavigation;
    private Button btnPreviewRoute;
    private EditText etDestination;
    private TextView tvStatus;

    private LinearLayout layoutNavInfo;
    private TextView tvNavDistance;
    private TextView tvNavDuration;
    private TextView tvNavInstruction;

    private boolean isListening = false;
    private LatLng currentLocation;
    private Marker destinationMarker;
    private Polyline routePolyline;

    private PoiSearch poiSearch;
    private List<PoiItem> poiResults;
    private LatLng selectedDestLatLng;
    private String selectedDestName;
    private String lastSpokenInstruction;

    private BottomNavigationView bottomNav;
    private LinearLayout pageMap;
    private View pageHistoryView;
    private View pageSettingsView;
    private RecyclerView rvHistory;
    private TextView tvHistoryEmpty;
    private VoiceRecordAdapter historyAdapter;
    private TripPreviewService tripPreviewService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        AMapLocationClient.updatePrivacyShow(this, true, true);
        AMapLocationClient.updatePrivacyAgree(this, true);

        initViews();
        if (requestPermissions()) {
            initServices();
        }

        mapView = findViewById(R.id.map);
        mapView.onCreate(savedInstanceState);
        mMap = mapView.getMap();
        initMap();
    }

    private void initViews() {
        btnVoiceInput = findViewById(R.id.btn_voice_input);
        btnSearch = findViewById(R.id.btn_search);
        btnStartNavigation = findViewById(R.id.btn_start_navigation);
        btnPreviewRoute = findViewById(R.id.btn_preview_route);
        etDestination = findViewById(R.id.et_destination);
        tvStatus = findViewById(R.id.tv_status);

        layoutNavInfo = findViewById(R.id.layout_nav_info);
        tvNavDistance = findViewById(R.id.tv_nav_distance);
        tvNavDuration = findViewById(R.id.tv_nav_duration);
        tvNavInstruction = findViewById(R.id.tv_nav_instruction);

        bottomNav = findViewById(R.id.bottom_nav);
        pageMap = findViewById(R.id.page_map);
        pageHistoryView = findViewById(R.id.page_history);
        pageSettingsView = findViewById(R.id.page_settings);
        rvHistory = findViewById(R.id.rv_history);
        rvHistory.setLayoutManager(new LinearLayoutManager(this));
        tvHistoryEmpty = findViewById(R.id.tv_history_empty);

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

        btnVoiceInput.setOnClickListener(v -> toggleVoiceInput());
        btnSearch.setOnClickListener(v -> {
            hideKeyboard();
            String keyword = etDestination.getText().toString().trim();
            if (!keyword.isEmpty()) {
                searchDestination(keyword);
            } else {
                Toast.makeText(this, "请输入目的地", Toast.LENGTH_SHORT).show();
            }
        });
        btnStartNavigation.setOnClickListener(v -> toggleNavigation());
        btnPreviewRoute.setOnClickListener(v -> sendTripPreview());

        etDestination.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                String keyword = etDestination.getText().toString().trim();
                if (!keyword.isEmpty()) {
                    searchDestination(keyword);
                }
                return true;
            }
            return false;
        });
    }

    private void switchTab(int index) {
        pageMap.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        pageHistoryView.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        pageSettingsView.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        if (index == 1) {
            loadHistory();
        } else if (index == 2) {
            loadSettings();
        }
    }

    private void loadHistory() {
        new Thread(() -> {
            try {
                List<VoiceRecord> records = appDatabase.voiceRecordDao().getAllRecords();
                Log.d(TAG, "loadHistory: found " + (records == null ? 0 : records.size()) + " records");
                runOnUiThread(() -> {
                    if (records == null || records.isEmpty()) {
                        tvHistoryEmpty.setVisibility(View.VISIBLE);
                        rvHistory.setVisibility(View.GONE);
                    } else {
                        tvHistoryEmpty.setVisibility(View.GONE);
                        rvHistory.setVisibility(View.VISIBLE);
                        if (historyAdapter == null) {
                            historyAdapter = new VoiceRecordAdapter(records);
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

    private void loadSettings() {
        TextView tvAmapKey = findViewById(R.id.tv_amap_key);
        tvAmapKey.setText(getString(R.string.amap_api_key));
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
    }

    private void initServices() {
        speechManager = new BaiduSpeechManager(this);
        speechManager.setCallback(this);
        navigationManager = new NavigationManager(this);
        navigationManager.setNavigationCallback(this);
        appDatabase = AppDatabase.getInstance(this);
        handler = new Handler(Looper.getMainLooper());
        tripPreviewService = new TripPreviewService();
        initTts();
    }

    private void initTts() {
        Log.d(TAG, "Initializing BaiduTtsManager...");
        baiduTts = new BaiduTtsManager(this,
                getString(R.string.baidu_speech_api_key),
                getString(R.string.baidu_speech_secret_key));
        baiduTts.setCallback(new BaiduTtsManager.TtsCallback() {
            @Override
            public void onTtsReady() {
                Log.d(TAG, "Baidu TTS ready");
            }

            @Override
            public void onTtsError(String error) {
                Log.e(TAG, "Baidu TTS error: " + error);
            }
        });
        baiduTts.init();
    }

    private void speak(String text) {
        if (text == null || text.isEmpty() || text.equals(lastSpokenInstruction)) {
            return;
        }
        lastSpokenInstruction = text;
        if (baiduTts != null) {
            Log.d(TAG, "TTS speak: " + text);
            baiduTts.speak(text);
        }
    }

    private void speakForce(String text) {
        if (text == null || text.isEmpty()) return;
        if (baiduTts != null) {
            Log.d(TAG, "TTS speakForce: " + text);
            baiduTts.speak(text);
        }
    }

    private void initMap() {
        if (mMap == null) return;

        MyLocationStyle myLocationStyle = new MyLocationStyle();
        myLocationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER);
        myLocationStyle.interval(2000);
        mMap.setMyLocationStyle(myLocationStyle);
        mMap.getUiSettings().setMyLocationButtonEnabled(true);

        if (checkLocationPermission()) {
            mMap.setMyLocationEnabled(true);
        }

        mMap.setOnMyLocationChangeListener(location -> {
            if (currentLocation == null && location != null) {
                currentLocation = new LatLng(location.getLatitude(), location.getLongitude());
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

    private boolean requestPermissions() {
        String[] permissions = {
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.INTERNET,
                Manifest.permission.POST_NOTIFICATIONS
        };

        boolean allGranted = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS_CODE);
        }

        return allGranted;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                Toast.makeText(this, "需要授予必要权限才能使用应用", Toast.LENGTH_LONG).show();
            } else {
                initServices();
                if (mMap != null) {
                    mMap.setMyLocationEnabled(true);
                }
            }
        }
    }

    private void toggleVoiceInput() {
        if (!checkAudioPermission()) {
            Toast.makeText(this, R.string.permission_audio_denied, Toast.LENGTH_SHORT).show();
            return;
        }

        if (isListening) {
            stopListening();
        } else {
            startListening();
        }
    }

    private boolean checkAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startListening() {
        if (speechManager == null) {
            Log.e(TAG, "speechManager is null - services not initialized");
            Toast.makeText(this, "服务尚未初始化，请先授予权限", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.d(TAG, "语音识别状态: " + speechManager.getRecognitionStatus());

        isListening = true;
        btnVoiceInput.setBackgroundColor(ContextCompat.getColor(this, R.color.purple_700));
        tvStatus.setText(R.string.listening);
        speechManager.startListening();
    }

    private void stopListening() {
        isListening = false;
        btnVoiceInput.setBackgroundColor(ContextCompat.getColor(this, R.color.purple_500));
        tvStatus.setText(R.string.speech_hint);
        if (speechManager != null) {
            speechManager.stopListening();
        }
    }

    private void searchDestination(String keyword) {
        Log.d(TAG, "Searching destination: " + keyword);
        tvStatus.setText("正在搜索: " + keyword);

        PoiSearch.Query query = new PoiSearch.Query(keyword, "", "");
        query.setPageSize(20);
        query.setPageNum(0);

        try {
            if (poiSearch == null) {
                poiSearch = new PoiSearch(this, query);
                poiSearch.setOnPoiSearchListener(this);
            } else {
                poiSearch.setQuery(query);
            }
            poiSearch.searchPOIAsyn();
        } catch (Exception e) {
            Log.e(TAG, "Failed to create PoiSearch: " + e.getMessage(), e);
            Toast.makeText(this, "搜索服务异常: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            tvStatus.setText(R.string.speech_hint);
        }
    }

    private void showPoiResultsDialog(List<PoiItem> items) {
        if (items == null || items.isEmpty()) {
            Toast.makeText(this, "未找到相关地点", Toast.LENGTH_SHORT).show();
            tvStatus.setText(R.string.speech_hint);
            return;
        }

        String[] displayItems = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            PoiItem item = items.get(i);
            displayItems[i] = item.getTitle() + "  " + item.getCityName() + item.getAdName();
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择目的地")
                .setItems(displayItems, (dialog, which) -> {
                    PoiItem selected = items.get(which);
                    LatLonPoint point = selected.getLatLonPoint();
                    LatLng latLng = new LatLng(point.getLatitude(), point.getLongitude());
                    setDestination(latLng, selected.getTitle());
                    dialog.dismiss();
                })
                .setNegativeButton("取消", (dialog, which) -> {
                    tvStatus.setText(R.string.speech_hint);
                    dialog.dismiss();
                })
                .setOnCancelListener(dialog -> tvStatus.setText(R.string.speech_hint));

        tvStatus.setText("已找到 " + items.size() + " 个结果");
        builder.show();
    }

    private void setDestination(LatLng latLng, String name) {
        selectedDestLatLng = latLng;
        selectedDestName = name;
        addDestinationMarker(latLng);
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16));
        tvStatus.setText("已选择: " + name);
        etDestination.setText(name);
        etDestination.setSelection(name.length());
    }

    private void toggleNavigation() {
        if (!checkLocationPermission()) {
            Toast.makeText(this, R.string.permission_location_denied, Toast.LENGTH_SHORT).show();
            return;
        }

        if (navigationManager == null) {
            Log.e(TAG, "navigationManager is null - services not initialized");
            Toast.makeText(this, "服务尚未初始化，请先授予权限", Toast.LENGTH_SHORT).show();
            return;
        }

        if (navigationManager.isNavigating()) {
            navigationManager.stopNavigation();
            btnStartNavigation.setText(R.string.start_navigation);
            clearRouteDisplay();
            tvStatus.setText(R.string.speech_hint);
            return;
        }

        if (selectedDestLatLng == null) {
            Toast.makeText(this, "请先搜索并选择目的地", Toast.LENGTH_SHORT).show();
            return;
        }

        if (currentLocation == null) {
            Toast.makeText(this, "正在获取当前位置，请稍后", Toast.LENGTH_SHORT).show();
            return;
        }

        layoutNavInfo.setVisibility(View.VISIBLE);
        tvStatus.setText("正在规划步行路线...");
        saveVoiceRecord(selectedDestName);
        navigationManager.planRoute(currentLocation, selectedDestLatLng, selectedDestName);
    }

    /**
     * 发送行前预览请求：将用户当前定位和目的地发送至后端。
     */
    private void sendTripPreview() {
        if (selectedDestLatLng == null) {
            Toast.makeText(this, R.string.preview_no_destination, Toast.LENGTH_SHORT).show();
            return;
        }

        if (currentLocation == null) {
            Toast.makeText(this, R.string.preview_no_location, Toast.LENGTH_SHORT).show();
            return;
        }

        tvStatus.setText(R.string.preview_requesting);

        tripPreviewService.sendPreviewRequest(
                currentLocation.latitude,
                currentLocation.longitude,
                selectedDestLatLng.latitude,
                selectedDestLatLng.longitude,
                new TripPreviewService.PreviewCallback() {
                    @Override
                    public void onSuccess(String response) {
                        tvStatus.setText(R.string.preview_success);
                        Toast.makeText(MainActivity.this, R.string.preview_success, Toast.LENGTH_LONG).show();
                        Log.d(TAG, "Trip preview response: " + response);
                    }

                    @Override
                    public void onError(String error) {
                        tvStatus.setText(R.string.preview_failed);
                        Toast.makeText(MainActivity.this, R.string.preview_failed + ": " + error, Toast.LENGTH_LONG).show();
                        Log.e(TAG, "Trip preview error: " + error);
                    }
                }
        );
    }

    private void drawRoute(List<LatLng> points) {
        if (mMap == null || points == null || points.isEmpty()) return;

        if (routePolyline != null) {
            routePolyline.remove();
            routePolyline = null;
        }

        PolylineOptions options = new PolylineOptions()
                .addAll(points)
                .color(0xFF3B8EFF)
                .width(12)
                .setDottedLine(false);
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

    private boolean checkLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void addDestinationMarker(LatLng latLng) {
        if (mMap == null) return;

        if (destinationMarker != null) {
            destinationMarker.remove();
        }

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
        new Thread(() -> {
            try {
                VoiceRecord record = new VoiceRecord();
                record.setContent(content);
                record.setFilePath("");
                record.setDestination(etDestination.getText().toString());
                record.setTimestamp(System.currentTimeMillis());
                appDatabase.voiceRecordDao().insert(record);
                Log.d(TAG, "Voice record saved: " + content + " at " + record.getTimestamp());
            } catch (Exception e) {
                Log.e(TAG, "Failed to save voice record", e);
            }
        }).start();
    }

    @Override
    public void onResult(String result) {
        Log.d(TAG, "STT final result: " + result);
        String cleaned = result.replaceAll("[。，、！？；：,.!?;:]*$", "").trim();
        etDestination.setText(cleaned);
        etDestination.setSelection(cleaned.length());
        stopListening();
        searchDestination(cleaned);
    }

    @Override
    public void onPartialResult(String result) {
        Log.d(TAG, "STT partial result: " + result);
        String cleaned = result.replaceAll("[。，、！？；：,.!?;:]*$", "").trim();
        etDestination.setText(cleaned);
        etDestination.setSelection(cleaned.length());
    }

    @Override
    public void onError(String error) {
        Log.e(TAG, "STT error: " + error);
        Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
        stopListening();
    }

    @Override
    public void onListening() {
        Log.d(TAG, "STT listening");
    }

    @Override
    public void onStopped() {
        Log.d(TAG, "STT stopped");
    }

    @Override
    public void onPoiSearched(PoiResult poiResult, int rCode) {
        Log.d(TAG, "POI search result, rCode: " + rCode);
        if (rCode == 1000) {
            poiResults = poiResult.getPois();
            showPoiResultsDialog(poiResults);
        } else {
            Log.e(TAG, "POI search failed, rCode: " + rCode);
            Toast.makeText(this, "搜索失败，错误码: " + rCode, Toast.LENGTH_SHORT).show();
            tvStatus.setText(R.string.speech_hint);
        }
    }

    @Override
    public void onPoiItemSearched(PoiItem poiItem, int rCode) {
    }

    @Override
    public void onLocationUpdated(Location location) {
        currentLocation = new LatLng(location.getLatitude(), location.getLongitude());
        Log.d(TAG, "Location updated: " + currentLocation.latitude + ", " + currentLocation.longitude);
    }

    @Override
    public void onRouteReady(List<LatLng> routePoints, float totalDistance, float totalDuration, List<String> instructions) {
        Log.d(TAG, "Route ready: " + routePoints.size() + " points");
        drawRoute(routePoints);

        String dist = formatDistance(totalDistance);
        String dur = formatDuration(totalDuration);
        tvNavDistance.setText(dist);
        tvNavDuration.setText(dur);

        if (instructions != null && !instructions.isEmpty()) {
            String firstInstruction = instructions.get(0);
            tvNavInstruction.setText(firstInstruction);
            speak(firstInstruction);
        }

        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLocation, 14));
        btnStartNavigation.setText(R.string.stop_navigation);
        tvStatus.setText(getString(R.string.navigating_to) + " " + selectedDestName);
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
        Log.d(TAG, "Re-routing...");
        tvStatus.setText("正在重新规划步行路线...");
        speakForce("正在重新规划步行路线");
    }

    @Override
    public void onArrived() {
        tvStatus.setText("已到达目的地附近");
        Toast.makeText(this, "已到达目的地附近", Toast.LENGTH_LONG).show();
        speakForce("您已到达目的地附近");
        btnStartNavigation.setText(R.string.start_navigation);
        clearRouteDisplay();
        selectedDestLatLng = null;
        selectedDestName = null;
    }

    @Override
    public void onNavigationStarted() {
        Log.d(TAG, "Navigation started");
    }

    @Override
    public void onNavigationStopped() {
        Log.d(TAG, "Navigation stopped");
        lastSpokenInstruction = null;
        btnStartNavigation.setText(R.string.start_navigation);
        clearRouteDisplay();
        selectedDestLatLng = null;
        selectedDestName = null;
    }

    @Override
    public void onNavigationError(String error) {
        Log.e(TAG, "Navigation error: " + error);
        Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
        layoutNavInfo.setVisibility(View.GONE);
        tvStatus.setText(R.string.speech_hint);
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
        int hours = minutes / 60;
        int mins = minutes % 60;
        return hours + "小时" + mins + "分钟";
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) {
            mapView.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mapView != null) {
            mapView.onPause();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null) {
            mapView.onSaveInstanceState(outState);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (baiduTts != null) {
            baiduTts.destroy();
            baiduTts = null;
        }
        if (speechManager != null) {
            speechManager.destroyRecognizer();
        }
        if (navigationManager != null) {
            navigationManager.stopNavigation();
            navigationManager.destroyLocationClient();
        }
        if (tripPreviewService != null) {
            tripPreviewService.cancelAll();
        }
        if (mapView != null) {
            mapView.onDestroy();
        }
    }
}
