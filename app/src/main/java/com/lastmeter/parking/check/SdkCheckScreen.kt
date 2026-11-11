package com.lastmeter.parking.check

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 개발용 점검 화면. 정차 중에만 쓰는 화면이라 주행 중 UI 규칙은 적용하지 않는다. */
@Composable
fun SdkCheckScreen(vm: SdkCheckViewModel, onRequestLocationPermission: () -> Unit) {
    Row(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Column(
            modifier = Modifier.weight(0.42f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("SDK 점검판", fontSize = 28.sp, fontWeight = FontWeight.Bold)

            Section("1. 내비 연동")
            Buttons(
                "initialize" to vm::naviInitialize,
                "목적지 조회" to vm::naviGetDestination,
                "경로 상태 조회" to vm::naviGetRouteState,
            )
            Buttons(
                "현재 위치 조회" to vm::naviGetCurrentLocation,
                "TBT 조회" to vm::naviGetTbt,
            )

            Section("2·3. 위치, 근접 알림")
            Buttons(
                "권한 요청" to onRequestLocationPermission,
                "initialize" to vm::locationInitialize,
            )
            Buttons(
                "실시간 위치 시작" to vm::locationStart,
                "마지막 위치" to vm::locationLastKnown,
            )
            Buttons(
                "현재 위치 300m 알림 등록" to vm::proximityRegisterAtLast,
                "알림 해제" to vm::proximityRemove,
            )

            Section("4. 기어")
            Buttons(
                "initialize" to vm::vehicleInitialize,
                "기어 읽기" to vm::vehicleReadGear,
            )

            Section("5. 음성")
            Buttons(
                "initialize" to vm::ttsInitialize,
                "시험 문장 읽기" to vm::ttsSpeakTest,
            )

            Section("6. 주행 제한")
            Buttons("감시 시작" to vm::uxStart)
        }

        Column(modifier = Modifier.weight(0.58f).fillMaxHeight().padding(start = 16.dp)) {
            Text("로그 (최신이 위)", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            HorizontalDivider()
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(vm.logs) { line ->
                    Text(line, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Buttons(vararg actions: Pair<String, () -> Unit>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { (label, onClick) ->
            Button(onClick = onClick) { Text(label, fontSize = 16.sp) }
        }
    }
}
