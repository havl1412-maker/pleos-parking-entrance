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
import android.content.Context
import com.lastmeter.parking.data.Access
import com.lastmeter.parking.data.AccessStatus
import com.lastmeter.parking.data.Entrance
import com.lastmeter.parking.data.EntranceData
import com.lastmeter.parking.data.EntranceRepository
import com.lastmeter.parking.data.FeedbackLog
import com.lastmeter.parking.data.FeedbackReason
import com.lastmeter.parking.data.FeedbackRecord
import com.lastmeter.parking.data.FeedbackSummary
import com.lastmeter.parking.data.Facility
import com.lastmeter.parking.guidance.Fix
import com.lastmeter.parking.guidance.Geo
import com.lastmeter.parking.guidance.GuidanceEngine
import com.lastmeter.parking.guidance.GuidanceEvent
import com.lastmeter.parking.guidance.GuidanceState
import com.lastmeter.parking.guidance.RouteAdjust
import com.lastmeter.parking.guidance.RouteAdjustment
import com.lastmeter.parking.guidance.SpeechTexts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.Locale

/**
 * Pleos SDK(위치·기어·내비·음성)와 AAOS 주행 제한 정보를 모아 GuidanceEngine에 넣고, 결과를 화면 상태와 음성으로 내보낸다.
 *
 * 화면(Activity)이 아니라 앱 프로세스에 하나만 둔다. 화면이 내려가거나 종료돼도
 * GuidanceKeepAliveService 가 프로세스를 살려 두는 동안 안내(음성·도착 알림)가 이어진다.
 */
