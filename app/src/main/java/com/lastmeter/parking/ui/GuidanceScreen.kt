package com.lastmeter.parking.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lastmeter.parking.data.Entrance
import com.lastmeter.parking.data.Facility
import com.lastmeter.parking.data.RoadSide
import com.lastmeter.parking.guidance.GuidanceState
import com.lastmeter.parking.guidance.SpeechTexts

/*
 * 주행 중 화면 원칙 (Pleos Driver Distraction 가이드):
 * 큰 글씨(핵심 정보 4mm 이상), 고대비, 한 문장 120자 이내, 3줄 이내, 움직이는 요소 없음,
 * 주행 중(restricted)에는 버튼·설정을 숨긴다. 화면은 내비 옆 반쪽 칸(약 850x1200)을 기준으로 한다.
 */
private val Bg = Color(0xFF111418)
private val CardBg = Color(0xFF1E232A)
private val TextMain = Color(0xFFFFFFFF)
private val TextSub = Color(0xFFB8C0CC)
private val Accent = Color(0xFFFFC400)
private val Warn = Color(0xFFFF6B5A)

@Composable
fun GuidanceScreen(
    vm: GuidanceViewModel,
    onRequestLocationPermission: () -> Unit,
    onOpenSdkCheck: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Bg).padding(24.dp)) {
        when (val s = vm.guidance) {
            is GuidanceState.Approaching -> ApproachCard(s.facility, s.entrance, s.distanceM)
            is GuidanceState.AtEntrance -> AtEntranceCard(s.entrance, s.distanceM)
            is GuidanceState.Passed -> PassedCard(s.facility)
            is GuidanceState.Parked -> ParkedCard(vm, s)
            GuidanceState.Idle -> IdleScreen(vm, onRequestLocationPermission, onOpenSdkCheck)
        }
    }
}

// ---------- 1. 300m 전: 미리 보여 주기 ----------

@Composable
private fun ApproachCard(facility: Facility, entrance: Entrance, distanceM: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(facility.name, color = TextSub, fontSize = 26.sp)
        Text("주차장 입구 ${SpeechTexts.roughDistance(distanceM)}", color = TextMain, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        EntrancePhoto(entrance.photoFar ?: entrance.photoNear)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SideArrow(entrance.roadSide, size = 72)
            Spacer(Modifier.size(16.dp))
            Column {
                Text(SpeechTexts.orderLine(entrance.roadSide, entrance.orderFromApproach), color = Accent, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(entrance.landmark, color = TextMain, fontSize = 28.sp)
            }
        }
    }
}

// ---------- 2·3. 입구 앞: 큰 화살표, 헷갈림 경고 ----------

@Composable
private fun AtEntranceCard(entrance: Entrance, distanceM: Double) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        SideArrow(entrance.roadSide, size = 220)
        Text(SpeechTexts.orderLine(entrance.roadSide, entrance.orderFromApproach), color = Accent, fontSize = 48.sp, fontWeight = FontWeight.Bold)
        Text(entrance.landmark, color = TextMain, fontSize = 32.sp)
        entrance.confusers.firstOrNull()?.let { c ->
            Box(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Warn).padding(20.dp),
            ) {
                Text(c.description, color = Color.Black, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(SpeechTexts.roughDistance(distanceM), color = TextSub, fontSize = 28.sp)
    }
}

// ---------- 4. 지나쳤을 때 ----------

@Composable
private fun PassedCard(facility: Facility) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("입구를 지나쳤어요", color = Warn, fontSize = 48.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("내비가 다시 안내해요", color = TextMain, fontSize = 32.sp)
        Spacer(Modifier.height(8.dp))
        Text(facility.name, color = TextSub, fontSize = 26.sp)
    }
}

// ---------- 5. 주차 완료 (정차 중이라 버튼 사용 가능) ----------

@Composable
private fun ParkedCard(vm: GuidanceViewModel, s: GuidanceState.Parked) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("주차 완료", color = TextSub, fontSize = 26.sp)
        Text(s.facility.name, color = TextMain, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        if (s.likelyOtherBuilding) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Warn).padding(16.dp)) {
                Text("옆 건물 주차장일 수 있어요. 안내판을 확인해 주세요.", color = Color.Black, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }
        InfoRow("높이 제한", s.entrance.heightLimitM?.let { "${it}m" })
        InfoRow("요금", s.entrance.feeText)
        InfoRow("운영 시간", s.entrance.hoursText)
        Spacer(Modifier.height(8.dp))
        Text("안내한 입구가 맞았나요?", color = TextMain, fontSize = 30.sp)
        if (!vm.restricted) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BigButton("맞았어요") { vm.submitFeedback(true) }
                BigButton("아니었어요") { vm.submitFeedback(false) }
            }
        }
        vm.lastFeedback?.let { Text(it, color = Accent, fontSize = 24.sp) }
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    if (value == null) return
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg).padding(16.dp)) {
        Text(label, color = TextSub, fontSize = 26.sp, modifier = Modifier.weight(0.4f))
        Text(value, color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.6f))
    }
}

// ---------- 대기 화면 ----------

@Composable
private fun IdleScreen(vm: GuidanceViewModel, onRequestLocationPermission: () -> Unit, onOpenSdkCheck: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("주차장 입구 안내", color = TextMain, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        val t = vm.target
        Text(
            if (t != null) "${t.name} 입구 300m 전에 알려 드릴게요" else "목적지 근처 주차장 입구를 알려 드려요",
            color = TextSub,
            fontSize = 28.sp,
        )
        vm.naviDestinationName?.let { Text("내비 목적지: $it", color = TextSub, fontSize = 24.sp) }

        if (vm.restricted) return@Column // 주행 중에는 아래 설정을 숨긴다

        if (!vm.locationReady) {
            BigButton("위치 사용 허용") { onRequestLocationPermission() }
        }
        Spacer(Modifier.height(8.dp))
        Text("시연용 목적지 (정차 중에만)", color = TextSub, fontSize = 24.sp)
        vm.facilities.forEach { f ->
            val selected = f.id == t?.id
            if (selected) {
                BigButton("✓ ${f.name}") { vm.selectDemoTarget(null) }
            } else {
                OutlinedButton(onClick = { vm.selectDemoTarget(f) }) { Text(f.name, fontSize = 24.sp, color = TextMain) }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onOpenSdkCheck) { Text("SDK 점검판 (개발용)", fontSize = 20.sp, color = TextSub) }
    }
}

// ---------- 공통 ----------

@Composable
private fun BigButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
        modifier = Modifier.height(72.dp),
    ) { Text(label, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
}

/** 입구 사진. 사진이 아직 없으면 자리 표시 */
@Composable
private fun EntrancePhoto(fileName: String?) {
    val context = LocalContext.current
    val bitmap = remember(fileName) {
        fileName?.let { name ->
            runCatching { context.assets.open("entrances/$name").use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
    }
    val modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), contentDescription = "입구 사진", contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier.background(CardBg), contentAlignment = Alignment.Center) {
            Text("입구 사진 준비 중", color = TextSub, fontSize = 26.sp)
        }
    }
}

/** 도로 어느 쪽인지 가리키는 굵은 화살표 (움직이지 않음) */
@Composable
private fun SideArrow(side: RoadSide, size: Int) {
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
        drawPath(path, Accent)
    }
}
