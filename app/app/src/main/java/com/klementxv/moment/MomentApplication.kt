package com.klementxv.moment

import android.app.Application
import com.klementxv.moment.moderation.LocalModerator
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
        com.klementxv.moment.i18n.AppLanguage.updateDevice(newConfig)
    }

    override fun onCreate() {
        super.onCreate()
        com.klementxv.moment.i18n.AppLanguage.initialize(this)
        scope.launch {
            try { moderator.warmUp() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: LinkageError) { }
            catch (_: Exception) { }
        }
    }
}
