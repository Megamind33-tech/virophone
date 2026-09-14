package com.viroreach.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.viroreach.app.diagnostic.DiscoveryDiagnosticScreen
import com.viroreach.core.designsystem.ViroTheme

/**
 * Phase 0 entry point — diagnostic screen for engineering verification.
 * Production UI comes in Phase 1+.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ViroTheme {
                DiscoveryDiagnosticScreen()
            }
        }
    }
}
