package com.kuma.bluetoothdebugassistant

import android.app.Application
import androidx.room.Room
import com.kuma.bluetoothdebugassistant.bluetooth.AndroidBluetoothController
import com.kuma.bluetoothdebugassistant.bluetooth.BluetoothController
import com.kuma.bluetoothdebugassistant.bluetooth.FakeBluetoothController
import com.kuma.bluetoothdebugassistant.project.AppDatabase
import com.kuma.bluetoothdebugassistant.project.ProjectRepository
import com.kuma.bluetoothdebugassistant.project.RoomProjectRepository

class BluetoothDebugApplication : Application() {
    val container: AppContainer by lazy { DefaultAppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.bluetoothController.start()
    }

    override fun onTerminate() {
        container.bluetoothController.stop()
        super.onTerminate()
    }
}

interface AppContainer {
    val bluetoothController: BluetoothController
    val database: AppDatabase
    val projectRepository: ProjectRepository
}

class DefaultAppContainer(application: Application) : AppContainer {
    override val bluetoothController: BluetoothController =
        if (application.resources.getBoolean(R.bool.use_fake_bluetooth)) {
            FakeBluetoothController(autoDemo = true)
        } else {
            AndroidBluetoothController(application)
        }
    override val database: AppDatabase by lazy {
        Room.databaseBuilder(
            application,
            AppDatabase::class.java,
            "bluetooth-debug-assistant.db",
        ).build()
    }
    override val projectRepository: ProjectRepository by lazy {
        RoomProjectRepository(database.projectDao())
    }
}

class FakeAppContainer(
    override val database: AppDatabase,
    override val projectRepository: ProjectRepository,
) : AppContainer {
    override val bluetoothController: BluetoothController = FakeBluetoothController()
}
