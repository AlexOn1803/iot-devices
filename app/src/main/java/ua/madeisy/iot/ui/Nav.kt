package ua.madeisy.iot.ui

sealed interface Screen {
    data object Home : Screen
    data class Device(val key: String) : Screen
    data class Edit(val key: String?) : Screen
    data object Settings : Screen
    data object Help : Screen
    data object MqttHelp : Screen
    data object AddEsp : Screen
    data object Thermostats : Screen
    data object Flash : Screen
    data class Inverter(val sn: String) : Screen
}

interface Nav {
    fun push(s: Screen)
    fun pop()
    fun replace(s: Screen)
    fun popTo(s: Screen)
}
