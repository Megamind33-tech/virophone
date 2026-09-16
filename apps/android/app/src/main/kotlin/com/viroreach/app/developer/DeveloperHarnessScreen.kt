package com.viroreach.app.developer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.viroreach.app.engineering.EngineeringNav
import com.viroreach.core.designsystem.ViroSpacing

@Composable
fun DeveloperHarnessScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(ViroSpacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("← Back to app") }
                Text(
                    "Engineering harness",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.width(72.dp))
            }
        }
        EngineeringNav()
    }
}
