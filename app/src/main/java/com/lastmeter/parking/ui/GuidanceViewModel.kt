package com.lastmeter.parking.ui

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.vehicle.Vehicle
import android.app.Application
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lastmeter.parking.data.EntranceRepository
import com.lastmeter.parking.data.Facility
import com.lastmeter.parking.guidance.Fix
import com.lastmeter.parking.guidance.Geo
import com.lastmeter.parking.guidance.GuidanceEngine
import com.lastmeter.parking.guidance.GuidanceEvent
import com.lastmeter.parking.guidance.GuidanceState
import com.lastmeter.parking.guidance.SpeechTexts
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Pleos SDK(위치·기어·내비·음성)와 AAOS 주행 제한 정보를 모아 GuidanceEngine에 넣고, 결과를 화면 상태와 음성으로 내보낸다.
 */
class GuidanceViewModel(app: Application) : AndroidViewModel(app) {

    private val main = Handler(Looper.getMainLooper())
    private val engine = GuidanceEngine()

    val facilities: List<Facility> = runCatching { EntranceRepository(app).load() }
        .onFailure { Log.e(TAG, "입구 데이터 읽기 실패", it) }
        .getOrDefault(emptyList())

    var guidance by mutableStateOf<GuidanceState>(GuidanceState.Idle)
        private set
    var target by mutableStateOf<Facility?>(null)
        private set
    /** 주행 제한 중(주행 중)인지. 제한 중에는 버튼·설정을 숨긴다 */
    var restricted by mutableStateOf(false)
        private set
    var isPark by mutableStateOf<Boolean?>(null)
        private set
    var locationReady by mutableStateOf(false)
        private set
    var naviDestinationName by mutableStateOf<String?>(null)
        private set
    var lastFeedback by mutableStateOf<String?>(null)
        private set

    private var fused: FusedLocation? = null
    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var car: Car? = null
    private var uxManager: CarUxRestrictionsManager? = null
    /** 사용자가 시연용으로 목적지를 직접 고른 경우, 내비 목적지로 덮어쓰지 않는다 */
    private var manualTarget = false

    init {
        startTts()
        startVehicle()
        startNavi()
        startUxRestrictions()
    }

    // ---------- 목적지 ----------

    fun selectDemoTarget(facility: Facility?) {
        manualTarget = facility != null
        applyTarget(facility)
    }

    private fun applyTarget(facility: Facility?) {
        engine.setTarget(facility)
        target = facility
        guidance = engine.state
        Log.i(TAG, "안내 대상: ${facility?.name}")
    }

    /** 내비 목적지 좌표 근처(400m)에 입구 데이터가 있는 시설을 찾는다 */
    private fun onNaviDestination(info: DestinationInfo) {
        naviDestinationName = info.name.ifBlank { null }
        if (manualTarget) return
        if (info.latitude == 0.0 && info.longitude == 0.0) {
            applyTarget(null)
            return
        }
        val match = facilities.minByOrNull { f ->
            f.entrances.minOf { e -> Geo.distanceM(info.latitude, info.longitude, e.lat, e.lng) }
        }?.takeIf { f ->
            f.entrances.any { e -> Geo.distanceM(info.latitude, info.longitude, e.lat, e.lng) <= 400.0 }
        }
        if (match?.id != target?.id) applyTarget(match)
    }

    // ---------- 위치 (FusedLocation) ----------

    /** pleos.car.permission.FUSED_LOCATION 권한을 받은 뒤 호출 */
    fun onLocationPermission(granted: Boolean) {
        if (!granted || fused != null) return
        try {
            val f = FusedLocation(getApplication())
            if (!f.isSupported()) {
                Log.w(TAG, "FusedLocation 미지원")
                return
            }
            f.initialize()
            f.registerFusedLocationCallback(locationListener)
            fused = f
            locationReady = true
        } catch (e: Exception) {
            Log.e(TAG, "FusedLocation 시작 실패", e)
        }
    }

