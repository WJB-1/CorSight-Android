package com.example.voicenavigation.navigation

import android.content.Context
import android.location.Location
import android.util.Log
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.maps.model.LatLng
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.route.BusRouteResult
import com.amap.api.services.route.DriveRouteResult
import com.amap.api.services.route.RideRouteResult
import com.amap.api.services.route.RouteSearch
import com.amap.api.services.route.WalkPath
import com.amap.api.services.route.WalkRouteResult
import com.amap.api.services.route.WalkStep

class NavigationManager(private val context: Context) : RouteSearch.OnRouteSearchListener {

    /**
     * Navigation callback interface. Kept as an explicit interface (not SAM/functional)
     * so that existing Java callers like MainActivity.java can implement it directly
     * without changes.
     */
    interface NavigationCallback {
        /**
         * @param location location position
         * @param address  address description (e.g. "Beijing Haidian District xxx Road"), may be null
         */
        fun onLocationUpdated(location: Location, address: String?)
        fun onRouteReady(routePoints: List<LatLng>, totalDistance: Float, totalDuration: Float, instructions: List<String>)
        fun onNavigationInfoUpdated(remainingDistance: Float, remainingDuration: Float, nextInstruction: String)
        fun onReRouting()
        fun onArrived()
        fun onNavigationStarted()
        fun onNavigationStopped()
        fun onNavigationError(error: String)
    }

    private var locationClient: AMapLocationClient? = null
    private var locationOption: AMapLocationClientOption? = null
    private var routeSearch: RouteSearch? = null
    private var navigationCallback: NavigationCallback? = null
    private var isNavigating = false
    private var isRerouting = false
    private var destination: LatLng? = null
    private var destinationName: String? = null

    private var currentRouteResult: WalkRouteResult? = null
    private var currentWalkPath: WalkPath? = null
    private var routePoints: List<LatLng>? = null
    private var totalDistance: Float = 0f
    private var totalDuration: Float = 0f
    private var remainingDistance: Float = 0f
    private var currentPolylineIndex: Int = 0
    private var stepInstructions: MutableList<String>? = null

    init {
        initLocationClient()
        initRouteSearch()
    }

    private fun initRouteSearch() {
        try {
            routeSearch = RouteSearch(context)
            routeSearch?.setRouteSearchListener(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create RouteSearch", e)
        }
    }

    private fun initLocationClient() {
        try {
            locationClient = AMapLocationClient(context)
            Log.d(TAG, "AMapLocationClient created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AMapLocationClient: ${e.message}", e)
            Log.e(TAG, "===== 定位服务初始化失败 =====")
            Log.e(TAG, "原因: ${e.javaClass.name}: ${e.message}")
            Log.e(TAG, "请检查: 1. API Key是否已在高德开放平台注册")
            Log.e(TAG, "请检查: 2. 包名 ${context.packageName} 是否与API Key绑定的包名一致")
            Log.e(TAG, "注册地址: https://lbs.amap.com/dev/key/app")
            return
        }

        try {
            locationOption = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                interval = UPDATE_INTERVAL
                isNeedAddress = true
                isWifiScan = true
                setLocationCacheEnable(false)
            }
            Log.d(TAG, "AMapLocationClientOption configured successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure AMapLocationClientOption", e)
            locationClient = null
            return
        }

        locationClient?.setLocationListener { aMapLocation ->
            if (aMapLocation == null) return@setLocationListener
            if (aMapLocation.errorCode == 0) {
                val location = Location("amap").apply {
                    latitude = aMapLocation.latitude
                    longitude = aMapLocation.longitude
                    accuracy = aMapLocation.accuracy
                    altitude = aMapLocation.altitude
                    speed = aMapLocation.speed
                    time = aMapLocation.time
                }

                if (isNavigating && routePoints != null && routePoints!!.isNotEmpty()) {
                    updateNavigationProgress(location)
                }

                var address = aMapLocation.address
                if (address.isNullOrEmpty()) {
                    address = aMapLocation.description
                }

                navigationCallback?.onLocationUpdated(location, address)
            } else {
                val error = "定位失败：${aMapLocation.errorInfo}"
                Log.e(TAG, "$error, code=${aMapLocation.errorCode}")
                if (navigationCallback != null && !isNavigating) {
                    navigationCallback?.onNavigationError(error)
                }
            }
        }
    }

