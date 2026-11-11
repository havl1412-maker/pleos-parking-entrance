package com.lastmeter.parking.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lastmeter.parking.data.AccessStatus
import com.lastmeter.parking.data.Entrance
import com.lastmeter.parking.data.Facility
import com.lastmeter.parking.data.FeedbackReason
import com.lastmeter.parking.data.RoadSide
import com.lastmeter.parking.data.confuserAfter
import com.lastmeter.parking.data.confuserBefore
import com.lastmeter.parking.guidance.GuidanceState
import com.lastmeter.parking.guidance.SpeechTexts

/*
 * 화면 원칙
 * - 색·크기는 Pleos Connect UI Design Guide 를 따른다 (PleosTheme.kt). 내비처럼 밝은 바탕 위에 카드를 올린다.
 * - 주행 중: 큰 글씨, 한 문단 3줄 이내, 움직이는 요소 없음, 버튼·설정 숨김 (Driver Distraction 가이드).
 * - 기본은 내비 옆 한 칸(세로형). 두 칸으로 넓어지면(가로형) 사진을 왼쪽, 안내를 오른쪽에 둔다.
 */
private val CardShape = RoundedCornerShape(20.dp)
private val Gap = 16.dp

@Composable
fun GuidanceScreen(
    vm: GuidanceController,
    onRequestLocationPermission: () -> Unit,
    onOpenSdkCheck: () -> Unit,
) {
    val c = LocalPleosColors.current
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(c.background).padding(24.dp)) {
        val wide = maxWidth > maxHeight
        when (val s = vm.guidance) {
            is GuidanceState.Approaching -> ApproachScreen(vm, s.facility, s.entrance, s.distanceM, s.otherDirection, wide)
            is GuidanceState.AtEntrance -> AtEntranceScreen(vm, s.entrance, s.distanceM, s.otherDirection, wide)
            is GuidanceState.Passed -> PassedScreen(s.facility, s.entrance, s.otherDirection)
            is GuidanceState.Parked -> ParkedScreen(vm, s)
            GuidanceState.Idle -> IdleScreen(vm, onRequestLocationPermission, onOpenSdkCheck)
        }
    }
}

/** 한 칸이면 위아래, 두 칸이면 사진 왼쪽·안내 오른쪽 */
@Composable
private fun PhotoAndInfo(wide: Boolean, photo: @Composable (Modifier) -> Unit, info: @Composable ColumnScope.() -> Unit) {
    if (wide) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.weight(1.25f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) { photo(Modifier.fillMaxWidth()) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Gap), content = info)
        }
    } else {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Gap)) {
            info()
            photo(Modifier.fillMaxWidth())
        }
    }
}

// ---------- 1. 입구 약 30초 전(150~500m): 미리 보여 주기 ----------

@Composable
private fun ApproachScreen(vm: GuidanceController, facility: Facility, entrance: Entrance, distanceM: Double, otherDirection: Boolean, wide: Boolean) {
    PhotoAndInfo(
        wide = wide,
        photo = { EntrancePhoto(entrance, far = true, otherDirection = otherDirection, modifier = it) },
    ) {
        Header(label = facility.name, title = "주차장 입구 ${SpeechTexts.roughDistance(distanceM)}")
        // 문을 닫았으면 입구를 찾기 전에 알아야 한다. 무료 개방·곧 닫음은 작은 줄로
        AccessNotice(vm, entrance)
        if (otherDirection) {
            // 반대쪽에서 오면 "오른쪽 · 두 번째"와 약도의 순서가 맞지 않는다
            OtherDirectionCard(large = false)
        } else {
            DirectionCard(entrance, large = false)
            RampDiagram(entrance)
        }
        // 높이 제한은 들어가기 전에 알아야 한다. 내 차가 걸리면 경고로, 아니면 작은 표시로
        HeightNotice(vm, entrance)
        // 지난번에 이 안내가 맞지 않았다고 답했으면 한 번 더 확인하게 한다
        FeedbackNotice(vm, entrance)
    }
}

// ---------- 2·3. 입구 앞: 큰 화살표, 헷갈림 경고 ----------

