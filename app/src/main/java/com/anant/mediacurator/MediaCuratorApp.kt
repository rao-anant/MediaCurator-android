package com.anant.mediacurator

import android.app.Application

class MediaCuratorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        DebugLog.installCrashHandler()
        // Restore the lifetime deletion counter from its Downloads mirror if this is a
        // fresh install (does its own background I/O; no-op once prefs hold a value).
        DeletionStatsStore.getInstance(this).ensureRestored()
        // Mirror the OS 30-day trash retention on Android ≤10 (no-op on 11+).
        Thread { TrashManager.get(this).purgeExpired() }.start()
    }
}
