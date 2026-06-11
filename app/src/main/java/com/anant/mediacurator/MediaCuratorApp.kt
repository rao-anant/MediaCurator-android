package com.anant.mediacurator

import android.app.Application

class MediaCuratorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        DebugLog.installCrashHandler()
    }
}
