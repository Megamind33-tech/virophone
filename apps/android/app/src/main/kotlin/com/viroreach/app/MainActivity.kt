package com.viroreach.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.viroreach.app.engineering.EngineeringNav
import com.viroreach.core.designsystem.ViroTheme

/**
 * Phase 1A engineering entry point — auth, call test, diagnostics.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ViroTheme {
                EngineeringNav()
            }
        }
    }
}
