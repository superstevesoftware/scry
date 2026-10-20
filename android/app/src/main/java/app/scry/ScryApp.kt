package app.scry

import android.app.Application
import core.Core

/**
 * Runs once per process, before any screen or background job, so every path into the core
 * (the main screen and download jobs) has network information and logging set up.
 */
class ScryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Core.setDebugLogging(BuildConfig.DEBUG)
        Core.setNetworkInfo(NetInfoProvider(this))
    }
}