@Composable
private fun AtEntranceScreen(vm: GuidanceController, entrance: Entrance, distanceM: Double, otherDirection: Boolean, wide: Boolean) {
    PhotoAndInfo(
        wide = wide,
        // 입구 바로 앞에서는 꺾기 직전 운전자 시점 사진으로 "이 모양 입구"를 확인시킨다
        photo = { EntrancePhoto(entrance, far = false, otherDirection = otherDirection, modifier = it) },
    ) {
        if (otherDirection) {
            OtherDirectionCard(large = true, trailing = SpeechTexts.roughDistance(distanceM))
        } else {
            DirectionCard(entrance, large = true, trailing = SpeechTexts.roughDistance(distanceM))
            RampDiagram(entrance)
        }
        val closed = vm.accessFor(entrance)?.status == AccessStatus.CLOSED
        if (vm.tooTallFor(entrance)) {
            if (closed) AccessNotice(vm, entrance)
            HeightNotice(vm, entrance)
        } else if (otherDirection) {
            // 반대쪽에서는 "첫 번째 경사로는 옆 건물" 같은 순서가 뒤집혀서 순서 없는 경고 하나만
            if (closed) AccessNotice(vm, entrance)
            if (entrance.confusers.isNotEmpty()) WarningCard("근처에 다른 건물 주차장 입구가 있어요")
        } else {
            // 경고는 두 장까지만 (주행 중 글이 많으면 안 된다). 문을 닫았으면 그것이 먼저다
            if (closed) AccessNotice(vm, entrance)
            // 앞쪽(먼저 나오는) 헷갈리는 입구, 그리고 지나치면 바로 나오는 뒤쪽 입구
            entrance.confuserBefore?.let { WarningCard(it.description) }
            if (!closed) entrance.confuserAfter?.let { WarningCard(it.description) }
        }
    }
}

// ---------- 4. 지나쳤을 때 ----------

@Composable
private fun PassedScreen(facility: Facility, entrance: Entrance, otherDirection: Boolean) {
    val c = LocalPleosColors.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Gap, Alignment.CenterVertically)) {
        Card {
            Text(facility.name, color = c.textSecondary, fontSize = 26.sp)
            Spacer(Modifier.height(8.dp))
            Text("입구를 지나쳤어요", color = c.negative, fontSize = 48.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("내비가 다시 안내해요", color = c.textPrimary, fontSize = 32.sp)
        }
        // 지나친 직후에는 바로 다음 경사로(다른 건물)로 잘못 들어가기 쉽다
        // 반대쪽에서 왔으면 데이터의 "뒤쪽 입구"는 이미 지나온 쪽이다
        if (!otherDirection && entrance.confuserAfter != null) WarningCard("바로 다음 경사로는 다른 건물 주차장이에요")
    }
}

// ---------- 5. 주차 완료 (정차 중이라 버튼 사용 가능) ----------

@Composable
private fun ParkedScreen(vm: GuidanceController, s: GuidanceState.Parked) {
    val c = LocalPleosColors.current
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Gap),
    ) {
        Header(label = "주차 완료", title = s.facility.name)
        if (s.likelyOtherBuilding) WarningCard("옆 건물 주차장일 수 있어요. 안내판을 확인해 주세요.")
        Card {
            InfoRow("높이 제한", s.entrance.heightLimitM?.let { "${it}m" })
            InfoRow("요금", s.entrance.feeText)
            InfoRow("운영 시간", s.entrance.hoursText)
            vm.accessFor(s.entrance)?.let { a ->
                InfoRow("지금", SpeechTexts.accessNow(a))
                // 차를 두고 간 사이에 문이 닫히면 못 나온다. 하루 안에 닫으면 시각을 보여 준다
                a.closesAt?.takeIf { java.time.Duration.between(vm.now(), it).toHours() < 24 }?.let {
                    InfoRow("문 닫는 시각", SpeechTexts.clock(it, vm.now()))
                }
            }
        }
        Card {
            Text("안내한 입구가 맞았나요?", color = c.textPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            if (!vm.restricted && vm.lastFeedback == null) {
                Spacer(Modifier.height(16.dp))
                if (!vm.askingReason) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        FilledButton("맞았어요", Modifier.weight(1f)) { vm.submitFeedback(true) }
                        PlainButton("아니었어요", Modifier.weight(1f)) { vm.submitFeedback(false) }
                    }
                } else {
                    // 이유를 알아야 데이터의 어느 칸(순서·운영 시간·사진)을 고칠지 알 수 있다
                    Text("어떤 점이 달랐나요?", color = c.textSecondary, fontSize = 26.sp)
                    Spacer(Modifier.height(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FeedbackReason.values().forEach { reason ->
                            PlainButton(reason.label, Modifier.fillMaxWidth()) { vm.submitWrongReason(reason) }
                        }
                    }
                }
            }
            vm.lastFeedback?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = c.positive, fontSize = 24.sp, lineHeight = 32.sp)
            }
            val record = vm.feedbackFor(s.entrance)
            if (record.correct + record.wrong > 0) {
                Spacer(Modifier.height(12.dp))
                Text("이 입구의 최근 90일 기록 · 맞았어요 ${record.correct} · 아니었어요 ${record.wrong}", color = c.textSecondary, fontSize = 22.sp)
            }
        }
        // 다음 단계 계획: 지금은 사람이 미리 찍은 사진을 쓴다. 빌트인 캠 영상 API가 열리면 차가 직접 입구 사진을 모은다 (docs/roadmap.md)
        Card {
            Text("다음 단계 · 준비 중", color = c.textSecondary, fontSize = 22.sp)
            Spacer(Modifier.height(4.dp))
            Text("빌트인 캠으로 입구 사진 자동 수집", color = c.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("입구를 지날 때 찍힌 장면으로 사진과 순서 정보를 최신으로 유지해요", color = c.textSecondary, fontSize = 24.sp, lineHeight = 32.sp)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    if (value == null) return
    val c = LocalPleosColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.textSecondary, fontSize = 26.sp, modifier = Modifier.weight(0.4f))
        Text(value, color = c.textPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.6f))
    }
}

