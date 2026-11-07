package ua.madeisy.iot

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import ua.madeisy.iot.data.HistoryDb
import ua.madeisy.iot.data.PlugController
import ua.madeisy.iot.data.Storage
import ua.madeisy.iot.data.ThermostatEngine
import ua.madeisy.iot.ui.ThermostatsScreen
import ua.madeisy.iot.service.MonitorService
import ua.madeisy.iot.service.Notifications
import ua.madeisy.iot.service.ScheduleManager
import ua.madeisy.iot.ui.AppTheme
import ua.madeisy.iot.ui.DeviceScreen
import ua.madeisy.iot.ui.EditScreen
import ua.madeisy.iot.ui.EspAddScreen
import ua.madeisy.iot.ui.FlashScreen
import ua.madeisy.iot.ui.EspConfigScreen
import ua.madeisy.iot.ui.EspDeviceScreen
import ua.madeisy.iot.ui.HelpScreen
import ua.madeisy.iot.ui.HomeScreen
import ua.madeisy.iot.ui.Nav
import ua.madeisy.iot.ui.Screen
import ua.madeisy.iot.ui.SettingsScreen

class IotApp : Application() {
    lateinit var storage: Storage
    lateinit var history: HistoryDb
    lateinit var controller: PlugController
    lateinit var thermostats: ThermostatEngine

    override fun onCreate() {
        super.onCreate()
        instance = this
        storage = Storage(this)
        history = HistoryDb(this)
        controller = PlugController(this, storage, history)
        thermostats = ThermostatEngine(this, storage, controller).also { it.start() }
        Notifications.createChannels(this)
        ScheduleManager.rescheduleAll(this)
    }

    companion object {
        lateinit var instance: IotApp
            private set
    }
}

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_DEVICE = "device"
    }

    private var openDevice by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openDevice = intent?.getStringExtra(EXTRA_DEVICE)
        if (IotApp.instance.storage.settings.value.backgroundMonitor) {
            runCatching { MonitorService.start(this) }
        }
        setContent {
            AppTheme { AppRoot(openDevice) { openDevice = null } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_DEVICE)?.let { openDevice = it }
    }
}

@Composable
private fun AppRoot(openDevice: String?, consumed: () -> Unit) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val nav = remember {
        object : Nav {
            override fun push(s: Screen) { stack.add(s) }
            override fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
            override fun replace(s: Screen) { stack[stack.lastIndex] = s }
            override fun popTo(s: Screen) {
                while (stack.size > 1 && stack.last() != s) stack.removeAt(stack.lastIndex)
            }
        }
    }
    LaunchedEffect(openDevice) {
        val key = openDevice ?: return@LaunchedEffect
        if (IotApp.instance.storage.device(key) != null) {
            stack.clear(); stack.add(Screen.Home); stack.add(Screen.Device(key))
        }
        consumed()
    }
    BackHandler(enabled = stack.size > 1) { nav.pop() }

    AnimatedContent(
        targetState = stack.last(),
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen",
    ) { screen ->
        when (screen) {
            Screen.Home -> HomeScreen(nav)
            is Screen.Device ->
                if (IotApp.instance.storage.device(screen.key)?.isEsp == true) EspDeviceScreen(screen.key, nav)
                else DeviceScreen(screen.key, nav)
            is Screen.Edit ->
                if (screen.key != null && IotApp.instance.storage.device(screen.key)?.isEsp == true) EspConfigScreen(screen.key, nav)
                else EditScreen(screen.key, nav)
            Screen.AddEsp -> EspAddScreen(nav)
            Screen.Thermostats -> ThermostatsScreen(nav)
            Screen.Flash -> FlashScreen(nav)
            Screen.Settings -> SettingsScreen(nav)
            Screen.Help -> HelpScreen(nav)
        }
    }
}
