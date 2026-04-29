package com.example.voicenavigation.navigation;

import android.content.Context;
import android.location.Location;
import android.util.Log;

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;
import com.amap.api.maps.model.LatLng;
import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.route.BusRouteResult;
import com.amap.api.services.route.DriveRouteResult;
import com.amap.api.services.route.RideRouteResult;
import com.amap.api.services.route.RouteSearch;
import com.amap.api.services.route.WalkPath;
import com.amap.api.services.route.WalkRouteResult;
import com.amap.api.services.route.WalkStep;

import java.util.ArrayList;
import java.util.List;

public class NavigationManager implements RouteSearch.OnRouteSearchListener {

    private static final String TAG = "NavigationManager";
    private static final long UPDATE_INTERVAL = 3000;
    private static final float ARRIVAL_DISTANCE = 20;
    private static final float OFF_ROUTE_THRESHOLD = 50;

    private Context context;
    private AMapLocationClient locationClient;
    private AMapLocationClientOption locationOption;
    private RouteSearch routeSearch;
    private NavigationCallback navigationCallback;
    private boolean isNavigating = false;
    private boolean isRerouting = false;
    private LatLng destination;
    private String destinationName;

    private WalkRouteResult currentRouteResult;
    private WalkPath currentWalkPath;
    private List<LatLng> routePoints;
    private float totalDistance;
    private float totalDuration;
    private float remainingDistance;
    private int currentPolylineIndex;
    private List<String> stepInstructions;

    public interface NavigationCallback {
        void onLocationUpdated(Location location);
        void onRouteReady(List<LatLng> routePoints, float totalDistance, float totalDuration, List<String> instructions);
        void onNavigationInfoUpdated(float remainingDistance, float remainingDuration, String nextInstruction);
        void onReRouting();
        void onArrived();
        void onNavigationStarted();
        void onNavigationStopped();
        void onNavigationError(String error);
    }

    public NavigationManager(Context context) {
        this.context = context;
        initLocationClient();
        initRouteSearch();
    }

    private void initRouteSearch() {
        try {
            routeSearch = new RouteSearch(context);
            routeSearch.setRouteSearchListener(this);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create RouteSearch", e);
        }
    }

    private void initLocationClient() {
        try {
            locationClient = new AMapLocationClient(context);
            Log.d(TAG, "AMapLocationClient created successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create AMapLocationClient: " + e.getMessage(), e);
            Log.e(TAG, "===== 定位服务初始化失败 =====");
            Log.e(TAG, "原因: " + e.getClass().getName() + ": " + e.getMessage());
            Log.e(TAG, "请检查: 1. API Key是否已在高德开放平台注册");
            Log.e(TAG, "请检查: 2. 包名 " + context.getPackageName() + " 是否与API Key绑定的包名一致");
            Log.e(TAG, "注册地址: https://lbs.amap.com/dev/key/app");
            return;
        }

        try {
            locationOption = new AMapLocationClientOption();
            locationOption.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
            locationOption.setInterval(UPDATE_INTERVAL);
            locationOption.setNeedAddress(true);
            locationOption.setWifiScan(true);
            locationOption.setLocationCacheEnable(false);
            Log.d(TAG, "AMapLocationClientOption configured successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to configure AMapLocationClientOption", e);
            locationClient = null;
            return;
        }

        locationClient.setLocationListener(new AMapLocationListener() {
            @Override
            public void onLocationChanged(AMapLocation aMapLocation) {
                if (aMapLocation != null) {
                    if (aMapLocation.getErrorCode() == 0) {
                        Location location = new Location("amap");
                        location.setLatitude(aMapLocation.getLatitude());
                        location.setLongitude(aMapLocation.getLongitude());
                        location.setAccuracy(aMapLocation.getAccuracy());
                        location.setAltitude(aMapLocation.getAltitude());
                        location.setSpeed(aMapLocation.getSpeed());
                        location.setTime(aMapLocation.getTime());

                        if (isNavigating && routePoints != null && !routePoints.isEmpty()) {
                            updateNavigationProgress(location);
                        }

                        if (navigationCallback != null) {
                            navigationCallback.onLocationUpdated(location);
                        }
                    } else {
                        Log.e(TAG, "Location error: " + aMapLocation.getErrorCode() + " - " + aMapLocation.getErrorInfo());
                        if (navigationCallback != null && !isNavigating) {
                            navigationCallback.onNavigationError("定位失败: " + aMapLocation.getErrorInfo());
                        }
                    }
                }
            }
        });
    }