// ---------- 대기 화면 ----------

@Composable
private fun IdleScreen(vm: GuidanceController, onRequestLocationPermission: () -> Unit, onOpenSdkCheck: () -> Unit) {
    val c = LocalPleosColors.current
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Gap),
    ) {
        val t = vm.target
        Header(label = "주차장 입구 안내", title = if (t != null) t.name else "목적지를 기다리는 중")
        Card {
            Text(
                if (t != null) "입구에 닿기 30초쯤 전에 사진과 함께 알려 드릴게요" else "내비 목적지 근처에 오면 주차장 입구를 알려 드려요",
                color = c.textPrimary,
                fontSize = 30.sp,
            )
            vm.naviDestinationName?.let {
                Spacer(Modifier.height(8.dp))
                Text("내비 목적지: $it", color = c.textSecondary, fontSize = 24.sp)
            }
            vm.routeMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = c.positive, fontSize = 24.sp, lineHeight = 32.sp)
            }
        }

        if (vm.restricted) return@Column // 주행 중에는 아래 설정을 숨긴다

        // 내비가 건물 앞으로 안내할 때: 정차 중에는 묻고, 그냥 출발하면 자동으로 바꾼다
        vm.routeSuggestion?.let { s ->
            Card {
                Text("내비가 건물 앞으로 안내하고 있어요", color = c.textPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "주차장 입구는 약 ${(Math.round(s.destinationToEntranceM / 10.0) * 10).toInt()}m 떨어져 있어요 · 출발하면 자동으로 입구로 바꿔요",
                    color = c.textSecondary, fontSize = 24.sp, lineHeight = 32.sp,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FilledButton("주차장 입구로 바꾸기", Modifier.weight(1f)) { vm.acceptRouteSuggestion() }
                    PlainButton("그대로 두기", Modifier.weight(1f)) { vm.dismissRouteSuggestion() }
                }
            }
        }

        if (vm.dataProblems.isNotEmpty()) DataProblemsCard(vm.dataProblems)

        if (!vm.locationReady) {
            FilledButton("위치 사용 허용", Modifier.fillMaxWidth()) { onRequestLocationPermission() }
        }
        Card {
            Text("내 차 높이", color = c.textSecondary, fontSize = 24.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlainButton("−", Modifier.width(96.dp)) { vm.changeVehicleHeight(-0.1) }
                Text(
                    vm.vehicleHeightM?.let { "${it}m" } ?: "설정 안 함",
                    color = c.textPrimary,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                PlainButton("+", Modifier.width(96.dp)) { vm.changeVehicleHeight(0.1) }
            }
            Spacer(Modifier.height(8.dp))
            Text("높이 제한에 걸리는 입구를 들어가기 전에 알려 드려요", color = c.textSecondary, fontSize = 22.sp)
        }
        Card {
            Text("시연용 목적지 (정차 중에만)", color = c.textSecondary, fontSize = 24.sp)
            Spacer(Modifier.height(12.dp))
            vm.facilities.forEach { f ->
                if (f.id == t?.id) {
                    FilledButton("✓ ${f.name}", Modifier.fillMaxWidth()) { vm.selectDemoTarget(null) }
                } else {
                    PlainButton(f.name, Modifier.fillMaxWidth()) { vm.selectDemoTarget(f) }
                }
            }
            // 운영 시간 안내(무료 개방·문 닫음)를 그 시각까지 기다리지 않고 보여 주기 위한 시연용 시계
            PlainButton("시연용 시각: ${vm.demoClockLabel}", Modifier.fillMaxWidth()) { vm.cycleDemoClock() }
            // 개발용: 내비 목적지를 입구가 아닌 건물 위치로 정해 "입구로 바꾸기" 흐름을 보여 준다
            if (LocalContext.current.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                vm.facilities.filter { it.naviLat != null }.forEach { f ->
                    PlainButton("시연: 내비 목적지를 ${f.name} 건물 앞으로", Modifier.fillMaxWidth()) { vm.demoSetNaviToBuilding(f) }
                }
            }
        }
        // 개발용 점검판은 debug 빌드에서만 보여 준다 (심사용 release 에는 숨김)
        val debuggable = LocalContext.current.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable) PlainButton("SDK 점검판 (개발용)", Modifier.fillMaxWidth(), onOpenSdkCheck)
    }
}

