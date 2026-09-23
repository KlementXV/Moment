package com.clockin.hackathon.i18n

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Device-wide UI preference, independent of wallet/session data. Null follows the device. */
internal object AppLanguage {
    private var application: Context? = null
    var selection: String? by mutableStateOf(null)
        private set
    var deviceCode: String by mutableStateOf("fr")
        private set
    val code: String get() = selection ?: deviceCode
    val locale: Locale get() = Locale.forLanguageTag(code)

    fun initialize(context: Context) {
        application = context.applicationContext
        selection = context.getSharedPreferences("language", Context.MODE_PRIVATE)
            .getString("code", null)?.takeIf { it in supported }
        updateDevice(context.resources.configuration)
    }

    fun updateDevice(configuration: Configuration) {
        deviceCode = resolve((0 until configuration.locales.size()).map {
            configuration.locales[it].language
        })
    }

    fun select(code: String?) {
        require(code == null || code in supported)
        application?.getSharedPreferences("language", Context.MODE_PRIVATE)
            ?.edit()?.apply { if (code == null) remove("code") else putString("code", code) }?.apply()
        selection = code
    }

    val supported = listOf("fr", "en")
    fun resolve(languages: List<String>): String = languages.firstOrNull { it in supported } ?: "en"
}