    private void updateNavigationProgress(Location currentLocation) {
        LatLng currentLatLng = new LatLng(currentLocation.getLatitude(), currentLocation.getLongitude());
        float minDist = Float.MAX_VALUE;
        int nearestIdx = currentPolylineIndex;

        int startSearch = Math.max(0, currentPolylineIndex - 5);
        int endSearch = Math.min(routePoints.size(), currentPolylineIndex + 50);
        for (int i = startSearch; i < endSearch; i++) {
            float dist = calculateDistance(currentLatLng, routePoints.get(i));
            if (dist < minDist) {
                minDist = dist;
                nearestIdx = i;
            }
        }

        if (minDist > OFF_ROUTE_THRESHOLD && !isRerouting) {
            Log.w(TAG, "Off route detected! Distance from route: " + minDist + "m");
            triggerReroute(currentLocation);
            return;
        }

        currentPolylineIndex = nearestIdx;

        remainingDistance = 0;
        for (int i = currentPolylineIndex; i < routePoints.size() - 1; i++) {
            remainingDistance += calculateDistance(routePoints.get(i), routePoints.get(i + 1));
        }

        float remainingDuration = (remainingDistance / totalDistance) * totalDuration;

        String nextInstruction = "";
        if (stepInstructions != null) {
            int stepIdx = 0;
            float accumulated = 0;
            for (WalkStep step : currentWalkPath.getSteps()) {
                List<LatLonPoint> pts = step.getPolyline();
                if (pts != null) {
                    accumulated += pts.size();
                    if (accumulated > currentPolylineIndex) {
                        if (stepIdx < stepInstructions.size()) {
                            nextInstruction = stepInstructions.get(stepIdx);
                        }
                        break;
                    }
                    stepIdx++;
                }
            }
        }

        if (navigationCallback != null) {
            navigationCallback.onNavigationInfoUpdated(remainingDistance, remainingDuration, nextInstruction);
        }

        Log.d(TAG, "Walk nav progress: remaining=" + remainingDistance + "m, nearestIdx=" + nearestIdx);

        if (remainingDistance < ARRIVAL_DISTANCE) {
            Log.d(TAG, "Arrived at destination!");
            stopNavigation();
            if (navigationCallback != null) {
                navigationCallback.onArrived();
            }
        }
    }

    private void triggerReroute(Location currentLocation) {
        isRerouting = true;
        if (navigationCallback != null) {
            navigationCallback.onReRouting();
        }
        Log.d(TAG, "Re-routing from current position");
        planRoute(
                new LatLng(currentLocation.getLatitude(), currentLocation.getLongitude()),
                destination,
                destinationName
        );
    }

    public void planRoute(LatLng origin, LatLng dest, String destName) {
        this.destination = dest;
        this.destinationName = destName;

        if (routeSearch == null) {
            if (navigationCallback != null) {
                navigationCallback.onNavigationError("路线规划服务未初始化");
            }
            return;
        }

        LatLonPoint from = new LatLonPoint(origin.latitude, origin.longitude);
        LatLonPoint to = new LatLonPoint(dest.latitude, dest.longitude);
        RouteSearch.FromAndTo fromAndTo = new RouteSearch.FromAndTo(from, to);
        RouteSearch.WalkRouteQuery query = new RouteSearch.WalkRouteQuery(fromAndTo, RouteSearch.WalkDefault);

        Log.d(TAG, "Planning walk route from " + origin.latitude + "," + origin.longitude + " to " + dest.latitude + "," + dest.longitude);
        try {
            routeSearch.calculateWalkRouteAsyn(query);
        } catch (Exception e) {
            Log.e(TAG, "Failed to calculate walk route", e);
            isRerouting = false;
            if (navigationCallback != null) {
                navigationCallback.onNavigationError("步行路线规划失败");
            }
        }
    }

