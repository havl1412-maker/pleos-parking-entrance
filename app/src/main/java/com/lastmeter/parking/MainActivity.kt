package com.lastmeter.parking

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.lastmeter.parking.check.SdkCheckScreen
import com.lastmeter.parking.check.SdkCheckViewModel

class MainActivity : ComponentActivity() {

    private val checkViewModel: SdkCheckViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // pleos.car.permission.FUSED_LOCATION 은 설치만으로는 허용되지 않아 실행 중에 요청해야 한다
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted -> checkViewModel.log("LOC", "FUSED_LOCATION 권한 결과=$granted") }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SdkCheckScreen(
                        vm = checkViewModel,
                        onRequestLocationPermission = { permissionLauncher.launch(FUSED_LOCATION_PERMISSION) },
                    )
                }
            }
        }
    }

    companion object {
        const val FUSED_LOCATION_PERMISSION = "pleos.car.permission.FUSED_LOCATION"
    }
}
