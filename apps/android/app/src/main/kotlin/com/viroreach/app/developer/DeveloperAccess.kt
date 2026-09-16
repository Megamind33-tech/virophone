package com.viroreach.app.developer

import com.viroreach.app.BuildConfig

object DeveloperAccess {
    fun isHarnessAvailable(): Boolean = BuildConfig.DEBUG
}