    @Override
    public void onWalkRouteSearched(WalkRouteResult result, int rCode) {
        Log.d(TAG, "Walk route search result, rCode: " + rCode);
        if (rCode == 1000) {
            if (result == null || result.getPaths() == null || result.getPaths().isEmpty()) {
                isRerouting = false;
                if (navigationCallback != null) {
                    navigationCallback.onNavigationError("未找到可行走路线");
                }
                return;
            }

            currentRouteResult = result;
            currentWalkPath = result.getPaths().get(0);
            totalDistance = currentWalkPath.getDistance();
            totalDuration = currentWalkPath.getDuration();
            remainingDistance = totalDistance;

            List<WalkStep> steps = currentWalkPath.getSteps();
            routePoints = new ArrayList<>();
            stepInstructions = new ArrayList<>();

            for (WalkStep step : steps) {
                String instruction = step.getInstruction();
                if (instruction != null && !instruction.isEmpty()) {
                    stepInstructions.add(instruction);
                }
                List<LatLonPoint> stepPoints = step.getPolyline();
                if (stepPoints != null) {
                    for (LatLonPoint point : stepPoints) {
                        routePoints.add(new LatLng(point.getLatitude(), point.getLongitude()));
                    }
                }
            }

            currentPolylineIndex = 0;

            if (!isNavigating) {
                isNavigating = true;
            }

            Log.d(TAG, "Walk route found: " + routePoints.size() + " points, " + totalDistance + "m, " + totalDuration + "s");

            if (locationClient != null && !isRerouting) {
                locationClient.startLocation();
            }

            if (navigationCallback != null) {
                navigationCallback.onRouteReady(routePoints, totalDistance, totalDuration, stepInstructions);
                if (!isRerouting) {
                    navigationCallback.onNavigationStarted();
                }
            }

            isRerouting = false;
        } else {
            Log.e(TAG, "Walk route search failed, error code: " + rCode);
            isRerouting = false;
            String errorMsg = "步行路线规划失败";
            if (rCode == 2001) {
                errorMsg = "步行路线规划失败：网络错误";
            } else if (rCode == 2002) {
                errorMsg = "步行路线规划失败：参数错误";
            } else if (rCode == 2003) {
                errorMsg = "步行路线规划失败：无权限";
            }
            if (navigationCallback != null) {
                navigationCallback.onNavigationError(errorMsg);
            }
        }
    }

    @Override
    public void onDriveRouteSearched(DriveRouteResult driveRouteResult, int i) {
    }

    @Override
    public void onBusRouteSearched(BusRouteResult busRouteResult, int i) {
    }

    @Override
    public void onRideRouteSearched(RideRouteResult rideRouteResult, int i) {
    }

    public void startNavigation(LatLng destination) {
        this.destination = destination;
        if (currentRouteResult != null) {
            isNavigating = true;
            if (locationClient != null) {
                locationClient.startLocation();
            }
            if (navigationCallback != null) {
                navigationCallback.onNavigationStarted();
            }
        }
    }

    public void stopNavigation() {
        isNavigating = false;
        isRerouting = false;
        if (locationClient != null) {
            locationClient.stopLocation();
        }
        currentRouteResult = null;
        currentWalkPath = null;
        routePoints = null;
        stepInstructions = null;
        remainingDistance = 0;
        currentPolylineIndex = 0;

        if (navigationCallback != null) {
            navigationCallback.onNavigationStopped();
        }
        Log.d(TAG, "Walk navigation stopped");
    }

    public boolean isNavigating() {
        return isNavigating;
    }

    public LatLng getDestination() {
        return destination;
    }

    public void setNavigationCallback(NavigationCallback callback) {
        this.navigationCallback = callback;
    }

    public void destroyLocationClient() {
        if (locationClient != null) {
            locationClient.onDestroy();
            locationClient = null;
        }
    }

    private float calculateDistance(LatLng p1, LatLng p2) {
        double R = 6371000;
        double dLat = Math.toRadians(p2.latitude - p1.latitude);
        double dLng = Math.toRadians(p2.longitude - p1.longitude);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(p1.latitude)) * Math.cos(Math.toRadians(p2.latitude))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return (float) (R * c);
    }
}
