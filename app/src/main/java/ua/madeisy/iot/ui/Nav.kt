package ua.madeisy.iot.ui

sealed interface Screen {
    data object Home : Screen
    data class Device(val key: String) : Screen
    data class Edit(val key: String?) : Screen
    data object Settings : Screen
    data object Help : Screen
    data object AddEsp : Screen
}

interface Nav {
    fun push(s: Screen)
    fun pop()
    fun replace(s: Screen)
    fun popTo(s: Screen)
}
