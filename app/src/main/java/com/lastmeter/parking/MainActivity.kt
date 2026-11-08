package com.lastmeter.parking

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.lastmeter.parking.check.SdkCheckScreen
import com.lastmeter.parking.check.SdkCheckViewModel
import com.lastmeter.parking.ui.GuidanceScreen
import com.lastmeter.parking.ui.GuidanceViewModel

class MainActivity : ComponentActivity() {

    private val guidanceViewModel: GuidanceViewModel by viewModels()
    private val checkViewModel: SdkCheckViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var showCheck by remember { mutableStateOf(false) }

            // pleos.car.permission.FUSED_LOCATION 은 설치만으로는 허용되지 않아 실행 중에 요청해야 한다
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                guidanceViewModel.onLocationPermission(granted)
                checkViewModel.log("LOC", "FUSED_LOCATION 권한 결과=$granted")
            }

            LaunchedEffect(Unit) {
                if (hasLocationPermission()) guidanceViewModel.onLocationPermission(true)
                else permissionLauncher.launch(FUSED_LOCATION_PERMISSION)
            }

            BackHandler(enabled = showCheck) { showCheck = false }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (showCheck) {
                        SdkCheckScreen(
                            vm = checkViewModel,
                            onRequestLocationPermission = { permissionLauncher.launch(FUSED_LOCATION_PERMISSION) },
                        )
                    } else {
                        GuidanceScreen(
                            vm = guidanceViewModel,
                            onRequestLocationPermission = { permissionLauncher.launch(FUSED_LOCATION_PERMISSION) },
                            onOpenSdkCheck = { showCheck = true },
                        )
                    }
                }
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, FUSED_LOCATION_PERMISSION) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val FUSED_LOCATION_PERMISSION = "pleos.car.permission.FUSED_LOCATION"
    }
}
