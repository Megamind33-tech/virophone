package com.viroreach.app

import android.app.Application
import com.viroreach.app.session.SessionManager

class ViroReachApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SessionManager.get(this)
    }
}