class GuidanceController private constructor(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private fun getApplication(): Application = app
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val main = Handler(Looper.getMainLooper())
    private val engine = GuidanceEngine()

    private val entranceData: EntranceData = runCatching { EntranceRepository(app).load() }
        .getOrElse { EntranceData(emptyList(), listOf("입구 데이터 읽기 실패: ${it.message}")) }
        .also { d -> d.problems.forEach { Log.w(TAG, "입구 데이터 문제: $it") } }

    val facilities: List<Facility> = entranceData.facilities

    /** entrances.json 에서 틀려서 빼고 읽은 곳들. 대기 화면에 보여 줘서 데이터를 넣은 사람이 바로 알게 한다 */
    val dataProblems: List<String> = entranceData.problems

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
    /** "아니었어요"를 누른 뒤 이유를 고르는 중인지 */
    var askingReason by mutableStateOf(false)
        private set

    /** 주차 후 답한 기록. 이 차 안에만 저장하고, 다음에 같은 입구를 안내할 때 주의 표시에 쓴다 */
    private val feedbackPrefs = app.getSharedPreferences("feedback", Context.MODE_PRIVATE)
    private val feedbackLog = FeedbackLog.decode(feedbackPrefs.getString(KEY_FEEDBACK_LOG, "").orEmpty())
    /** 기록이 늘 때마다 올려서, 기록을 보여 주는 화면이 다시 그려지게 한다 */
    private var feedbackVersion by mutableStateOf(0)

    /** 운전자가 넣어 둔 내 차 높이(m). 높이 제한에 걸리는 입구를 미리 경고하는 데 쓴다. 없으면 null */
    var vehicleHeightM by mutableStateOf(prefs.getFloat(KEY_VEHICLE_HEIGHT, 0f).takeIf { it > 0f }?.toDouble())
        private set

    private var fused: FusedLocation? = null
    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var car: Car? = null
    private var uxManager: CarUxRestrictionsManager? = null
    /** 사용자가 시연용으로 목적지를 직접 고른 경우, 내비 목적지로 덮어쓰지 않는다 */
    private var manualTarget = false

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
        if (facility != null) ensureKeepAlive()
    }

    /** 백그라운드 유지 서비스가 켜져 있는지 */
    private var keepAliveOn = false

    /**
     * 백그라운드 유지 서비스를 켠다. 안드로이드 12+ 는 앱이 화면 뒤에 있을 때 포그라운드 서비스 시작을 막으므로
     * (내비에서 목적지를 정하면 우리 앱은 뒤로 간다), 앱 화면이 보일 때 미리 켜 두고 목적지가 없어져도 끄지 않는다.
     * 2026-11-11 에뮬레이터: 화면 뒤에서 시작하면 "startForegroundService() not allowed" 로 실패하는 것 확인.
     */
    private fun ensureKeepAlive() {
        if (keepAliveOn) return
        runCatching { com.lastmeter.parking.GuidanceKeepAliveService.start(getApplication()) }
            .onSuccess { keepAliveOn = true; Log.i(TAG, "백그라운드 유지 서비스 켬") }
            .onFailure { Log.w(TAG, "백그라운드 유지 서비스 시작 실패: ${it.message}") }
    }

    /** 내비 목적지 좌표 근처(400m)에 입구 데이터가 있는 시설을 찾는다 */
    private fun onNaviDestination(info: DestinationInfo) {
        naviDestinationName = info.name.ifBlank { null }
        if (manualTarget) return
        if (info.latitude == 0.0 && info.longitude == 0.0) {
            // 내비는 목적지를 바꿀 때 잠깐 목적지를 비웠다가(0,0) 새 목적지를 알려 준다. 도착하면서 지우기도 한다.
            // 바로 안내를 끝내면 진행 상태(미리 보기 음성, 지나침, 주차 판단)가 처음으로 돌아가므로 조금 기다린다
            if (naviClearedAt == null) naviClearedAt = android.os.SystemClock.elapsedRealtime()
            routeSuggestion = null
            main.removeCallbacks(clearTargetCheck)
            main.postDelayed(clearTargetCheck, CLEAR_DEBOUNCE_MS)
            return
        }
        naviClearedAt = null
        main.removeCallbacks(clearTargetCheck)
        confirmRouteAdjust(info)
        val match = facilities.minByOrNull { f ->
            f.entrances.minOf { e -> Geo.distanceM(info.latitude, info.longitude, e.lat, e.lng) }
        }?.takeIf { f ->
            f.entrances.any { e -> Geo.distanceM(info.latitude, info.longitude, e.lat, e.lng) <= 400.0 }
        }
        if (match?.id != target?.id) applyTarget(match)
        considerRouteAdjust(match, info)
    }

    /** 내비 목적지가 비워진 시각. 5초 넘게 비어 있고 입구 근처 안내 중이 아니면 안내 대상을 내린다 */
    private var naviClearedAt: Long? = null
    private val clearTargetCheck = Runnable { maybeClearTarget() }

    private fun maybeClearTarget() {
        val since = naviClearedAt ?: return
        if (manualTarget) { naviClearedAt = null; return }
        // 입구 근처에서 안내 중이면(도착하면서 내비가 목적지를 지운 경우) 안내가 끝날 때(대기 상태)까지 둔다. onFix 에서 다시 본다
        if (engine.state !is GuidanceState.Idle) return
        // 우리가 목적지 바꾸기를 요청해서 내비가 경로를 다시 찾는 중이면 기다린다 (에뮬레이터에서 5초 넘게 걸림)
        if (pendingRoute != null) { main.postDelayed(clearTargetCheck, CLEAR_DEBOUNCE_MS); return }
        if (android.os.SystemClock.elapsedRealtime() - since < CLEAR_DEBOUNCE_MS) return
        naviClearedAt = null
        Log.i(TAG, "내비 목적지가 지워져서 안내 대상을 내림")
        applyTarget(null)
        resetRouteAdjust() // 다음에 같은 건물로 갈 때 다시 제안할 수 있게
    }

    // ---------- 내비 목적지를 주차장 입구로 바꾸기 ----------

    /** 정차 중에 보여 주는 "주차장 입구로 바꿀까요?" 제안. 주행 중에는 묻지 않고 바로 바꾼다 */
    var routeSuggestion by mutableStateOf<RouteAdjustment?>(null)
        private set
    var routeMessage by mutableStateOf<String?>(null)
        private set
    /** 이미 바꾼 시설. 내비가 입구 좌표를 길 위로 옮겨 알려 주거나 출발 위치가 달라져도 다시 바꾸지 않게 시설 단위로 적는다 */
    private var routeAdjustedFor: String? = null
    /** 내비에 바꿔 달라고 요청했고, 내비가 새 목적지를 알려 주기를 기다리는 중 */
    private var pendingRoute: RouteAdjustment? = null
    private val routeConfirmTimeout = Runnable {
        if (pendingRoute != null) {
            Log.w(TAG, "내비가 바꾼 목적지를 알려 주지 않음")
            pendingRoute = null
            routeMessage = "내비 목적지를 바꾸지 못했어요. 입구 앞에서 사진과 순서로 알려 드릴게요."
        }
    }
    /** "그대로 두기"를 누른 시설. 그 목적지에서는 다시 묻지 않는다 */
    private var routeDismissedFor: String? = null
    /** 마지막으로 판단한 시설. 다른 시설로 목적지가 바뀔 때만 기록을 지운다 */
    private var routeFacilityId: String? = null
    private var lastFix: Fix? = null

    private fun resetRouteAdjust() {
        routeSuggestion = null
        routeMessage = null
        routeAdjustedFor = null
        routeDismissedFor = null
        routeFacilityId = null
        pendingRoute = null
        main.removeCallbacks(routeConfirmTimeout)
    }

    private fun considerRouteAdjust(facility: Facility?, info: DestinationInfo) {
        routeSuggestion = null
        if (facility == null) return
        if (facility.id != routeFacilityId) {
            resetRouteAdjust()
            routeFacilityId = facility.id
        }
        val adj = RouteAdjust.suggest(facility, info.latitude, info.longitude, lastFix?.lat, lastFix?.lng) ?: return
        if (routeAdjustedFor == facility.id || routeDismissedFor == facility.id || pendingRoute != null) return
        Log.i(TAG, "내비 목적지가 입구에서 ${adj.destinationToEntranceM.toInt()}m 떨어짐 → ${adj.entrance.id} 로 바꾸기 제안 (주행 제한=$restricted)")
        if (restricted) {
            // 주행 중에는 버튼을 누를 수 없으니 바로 바꾸고 음성으로 알린다
            applyRouteAdjust(adj)
        } else {
            routeSuggestion = adj
            if (!screenVisible) {
                // 내비에서 목적지를 정하면 우리 앱은 뒤에 있다. 알림을 눌러 열면 바꿀 수 있고, 그냥 출발하면 자동으로 바꾼다
                runCatching {
                    com.lastmeter.parking.ArrivalNotifier.show(
                        getApplication(),
                        title = "${SpeechTexts.lotName(facility.name)} 입구로 안내할까요?",
                        text = "내비 목적지가 입구에서 약 ${adj.destinationToEntranceM.toInt()}m 떨어져 있어요. 출발하면 자동으로 입구로 바꿔요",
                    )
                }
            }
        }
    }

    /** 내비가 알려 준 새 목적지가 우리가 보낸 입구 근처면 바뀐 것으로 보고 알린다 */
    private fun confirmRouteAdjust(info: DestinationInfo) {
        val p = pendingRoute ?: return
        if (Geo.distanceM(info.latitude, info.longitude, p.entrance.lat, p.entrance.lng) > ROUTE_CONFIRM_M) return
        pendingRoute = null
        main.removeCallbacks(routeConfirmTimeout)
        Log.i(TAG, "내비가 목적지를 ${p.entrance.id} 로 바꿈")
        routeMessage = "내비 목적지를 주차장 입구로 바꿨어요"
        speakText(SpeechTexts.routeChanged(p.facility.name))
    }

    /** 정차 중 "주차장 입구로 바꾸기" 버튼 */
    fun acceptRouteSuggestion() {
        routeSuggestion?.let { applyRouteAdjust(it) }
    }

    /** 정차 중 "그대로 두기" 버튼 */
    fun dismissRouteSuggestion() {
        val s = routeSuggestion ?: return
        routeDismissedFor = s.facility.id
        routeSuggestion = null
        routeMessage = "내비 목적지를 그대로 둘게요. 입구 앞에서 사진과 순서로 알려 드릴게요."
    }

    private fun applyRouteAdjust(adj: RouteAdjustment) {
        val helper = navi ?: return
        routeAdjustedFor = adj.facility.id // 요청 직후 내비가 새 목적지를 알려 줄 때 되풀이하지 않게 먼저 적어 둔다
        routeSuggestion = null
        runCatching {
            helper.requestRoute(
                ai.pleos.playground.navi.data.RouteInfo(
                    latitude = adj.entrance.lat,
                    longitude = adj.entrance.lng,
                    poiId = "",
                    poiName = "${SpeechTexts.lotName(adj.facility.name)} 입구",
                    poiSubId = "",
                    address = adj.facility.address,
                )
            )
        }.onSuccess {
            Log.i(TAG, "내비 목적지를 ${adj.entrance.id} (${adj.entrance.lat}, ${adj.entrance.lng}) 로 바꿔 요청")
            // 화면 안내도 내비를 보낸 입구로 맞춘다
            engine.preferredEntranceId = adj.entrance.id
            // "바꿨어요"는 내비가 새 목적지를 알려 준 뒤에 말한다 (confirmRouteAdjust)
            pendingRoute = adj
            routeMessage = "내비 목적지를 주차장 입구로 바꾸는 중이에요"
            main.removeCallbacks(routeConfirmTimeout)
            main.postDelayed(routeConfirmTimeout, ROUTE_CONFIRM_TIMEOUT_MS)
        }.onFailure {
            Log.w(TAG, "내비 목적지 바꾸기 실패: ${it.message}")
            routeAdjustedFor = null
            routeMessage = "내비 목적지를 바꾸지 못했어요. 입구 앞에서 사진과 순서로 알려 드릴게요."
        }
    }

    /** 주행 제한이 켜지면(출발) 답하지 않은 제안은 자동으로 적용한다 */
    private fun onRestricted(value: Boolean) {
        restricted = value
        if (value) routeSuggestion?.let { applyRouteAdjust(it) }
    }

    /** 개발용(시연): 내비 목적지를 입구가 아닌 건물 위치로 정해서, 바꾸기 제안이 뜨는 흐름을 보여 준다 */
    fun demoSetNaviToBuilding(facility: Facility) {
        val lat = facility.naviLat ?: return
        val lng = facility.naviLng ?: return
        selectDemoTarget(null)
        resetRouteAdjust()
        runCatching {
            navi?.requestRoute(
                ai.pleos.playground.navi.data.RouteInfo(
                    latitude = lat, longitude = lng, poiId = "", poiName = facility.name, poiSubId = "", address = facility.address,
                )
            )
        }.onFailure { Log.w(TAG, "시연용 내비 목적지 요청 실패: ${it.message}") }
    }

    // ---------- 내 차 높이 ----------

    /** 0.1m 단위로 올리고 내린다. 처음 누르면 승용차 기준 1.6m 에서 시작 */
    fun changeVehicleHeight(deltaM: Double) {
        val current = vehicleHeightM
        val next = if (current == null) 1.6 else (Math.round((current + deltaM) * 10) / 10.0).coerceIn(1.2, 3.5)
        vehicleHeightM = next
        prefs.edit().putFloat(KEY_VEHICLE_HEIGHT, next.toFloat()).apply()
    }

    fun clearVehicleHeight() {
        vehicleHeightM = null
        prefs.edit().remove(KEY_VEHICLE_HEIGHT).apply()
    }

    /** 내 차가 이 입구의 높이 제한에 걸리는지 */
    fun tooTallFor(entrance: Entrance): Boolean {
        val mine = vehicleHeightM ?: return false
        val limit = entrance.heightLimitM ?: return false
        return mine >= limit
    }

    // ---------- 운영 시간 ----------

    /** 시연용 시각 번호. 0 이면 실제 시각. 밤이나 일요일 화면을 그 시각까지 기다리지 않고 보여 주려고 둔다 */
    var demoClockIndex by mutableStateOf(0)
        private set
    val demoClockLabel: String get() = DEMO_CLOCKS[demoClockIndex].first

    /** 정차 중 시연용 버튼: 실제 시각 → 평일 낮 → 평일 밤 9시 반 → 평일 밤 11시 → 일요일 낮 → 실제 시각 */
    fun cycleDemoClock() {
        demoClockIndex = (demoClockIndex + 1) % DEMO_CLOCKS.size
    }

    fun now(): LocalDateTime = DEMO_CLOCKS[demoClockIndex].second ?: LocalDateTime.now()

    /** 이 입구가 지금 열려 있는지(유료/무료 개방/폐쇄). 시간표가 없는 입구는 null */
    fun accessFor(entrance: Entrance): Access? = entrance.schedule?.at(now())

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
            // 시각은 받은 순간의 기기 시계를 쓴다. 속도를 위치 변화로 구하는데(LocationData 에 속도 칸이 없다),
            // 같은 좌표가 같은 timestampMillis 로 다시 오면 멈춘 것을 알 수 없기 때문이다.
            val receivedAt = android.os.SystemClock.elapsedRealtime()
            main.post { onFix(Fix(location.latitude, location.longitude, receivedAt)) }
        }

        override fun onError(exception: Exception) {
            Log.w(TAG, "위치 오류: ${exception.message}")
        }
    }

    private fun onFix(fix: Fix) {
        lastFix = fix
        val events = engine.onFix(fix)
        val before = guidance
        guidance = engine.state
        if (naviClearedAt != null) maybeClearTarget()
        val kmh = engine.speedMps?.let { "%.0f".format(it * 3.6) } ?: "?"
        Log.d(TAG, "위치 ${"%.5f".format(fix.lat)},${"%.5f".format(fix.lng)} 시속 ${kmh}km 미리보기 ${engine.previewDistanceM().toInt()}m 상태=${guidance::class.simpleName} (이전 ${before::class.simpleName})")
        events.forEach(::speak)
        val s = guidance
        if (before is GuidanceState.Idle && s is GuidanceState.Approaching && !screenVisible) {
            // 화면이 내려가 있으면(내비만 보일 때) 알림을 띄워 한 번 눌러 내비 옆에 열 수 있게 한다.
            // 앱이 스스로 화면을 올리는 것은 시스템이 막는다 (BAL_BLOCK, docs/sdk-check.md 8절).
            runCatching {
                val closed = accessFor(s.entrance)?.status == AccessStatus.CLOSED
                com.lastmeter.parking.ArrivalNotifier.show(
                    getApplication(),
                    title = "${SpeechTexts.lotName(s.facility.name)} 입구" + if (closed) " · 지금은 문 닫음" else "",
                    text = if (s.otherDirection) "다른 방향에서 오는 중 · 사진의 간판을 보고 찾으세요"
                    else SpeechTexts.orderLine(s.entrance.roadSide, s.entrance.orderFromApproach) + " · " + s.entrance.landmark,
                )
            }.onFailure { Log.w(TAG, "도착 알림 실패: ${it.message}") }
        }
    }

    /** 앱 화면이 지금 보이는지 (MainActivity 가 알려 준다) */
    var screenVisible = false
        set(value) {
            field = value
            if (value) {
                runCatching { com.lastmeter.parking.ArrivalNotifier.cancel(getApplication()) }
                // 화면이 보일 때만 포그라운드 서비스를 시작할 수 있다
                ensureKeepAlive()
            }
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
        scope.launch {
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
        // 새로 주차할 때마다 피드백 질문을 처음 상태로
        lastFeedback = null
        askingReason = false
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
            is GuidanceEvent.SpeakPreview ->
                SpeechTexts.preview(event, accessFor(event.entrance), now()) +
                    (if (tooTallFor(event.entrance)) " " + SpeechTexts.tooTall(event.entrance.heightLimitM ?: 0.0) else "") +
                    (SpeechTexts.feedbackSpeech(feedbackFor(event.entrance))?.let { " $it" } ?: "")
            is GuidanceEvent.SpeakConfuserWarning -> SpeechTexts.atEntrance(
                event,
                facilityName = target?.name.orEmpty(),
                tooTallLimitM = event.entrance.heightLimitM?.takeIf { tooTallFor(event.entrance) },
                closed = accessFor(event.entrance)?.status == AccessStatus.CLOSED,
            )
            is GuidanceEvent.SpeakPassed -> SpeechTexts.passed(event.entrance, event.otherDirection)
        }
        speakText(text)
    }

    private fun speakText(text: String) {
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
            m.registerListener { r -> main.post { onRestricted(r.isRequiresDistractionOptimization) } }
        } catch (e: Exception) {
            Log.e(TAG, "주행 제한 감시 실패", e)
        }
    }

    // ---------- 주차 후 피드백 ----------

    /** "맞았어요"는 바로 기록하고, "아니었어요"는 어떤 점이 달랐는지 한 번 더 묻는다 */
    fun submitFeedback(correct: Boolean) {
        val s = guidance as? GuidanceState.Parked ?: return
        if (!correct) {
            askingReason = true
            return
        }
        saveFeedback(FeedbackRecord(s.entrance.id, System.currentTimeMillis(), correct = true))
        lastFeedback = "고마워요. 입구 정보가 맞다고 기록했어요."
    }

    fun submitWrongReason(reason: FeedbackReason) {
        val s = guidance as? GuidanceState.Parked ?: return
        saveFeedback(FeedbackRecord(s.entrance.id, System.currentTimeMillis(), correct = false, reason = reason))
        askingReason = false
        lastFeedback = "알려 주셔서 고마워요. 다음에 이 입구를 안내할 때 한 번 더 확인하라고 알려 드릴게요."
    }

    private fun saveFeedback(record: FeedbackRecord) {
        // TODO: 동의한 사용자에 한해 서버로 보내 입구 데이터 정확도 개선에 쓴다 (기획서 확장 계획)
        feedbackLog.add(record)
        feedbackPrefs.edit().putString(KEY_FEEDBACK_LOG, feedbackLog.encode()).apply()
        feedbackVersion++
        Log.i(TAG, "피드백 ${record.entranceId} correct=${record.correct} reason=${record.reason} (모두 ${feedbackLog.records.size}건)")
    }

    /** 이 입구의 최근 90일 기록 요약 */
    fun feedbackFor(entrance: Entrance): FeedbackSummary {
        feedbackVersion // 읽어 두면 기록이 늘 때 화면이 다시 그려진다
        return feedbackLog.summary(entrance.id, System.currentTimeMillis())
    }

    @Suppress("DEPRECATION")
    fun release() {
        runCatching { fused?.unregisterFusedLocationCallback(locationListener); fused?.release() }
        runCatching { vehicle?.release() }
        runCatching { navi?.removeListener(naviListener); navi?.release() }
        runCatching { tts?.release() }
        runCatching { uxManager?.unregisterListener(); car?.disconnect() }
        runCatching { com.lastmeter.parking.GuidanceKeepAliveService.stop(getApplication()) }
        keepAliveOn = false
        instance = null
    }

    // 클래스 맨 끝에 둔다. 코틀린은 속성을 위에서부터 초기화하므로, 앞에 두면 naviListener 등이 아직 null 이라
    // NaviHelper.addListener(null) 로 내비 연동이 조용히 실패한다 (2026-11-11 노트북 에뮬레이터에서 발견).
    init {
        startTts()
        startVehicle()
        startNavi()
        startUxRestrictions()
    }

    companion object {
        const val TAG = "Guidance"
        private const val KEY_VEHICLE_HEIGHT = "vehicleHeightM"
        private const val KEY_FEEDBACK_LOG = "log"
        /** 내비 목적지가 이만큼 비어 있어야 정말 지운 것으로 본다 */
        private const val CLEAR_DEBOUNCE_MS = 5_000L
        /** 목적지 바꾸기를 요청하고 이만큼 기다려도 내비가 새 목적지를 알려 주지 않으면 실패로 본다 */
        private const val ROUTE_CONFIRM_TIMEOUT_MS = 15_000L
        /** 내비가 알려 준 목적지가 보낸 입구에서 이만큼 안이면 받아들인 것으로 본다 (길 위로 옮길 수 있다) */
        private const val ROUTE_CONFIRM_M = 60.0

        // 2026-11-11 은 수요일, 2026-11-15 는 일요일
        private val DEMO_CLOCKS: List<Pair<String, LocalDateTime?>> = listOf(
            "실제 시각" to null,
            "평일 오후 2시" to LocalDateTime.of(2026, 11, 11, 14, 0),
            "평일 밤 9시 30분" to LocalDateTime.of(2026, 11, 11, 21, 30),
            "평일 밤 11시" to LocalDateTime.of(2026, 11, 11, 23, 0),
            "일요일 오후 2시" to LocalDateTime.of(2026, 11, 15, 14, 0),
        )

        @Volatile
        private var instance: GuidanceController? = null

        fun get(context: Context): GuidanceController =
            instance ?: synchronized(this) {
                instance ?: GuidanceController(context.applicationContext as Application).also { instance = it }
            }
    }
}
