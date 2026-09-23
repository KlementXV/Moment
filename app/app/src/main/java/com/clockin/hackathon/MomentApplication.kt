package com.clockin.hackathon

import android.app.Application
import com.clockin.hackathon.moderation.LocalModerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MomentApplication : Application() {
    val moderator by lazy { LocalModerator(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.clockin.hackathon.i18n.AppLanguage.updateDevice(newConfig)
    }

    override fun onCreate() {
        super.onCreate()
        com.clockin.hackathon.i18n.AppLanguage.initialize(this)
        scope.launch {
            // A failed warm-up is retried by capture; the UI then exposes Unavailable.
            try { moderator.warmUp() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: LinkageError) { }
            catch (_: Exception) { }
        }
    }
}
