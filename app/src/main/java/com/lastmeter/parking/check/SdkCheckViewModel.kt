package com.lastmeter.parking.check

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.ChargerOperator
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.vehicle.Vehicle
import android.app.Application
import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 5단계 SDK 점검판.
 * 각 SDK의 호출 결과와 콜백을 화면과 Logcat(태그 SdkCheck)에 그대로 남긴다.
 * 콜백 객체의 toString()을 그대로 찍어서, 문서에서 확인하지 못한 필드 이름을 실행 결과로 확인한다.
 */
class SdkCheckViewModel(app: Application) : AndroidViewModel(app), NaviHelperEventListener {

    val logs = mutableStateListOf<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREA)

    private var naviHelper: NaviHelper? = null
    private var fused: FusedLocation? = null
    private var fusedSubscribed = false
    private var lastLocation: LocationData? = null
    private var lastLocationAt = 0L
    private var proximityAlertId: String? = null
    private var vehicle: Vehicle? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var car: Car? = null
    private var uxManager: CarUxRestrictionsManager? = null

    fun log(tag: String, message: String) {
        val line = "${timeFormat.format(Date())} [$tag] $message"
        Log.i(LOG_TAG, line)
        logs.add(0, line)
        if (logs.size > 300) logs.removeAt(logs.lastIndex)
    }

    private inline fun guarded(tag: String, action: String, block: () -> Unit) {
        try {
            block()
            log(tag, "$action 호출함")
        } catch (e: Throwable) {
            log(tag, "$action 실패: ${e::class.java.simpleName}: ${e.message}")
        }
    }

    // ---------------- 1. 내비 연동 (NaviHelper) ----------------

    fun naviInitialize() = guarded("NAVI", "initialize") {
        if (naviHelper != null) return@guarded
        val helper = NaviHelper(getApplication())
        helper.initialize()
        helper.addListener(this)
        naviHelper = helper
    }

    fun naviGetDestination() = guarded("NAVI", "getDestinationInfo") { requireNavi().getDestinationInfo() }
    fun naviGetRouteState() = guarded("NAVI", "getRouteStateInfo") { requireNavi().getRouteStateInfo() }
    fun naviGetCurrentLocation() = guarded("NAVI", "getCurrentLocationInfo") { requireNavi().getCurrentLocationInfo() }
    fun naviGetTbt() = guarded("NAVI", "getTBTInfo") { requireNavi().getTBTInfo() }

    // 목적지 요청(requestRoute)·취소 시험은 11/08 에 확인을 마치고 뺐다(docs/sdk-check.md 1절).
    // 지금은 앱 기능(내비 목적지를 주차장 입구로 바꾸기, GuidanceController.applyRouteAdjust)에서 requestRoute 를 쓴다.

    private fun requireNavi(): NaviHelper = naviHelper ?: error("NaviHelper 먼저 initialize")

    override fun onRouteStarted(routeStartInfo: RouteStartInfo) = log("NAVI", "onRouteStarted $routeStartInfo")
    @Deprecated("Deprecated by NaviHelperEventListener")
    override fun onRouteEnded() = log("NAVI", "onRouteEnded(deprecated)")
    override fun onRoutingCompleted() = log("NAVI", "onRoutingCompleted")
    override fun onReRouteEnded() = log("NAVI", "onReRouteEnded")
    override fun onRouteOptionChanged(routeOption: RouteOption) = log("NAVI", "onRouteOptionChanged $routeOption")
    override fun onRouteCancelled() = log("NAVI", "onRouteCancelled")
    override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) = log("NAVI", "onWaypointChanged $waypointChangedInfo")
    override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = log("NAVI", "onDestinationArrived $destinationArrivedInfo")
    override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) = log("NAVI", "onWayPointArrived $waypointArrivedInfo")
    override fun onError(errorCode: NaviErrorCode) = log("NAVI", "onError $errorCode")
    override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = log("NAVI", "onCurrentLocationInfo $currentLocationInfo")
    override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = log("NAVI", "onRouteStateInfo $routeStateInfo")
    override fun onDestinationInfo(destinationInfo: DestinationInfo) = log("NAVI", "onDestinationInfo $destinationInfo")
    override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) = log("NAVI", "onWayPointInfo $waypointInfoList")
    override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) = log("NAVI", "onRecentDestinationInfo size=${recentDestinationInfoList.size}")
    override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) = log("NAVI", "onBookmarkInfo size=${bookmarkInfoList.size}")
    override fun onCurrentRoadSpeedLimit(speedLimit: Int) = Unit
    override fun onCameraAlert(cameraAlertList: List<CameraAlert>) = Unit
    override fun onDrivingInfo(drivingInfo: DrivingInfo) = logThrottled("NAVI", "onDrivingInfo $drivingInfo")
    override fun onBookMarkChanged() = log("NAVI", "onBookMarkChanged")
    override fun onDestinationChanged() = log("NAVI", "onDestinationChanged")
    override fun onNavigationStatus(status: NavigationStatus) = log("NAVI", "onNavigationStatus $status")
    override fun onTBTInfo(tbtInfoList: List<TBTInfo>) = logThrottled("NAVI-TBT", "onTBTInfo $tbtInfoList")
    override fun onChargerOperatorInfo(chargerOperatorList: List<ChargerOperator>) = Unit
    override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) = Unit
    override fun onTripInfo(tripInfo: TripInfo) = log("NAVI", "onTripInfo $tripInfo")

    private val lastThrottled = mutableMapOf<String, Long>()
    private fun logThrottled(tag: String, message: String) {
        val now = System.currentTimeMillis()
        if (now - (lastThrottled[tag] ?: 0L) >= 3000) {
            lastThrottled[tag] = now
            log(tag, message)
        }
    }

    // ---------------- 2·3. 위치와 근접 알림 (FusedLocation) ----------------

    private val locationListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            val now = System.currentTimeMillis()
            val gapMs = if (lastLocationAt == 0L) 0 else now - lastLocationAt
            lastLocationAt = now
            if (location == null) {
                log("LOC", "onChanged(null) 갱신간격=${gapMs}ms")
                return
            }
            lastLocation = location
            logThrottled("LOC", "onChanged $location 갱신간격=${gapMs}ms")
        }

        override fun onError(exception: Exception) = log("LOC", "onError ${exception::class.java.simpleName}: ${exception.message}")
    }

    private val proximityListener = object : ProximityAlertListener {
        override fun onRegistered(alertId: String) {
            proximityAlertId = alertId
            log("PROX", "onRegistered id=$alertId")
        }

        override fun onTransition(event: ProximityAlertEvent) = log("PROX", "onTransition $event")
        override fun onError(exception: Exception) = log("PROX", "onError ${exception::class.java.simpleName}: ${exception.message}")
    }

    fun locationInitialize() = guarded("LOC", "isSupported/initialize") {
        if (fused != null) return@guarded
        val f = FusedLocation(getApplication())
        log("LOC", "isSupported=${f.isSupported()}")
        f.initialize()
        fused = f
    }

    fun locationStart() = guarded("LOC", "registerFusedLocationCallback") {
        val f = fused ?: error("FusedLocation 먼저 initialize")
        if (!fusedSubscribed) {
            f.registerFusedLocationCallback(locationListener)
            fusedSubscribed = true
        }
    }

    fun locationLastKnown() = guarded("LOC", "getLastKnownLocation") {
        (fused ?: error("FusedLocation 먼저 initialize")).getLastKnownLocation(locationListener)
    }

    /** 마지막으로 받은 위치를 중심으로 반경 300m 근접 알림 등록 */
    fun proximityRegisterAtLast() = guarded("PROX", "addProximityAlert(현재 위치, 300m)") {
        val f = fused ?: error("FusedLocation 먼저 initialize")
        val loc = lastLocation ?: error("위치를 먼저 받아야 함")
        f.addProximityAlert(
            ProximityAlertRequest(
                latitude = loc.latitude,
                longitude = loc.longitude,
                radiusMeters = 300f,
                transitionTypes = setOf(ProximityTransition.ENTER, ProximityTransition.EXIT),
            ),
            proximityListener,
        )
    }

    fun proximityRemove() = guarded("PROX", "removeProximityAlert") {
        val id = proximityAlertId ?: error("등록된 알림 없음")
        fused?.removeProximityAlert(id)
        proximityAlertId = null
    }

    // ---------------- 4. 기어 (Vehicle) ----------------

    fun vehicleInitialize() = guarded("VEH", "initialize") {
        if (vehicle != null) return@guarded
        val v = Vehicle(getApplication())
        v.initialize()
        vehicle = v
    }

    @Suppress("DEPRECATION")
    fun vehicleReadGear() = guarded("VEH", "getGear().getCurrentGearSelectionState") {
        val v = vehicle ?: error("Vehicle 먼저 initialize")
        v.getGear().getCurrentGearSelectionState(
            { gear -> log("VEH", "기어=$gear") },
            { e -> log("VEH", "기어 읽기 실패 ${e::class.java.simpleName}: ${e.message}") },
        )
    }

    // ---------------- 5. 음성 (TextToSpeech) ----------------

    fun ttsInitialize() = guarded("TTS", "initialize(ON_DEVICE)") {
        if (tts != null) return@guarded
        val t = TextToSpeech(getApplication(), TtsMode.ON_DEVICE)
        tts = t
        t.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                t.setLocale(Locale.KOREA)
                ttsReady = true
                log("TTS", "onSuccess, 한국어 설정")
            }

            override fun onFailure(errorType: TtsErrorType) = log("TTS", "onFailure $errorType")
        })
    }

    fun ttsSpeakTest() = guarded("TTS", "speak") {
        val t = tts ?: error("TTS 먼저 initialize")
        check(ttsReady) { "TTS 초기화 완료 전" }
        t.speak("오른쪽, 편의점 옆 경사로가 주차장 입구예요.", true)
    }

    // ---------------- 6. 주행 제한 (CarUxRestrictions, AAOS 표준) ----------------

    fun uxStart() = guarded("UX", "CarUxRestrictionsManager 등록") {
        if (uxManager != null) return@guarded
        val c = Car.createCar(getApplication())
        car = c
        val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
        uxManager = m
        logUx("현재", m.currentCarUxRestrictions)
        m.registerListener { r -> logUx("변경", r) }
    }

    private fun logUx(kind: String, r: CarUxRestrictions?) {
        if (r == null) {
            log("UX", "$kind: null")
            return
        }
        log(
            "UX",
            "$kind: 제한중=${r.isRequiresDistractionOptimization} active=0x${Integer.toHexString(r.activeRestrictions)} " +
                "최대글자=${r.maxRestrictedStringLength}",
        )
    }

    override fun onCleared() {
        runCatching { naviHelper?.removeListener(this); naviHelper?.release() }
        runCatching {
            if (fusedSubscribed) fused?.unregisterFusedLocationCallback(locationListener)
            proximityAlertId?.let { fused?.removeProximityAlert(it) }
            fused?.release()
        }
        runCatching { vehicle?.release() }
        runCatching { tts?.release() }
        runCatching { uxManager?.unregisterListener(); car?.disconnect() }
        super.onCleared()
    }

    companion object {
        const val LOG_TAG = "SdkCheck"
    }
}