    private val locationListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            if (location == null) return
            main.post { onFix(Fix(location.latitude, location.longitude, location.timestampMillis)) }
        }

        override fun onError(exception: Exception) {
            Log.w(TAG, "위치 오류: ${exception.message}")
        }
    }

    private fun onFix(fix: Fix) {
        val events = engine.onFix(fix)
        val before = guidance
        guidance = engine.state
        Log.d(TAG, "위치 ${"%.5f".format(fix.lat)},${"%.5f".format(fix.lng)} 상태=${guidance::class.simpleName} (이전 ${before::class.simpleName})")
        events.forEach(::speak)
    }

    // ---------- 기어 (Vehicle) ----------

    private fun startVehicle() {
        try {
            val v = Vehicle(getApplication())
            v.initialize()
            vehicle = v
        } catch (e: Exception) {
            Log.e(TAG, "Vehicle 시작 실패", e)
            return
        }
        // 기어 변화 리스너를 확인하지 못해서 1초마다 조회한다 (docs/sdk-check.md 4절)
        viewModelScope.launch {
            while (true) {
                readGear()
                delay(1000)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun readGear() {
        val v = vehicle ?: return
        runCatching {
            v.getGear().getCurrentGearSelectionState(
                { gear -> main.post { onGear(gear?.name == "GEAR_PARK") } },
                { e -> Log.w(TAG, "기어 읽기 실패: ${e.message}") },
            )
        }
    }

    private fun onGear(park: Boolean) {
        if (isPark == park) return
        Log.i(TAG, "기어 변화: P단=$park")
        isPark = park
        engine.onGear(park)
        guidance = engine.state
    }

    // ---------- 내비 (NaviHelper) ----------

    private fun startNavi() {
        try {
            val helper = NaviHelper(getApplication())
            helper.initialize()
            helper.addListener(naviListener)
            navi = helper
            helper.getDestinationInfo()
        } catch (e: Exception) {
            Log.e(TAG, "NaviHelper 시작 실패", e)
        }
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            main.post { onNaviDestination(destinationInfo) }
        }

        override fun onDestinationChanged() {
            runCatching { navi?.getDestinationInfo() }
        }

        override fun onRouteStarted(routeStartInfo: ai.pleos.playground.navi.data.RouteStartInfo) {
            runCatching { navi?.getDestinationInfo() }
        }

        override fun onRouteCancelled() {
            runCatching { navi?.getDestinationInfo() }
        }

        override fun onDestinationArrived(destinationArrivedInfo: ai.pleos.playground.navi.data.DestinationArrivedInfo) = Unit
        @Deprecated("Deprecated by NaviHelperEventListener")
        override fun onRouteEnded() = Unit
        override fun onRoutingCompleted() = Unit
        override fun onReRouteEnded() = Unit
        override fun onRouteOptionChanged(routeOption: ai.pleos.playground.navi.constants.RouteOption) = Unit
        override fun onWaypointChanged(waypointChangedInfo: ai.pleos.playground.navi.data.WaypointChangedInfo) = Unit
        override fun onWayPointArrived(waypointArrivedInfo: ai.pleos.playground.navi.data.WaypointArrivedInfo) = Unit
        override fun onError(errorCode: ai.pleos.playground.navi.constants.NaviErrorCode) = Unit
        override fun onCurrentLocationInfo(currentLocationInfo: ai.pleos.playground.navi.data.CurrentLocationInfo) = Unit
        override fun onRouteStateInfo(routeStateInfo: ai.pleos.playground.navi.data.RouteStateInfo) = Unit
        override fun onWayPointInfo(waypointInfoList: List<ai.pleos.playground.navi.data.WaypointInfo>) = Unit
        override fun onRecentDestinationInfo(recentDestinationInfoList: List<ai.pleos.playground.navi.data.RecentDestinationInfo>) = Unit
        override fun onBookmarkInfo(bookmarkInfoList: List<ai.pleos.playground.navi.data.BookmarkInfo>) = Unit
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) = Unit
        override fun onCameraAlert(cameraAlertList: List<ai.pleos.playground.navi.data.CameraAlert>) = Unit
        override fun onDrivingInfo(drivingInfo: ai.pleos.playground.navi.data.DrivingInfo) = Unit
        override fun onBookMarkChanged() = Unit
        override fun onNavigationStatus(status: ai.pleos.playground.navi.constants.NavigationStatus) = Unit
        override fun onTBTInfo(tbtInfoList: List<ai.pleos.playground.navi.data.TBTInfo>) = Unit
        override fun onChargerOperatorInfo(chargerOperatorList: List<ai.pleos.playground.navi.data.ChargerOperator>) = Unit
        override fun onCautionPointInfo(cautionPointList: List<ai.pleos.playground.navi.data.CautionPoint>) = Unit
        override fun onTripInfo(tripInfo: ai.pleos.playground.navi.data.TripInfo) = Unit
    }

    // ---------- 음성 (TextToSpeech) ----------

    private fun startTts() {
        try {
            val t = TextToSpeech(getApplication(), TtsMode.ON_DEVICE)
            tts = t
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    t.setLocale(Locale.KOREA)
                    ttsReady = true
                }

                override fun onFailure(errorType: TtsErrorType) {
                    Log.w(TAG, "TTS 초기화 실패: $errorType")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "TTS 시작 실패", e)
        }
    }

    private fun speak(event: GuidanceEvent) {
        val text = when (event) {
            is GuidanceEvent.SpeakPreview -> SpeechTexts.preview(event)
            is GuidanceEvent.SpeakConfuserWarning -> SpeechTexts.confuser(event)
            is GuidanceEvent.SpeakPassed -> SpeechTexts.passed()
        }
        Log.i(TAG, "음성: $text")
        if (ttsReady) runCatching { tts?.speak(text, true) }
    }

    // ---------- 주행 제한 (AAOS CarUxRestrictions) ----------

    @Suppress("DEPRECATION")
    private fun startUxRestrictions() {
        try {
            val c = Car.createCar(getApplication())
            car = c
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            uxManager = m
            restricted = m.currentCarUxRestrictions?.isRequiresDistractionOptimization == true
            m.registerListener { r -> main.post { restricted = r.isRequiresDistractionOptimization } }
        } catch (e: Exception) {
            Log.e(TAG, "주행 제한 감시 실패", e)
        }
    }

    // ---------- 주차 후 피드백 ----------

    fun submitFeedback(correct: Boolean) {
        val s = guidance as? GuidanceState.Parked ?: return
        // TODO: 동의한 사용자에 한해 서버로 보내 입구 데이터 정확도 개선에 쓴다 (기획서 확장 계획)
        lastFeedback = if (correct) "고마워요. 입구 정보가 맞다고 기록했어요." else "알려 주셔서 고마워요. 이 입구 정보를 다시 확인할게요."
        Log.i(TAG, "피드백 ${s.entrance.id} correct=$correct")
    }

    @Suppress("DEPRECATION")
    override fun onCleared() {
        runCatching { fused?.unregisterFusedLocationCallback(locationListener); fused?.release() }
        runCatching { vehicle?.release() }
        runCatching { navi?.removeListener(naviListener); navi?.release() }
        runCatching { tts?.release() }
        runCatching { uxManager?.unregisterListener(); car?.disconnect() }
        super.onCleared()
    }

    companion object {
        const val TAG = "Guidance"
    }
}
