package com.kuma.bluetoothdebugassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kuma.bluetoothdebugassistant.ui.theme.AppBackground
import com.kuma.bluetoothdebugassistant.ui.theme.BluetoothDebugTheme

class MainActivity : ComponentActivity() {
    private val container: AppContainer
        get() = (application as BluetoothDebugApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val systemBarColor = AppBackground.toArgb()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(systemBarColor, systemBarColor),
            navigationBarStyle = SystemBarStyle.light(systemBarColor, systemBarColor),
        )
        setContent {
            BluetoothDebugTheme {
                val appViewModel: AppViewModel = viewModel(
                    factory = AppViewModel.Factory(
                        container.bluetoothController,
                        container.projectRepository,
                    ),
                )
                BluetoothDebugApp(appViewModel)
            }
        }
    }

}