    fun requestCurrentLocation() {
        if (locationClient == null) {
            navigationCallback?.onNavigationError("定位服务未初始化")
            return
        }
        locationClient?.startLocation()
    }

    private fun updateNavigationProgress(currentLocation: Location) {
        val currentLatLng = LatLng(currentLocation.latitude, currentLocation.longitude)
        var minDist = Float.MAX_VALUE
        var nearestIdx = currentPolylineIndex

        val startSearch = Math.max(0, currentPolylineIndex - 5)
        val endSearch = Math.min(routePoints!!.size, currentPolylineIndex + 50)
        for (i in startSearch until endSearch) {
            val dist = calculateDistance(currentLatLng, routePoints!![i])
            if (dist < minDist) {
                minDist = dist
                nearestIdx = i
            }
        }

        if (minDist > OFF_ROUTE_THRESHOLD && !isRerouting) {
            Log.w(TAG, "Off route detected! Distance from route: ${minDist}m")
            triggerReroute(currentLocation)
            return
        }

        currentPolylineIndex = nearestIdx

        remainingDistance = 0f
        for (i in currentPolylineIndex until routePoints!!.size - 1) {
            remainingDistance += calculateDistance(routePoints!![i], routePoints!![i + 1])
        }

        val remainingDuration = if (totalDistance > 0) (remainingDistance / totalDistance) * totalDuration else 0f
        var nextInstruction = ""
        if (stepInstructions != null && currentWalkPath != null) {
            var stepIdx = 0
            var accumulated = 0
            for (step in currentWalkPath!!.steps) {
                val pts = step.polyline
                if (pts != null) {
                    accumulated += pts.size
                    if (accumulated > currentPolylineIndex) {
                        if (stepIdx < stepInstructions!!.size) {
                            nextInstruction = stepInstructions!![stepIdx]
                        }
                        break
                    }
                    stepIdx++
                }
            }
        }

        navigationCallback?.onNavigationInfoUpdated(remainingDistance, remainingDuration, nextInstruction)

        Log.d(TAG, "Walk nav progress: remaining=${remainingDistance}m, nearestIdx=$nearestIdx")

        if (remainingDistance < ARRIVAL_DISTANCE) {
            Log.d(TAG, "Arrived at destination!")
            stopNavigation()
            navigationCallback?.onArrived()
        }
    }

    private fun triggerReroute(currentLocation: Location) {
        isRerouting = true
        navigationCallback?.onReRouting()
        Log.d(TAG, "Re-routing from current position")
        planRoute(
            LatLng(currentLocation.latitude, currentLocation.longitude),
            destination!!,
            destinationName
        )
    }

    fun planRoute(origin: LatLng, dest: LatLng, destName: String?) {
        destination = dest
        destinationName = destName

        if (routeSearch == null) {
            navigationCallback?.onNavigationError("路线规划服务未初始化")
            return
        }

        val from = LatLonPoint(origin.latitude, origin.longitude)
        val to = LatLonPoint(dest.latitude, dest.longitude)
        val fromAndTo = RouteSearch.FromAndTo(from, to)
        val query = RouteSearch.WalkRouteQuery(fromAndTo, RouteSearch.WalkDefault)

        Log.d(TAG, "Planning walk route from ${origin.latitude},${origin.longitude} to ${dest.latitude},${dest.longitude}")
        try {
            routeSearch?.calculateWalkRouteAsyn(query)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate walk route", e)
            isRerouting = false
            navigationCallback?.onNavigationError("步行路线规划失败")
        }
    }