/** 입구 데이터에서 틀려서 빼고 읽은 곳. 세 개까지만 보여 주고 나머지는 로그로 */
@Composable
private fun DataProblemsCard(problems: List<String>) {
    val c = LocalPleosColors.current
    Column(
        modifier = Modifier.fillMaxWidth().clip(CardShape).background(c.surface).border(3.dp, c.negative, CardShape).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("입구 데이터 확인 필요 · ${problems.size}건", color = c.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        problems.take(3).forEach { Text("· $it", color = c.textSecondary, fontSize = 20.sp, lineHeight = 26.sp) }
        if (problems.size > 3) Text("외 ${problems.size - 3}건은 Logcat 의 Guidance 태그에 있어요", color = c.textSecondary, fontSize = 20.sp)
    }
}

// ---------- 공통 부품 ----------

/** 작은 설명 줄 + 큰 제목 (Title Large 40) */
@Composable
private fun Header(label: String, title: String) {
    val c = LocalPleosColors.current
    Column {
        Text(label, color = c.textSecondary, fontSize = 26.sp)
        Text(title, color = c.textPrimary, fontSize = 40.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    val c = LocalPleosColors.current
    Column(
        modifier = Modifier.fillMaxWidth().clip(CardShape).background(c.surface).border(1.dp, c.outline, CardShape).padding(24.dp),
        content = content,
    )
}

/** 내비의 목적지 카드처럼 색으로 채운 방향 카드: 화살표 + "오른쪽 · 두 번째 경사로" + 눈에 띄는 표지 */
@Composable
private fun DirectionCard(entrance: Entrance, large: Boolean, trailing: String? = null) {
    val c = LocalPleosColors.current
    Row(
        modifier = Modifier.fillMaxWidth().clip(CardShape).background(c.guide).padding(horizontal = 24.dp, vertical = if (large) 28.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SideArrow(entrance.roadSide, size = if (large) 120 else 80, color = c.onGuide)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                SpeechTexts.orderLine(entrance.roadSide, entrance.orderFromApproach),
                color = c.onGuide,
                fontSize = if (large) 44.sp else 36.sp,
                lineHeight = if (large) 56.sp else 44.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(entrance.landmark, color = c.onGuide, fontSize = 28.sp, lineHeight = 36.sp)
        }
        trailing?.let {
            Spacer(Modifier.width(12.dp))
            Text(it, color = c.onGuide, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 반대쪽에서 올 때 방향 카드 자리에 둔다. 좌우·순서 대신 사진의 간판으로 찾게 한다 */
@Composable
private fun OtherDirectionCard(large: Boolean, trailing: String? = null) {
    val c = LocalPleosColors.current
    Row(
        modifier = Modifier.fillMaxWidth().clip(CardShape).background(c.guide).padding(horizontal = 24.dp, vertical = if (large) 28.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "다른 방향에서 오는 중",
                color = c.onGuide,
                fontSize = if (large) 44.sp else 36.sp,
                lineHeight = if (large) 56.sp else 44.sp,
                fontWeight = FontWeight.Bold,
            )
            Text("입구가 길 건너편에 있을 수 있어요 · 사진의 간판을 보고 찾으세요", color = c.onGuide, fontSize = 28.sp, lineHeight = 36.sp)
        }
        trailing?.let {
            Spacer(Modifier.width(12.dp))
            Text(it, color = c.onGuide, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * 경사로 약도: 진행 방향 순서대로 "첫 번째 ✕ 옆 건물 → 두 번째 ✓ 여기 → 세 번째 ✕ 다음 건물".
 * 사진이 없거나 밤이라 사진으로 알아보기 어려울 때도 한눈에 몇 번째인지 알 수 있다.
 * 뒤쪽 칸은 목적지를 지나치면 바로 나오는 다른 건물 경사로(confuserAfter)가 있을 때만 그린다.
 */
@Composable
private fun RampDiagram(entrance: Entrance) {
    val c = LocalPleosColors.current
    val n = entrance.orderFromApproach.coerceIn(1, 4)
    val hasAfter = entrance.confuserAfter != null
    if (n < 2 && !hasAfter) return // 첫 번째 경사로이고 뒤쪽도 없으면 헷갈릴 순서가 없다
    val otherLabel = if (entrance.confuserBefore != null) "옆 건물" else "지나가요"
    val total = if (hasAfter) n + 1 else n
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("진행\n방향", color = c.textSecondary, fontSize = 20.sp, lineHeight = 24.sp, textAlign = TextAlign.Center)
        Text("▶", color = c.textSecondary, fontSize = 22.sp)
        for (i in 1..total) {
            val target = i == n
            val shape = RoundedCornerShape(16.dp)
            Column(
                modifier = Modifier.weight(1f).clip(shape)
                    .background(if (target) c.guide else c.surfaceSunken)
                    .border(1.dp, if (target) c.guide else c.outline, shape)
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (target) "✓" else "✕",
                    color = if (target) c.onGuide else c.negative,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${com.lastmeter.parking.guidance.GuidanceEngine.ordinalKo(i)} 번째",
                    color = if (target) c.onGuide else c.textPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    when {
                        target -> "여기"
                        i > n -> "다음 건물"
                        else -> otherLabel
                    },
                    color = if (target) c.onGuide else c.textSecondary,
                    fontSize = 22.sp,
                )
            }
        }
    }
}

/** 높이 제한. 내 차가 걸리면 빨간 경고, 아니면 한 줄 표시 */
@Composable
private fun HeightNotice(vm: GuidanceController, entrance: Entrance) {
    val c = LocalPleosColors.current
    val limit = entrance.heightLimitM ?: return
    if (vm.tooTallFor(entrance)) {
        WarningCard("높이 제한 ${limit}m · 내 차 ${vm.vehicleHeightM}m는 들어가기 어려워요")
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().clip(CardShape).background(c.surface).border(1.dp, c.outline, CardShape).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("높이 제한", color = c.textSecondary, fontSize = 24.sp)
            Spacer(Modifier.width(12.dp))
            Text("${limit}m", color = c.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            if (vm.vehicleHeightM != null) {
                Spacer(Modifier.weight(1f))
                Text("내 차 ${vm.vehicleHeightM}m 통과", color = c.active, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 지난번 주차 때 "아니었어요"라고 답한 입구면 한 번 더 확인하라고 알려 준다 */
@Composable
private fun FeedbackNotice(vm: GuidanceController, entrance: Entrance) {
    val c = LocalPleosColors.current
    val line = SpeechTexts.feedbackLine(vm.feedbackFor(entrance)) ?: return
    Column(
        modifier = Modifier.fillMaxWidth().clip(CardShape).background(c.surface).border(2.dp, c.textSecondary, CardShape).padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(line, color = c.textPrimary, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
        Text("간판을 한 번 더 확인하세요", color = c.textSecondary, fontSize = 22.sp)
    }
}

/** 운영 시간 안내. 문을 닫았으면 빨간 경고, 무료 개방이나 곧 닫는 것은 한 줄 표시, 평소에는 아무것도 안 그린다 */
@Composable
private fun AccessNotice(vm: GuidanceController, entrance: Entrance) {
    val c = LocalPleosColors.current
    val access = vm.accessFor(entrance) ?: return
    val line = SpeechTexts.accessLine(access, vm.now()) ?: return
    if (access.status == AccessStatus.CLOSED) {
        WarningCard(line)
    } else {
        val free = access.status == AccessStatus.FREE
        Row(
            modifier = Modifier.fillMaxWidth().clip(CardShape).background(c.surface).border(1.dp, c.outline, CardShape).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (free) "요금" else "운영", color = c.textSecondary, fontSize = 24.sp)
            Spacer(Modifier.width(12.dp))
            Text(line, color = if (free) c.active else c.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 헷갈리는 옆 건물 경고. 빨강은 띠와 테두리에만 쓰고 글씨는 본문색으로 둬서 대비를 지킨다 */
@Composable
private fun WarningCard(text: String) {
    val c = LocalPleosColors.current
    Row(
        modifier = Modifier.fillMaxWidth().clip(CardShape).background(c.surface).border(3.dp, c.negative, CardShape).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)).background(c.negative), contentAlignment = Alignment.Center) {
            Text("!", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(16.dp))
        Text(text, color = c.textPrimary, fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold)
    }
}

/** 터치 대상은 높이 76dp 이상 */
@Composable
private fun FilledButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalPleosColors.current
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c.buttonFilled, contentColor = c.onButtonFilled),
        modifier = modifier.heightIn(min = 76.dp),
    ) { Text(label, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun PlainButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalPleosColors.current
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, c.outline),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = c.surface, contentColor = c.textPrimary),
        modifier = modifier.heightIn(min = 76.dp),
    ) { Text(label, fontSize = 26.sp) }
}

/** 입구 사진. 밤 모드에서는 밤에 찍은 사진이 있으면 그쪽을 쓴다. 사진이 아직 없으면 자리 표시 */
@Composable
private fun EntrancePhoto(entrance: Entrance, far: Boolean, otherDirection: Boolean = false, modifier: Modifier = Modifier) {
    val c = LocalPleosColors.current
    val night = isSystemInDarkTheme()
    val day = if (far) entrance.photoFar ?: entrance.photoNear else entrance.photoNear ?: entrance.photoFar
    val nightPhoto = if (far) entrance.photoFarNight ?: entrance.photoNearNight else entrance.photoNearNight ?: entrance.photoFarNight
    val fileName = if (night) nightPhoto ?: day else day
    val context = LocalContext.current
    val bitmap = remember(fileName) {
        fileName?.let { name ->
            runCatching { context.assets.open("entrances/$name").use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
    }
    val m = modifier.aspectRatio(16f / 9f).clip(CardShape).border(1.dp, c.outline, CardShape)
    if (bitmap != null) {
        Box(m) {
            Image(bitmap.asImageBitmap(), contentDescription = "입구 사진", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            // 사진은 데이터의 진입 방향에서 찍었다. 반대쪽에서 오면 사진 속 좌우·화살표가 지금과 다르다는 것을 알린다
            if (otherDirection) {
                Text(
                    "평소 방향에서 찍은 사진 · 간판만 참고하세요",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    } else {
        Box(m.background(c.surfaceSunken), contentAlignment = Alignment.Center) {
            Text("입구 사진 준비 중", color = c.textSecondary, fontSize = 26.sp)
        }
    }
}

/** 도로 어느 쪽인지 가리키는 굵은 화살표 (움직이지 않음) */
@Composable
private fun SideArrow(side: RoadSide, size: Int, color: Color) {
    Canvas(modifier = Modifier.size(size.dp).rotate(if (side == RoadSide.RIGHT) 0f else 180f)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.10f, h * 0.38f)
            lineTo(w * 0.55f, h * 0.38f)
            lineTo(w * 0.55f, h * 0.15f)
            lineTo(w * 0.92f, h * 0.50f)
            lineTo(w * 0.55f, h * 0.85f)
            lineTo(w * 0.55f, h * 0.62f)
            lineTo(w * 0.10f, h * 0.62f)
            close()
        }
        drawPath(path, color)
    }
}