    override fun onWalkRouteSearched(result: WalkRouteResult?, rCode: Int) {
        Log.d(TAG, "Walk route search result, rCode: $rCode")
        if (rCode == 1000) {
            if (result == null || result.paths == null || result.paths.isEmpty()) {
                isRerouting = false
                navigationCallback?.onNavigationError("未找到可行走路线")
                return
            }

            currentRouteResult = result
            currentWalkPath = result.paths[0]
            totalDistance = currentWalkPath!!.distance.toFloat()
            totalDuration = currentWalkPath!!.duration.toFloat()
            remainingDistance = totalDistance

            val steps: List<WalkStep> = currentWalkPath!!.steps
            val points = mutableListOf<LatLng>()
            val instructions = mutableListOf<String>()

            for (step in steps) {
                val instruction = step.instruction
                if (!instruction.isNullOrEmpty()) {
                    instructions.add(instruction)
                }
                val stepPoints = step.polyline
                if (stepPoints != null) {
                    for (point in stepPoints) {
                        points.add(LatLng(point.latitude, point.longitude))
                    }
                }
            }

            routePoints = points
            stepInstructions = instructions
            currentPolylineIndex = 0

            if (!isNavigating) {
                isNavigating = true
            }

            Log.d(TAG, "Walk route found: ${routePoints!!.size} points, ${totalDistance}m, ${totalDuration}s")

            if (locationClient != null && !isRerouting) {
                locationClient?.startLocation()
            }

            if (navigationCallback != null) {
                navigationCallback!!.onRouteReady(routePoints!!, totalDistance, totalDuration, stepInstructions!!)
                if (!isRerouting) {
                    navigationCallback!!.onNavigationStarted()
                }
            }

            isRerouting = false
        } else {
            Log.e(TAG, "Walk route search failed, error code: $rCode")
            isRerouting = false
            var errorMsg = "步行路线规划失败"
            when (rCode) {
                2001 -> errorMsg = "步行路线规划失败：网络错误"
                2002 -> errorMsg = "步行路线规划失败：参数错误"
                2003 -> errorMsg = "步行路线规划失败：无权限"
            }
            navigationCallback?.onNavigationError(errorMsg)
        }
    }

    override fun onDriveRouteSearched(driveRouteResult: DriveRouteResult?, i: Int) {}

    override fun onBusRouteSearched(busRouteResult: BusRouteResult?, i: Int) {}

    override fun onRideRouteSearched(rideRouteResult: RideRouteResult?, i: Int) {}

    fun startNavigation(destination: LatLng) {
        this.destination = destination
        if (currentRouteResult != null) {
            isNavigating = true
            locationClient?.startLocation()
            navigationCallback?.onNavigationStarted()
        }
    }

    fun stopNavigation() {
        isNavigating = false
        isRerouting = false
        locationClient?.stopLocation()
        currentRouteResult = null
        currentWalkPath = null
        routePoints = null
        stepInstructions = null
        remainingDistance = 0f
        currentPolylineIndex = 0

        navigationCallback?.onNavigationStopped()
        Log.d(TAG, "Walk navigation stopped")
    }

    fun isNavigating(): Boolean = isNavigating

    fun getDestination(): LatLng? = destination

    fun setNavigationCallback(callback: NavigationCallback?) {
        navigationCallback = callback
    }

    fun destroyLocationClient() {
        locationClient?.onDestroy()
        locationClient = null
    }

    private fun calculateDistance(p1: LatLng, p2: LatLng): Float {
        val R = 6371000.0
        val dLat = Math.toRadians(p2.latitude - p1.latitude)
        val dLng = Math.toRadians(p2.longitude - p1.longitude)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(p1.latitude)) * Math.cos(Math.toRadians(p2.latitude)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return (R * c).toFloat()
    }

    companion object {
        private const val TAG = "NavigationManager"
        private const val UPDATE_INTERVAL = 3000L
        private const val ARRIVAL_DISTANCE = 20f
        private const val OFF_ROUTE_THRESHOLD = 50f
    }
}
