package com.viroreach.app.consumer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.ViroBackButton
import com.viroreach.core.designsystem.components.ViroSafeScreen
import com.viroreach.core.designsystem.components.ViroScreenBackground
import kotlinx.coroutines.launch

/** Lists users this account has blocked and lets them be unblocked. */
@Composable
fun BlockedContactsScreen(
    session: SessionManager,
    onBack: () -> Unit,
) {
    var blocked by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        loading = true
        runCatching { session.api.listBlocks() }
            .onSuccess { list -> blocked = list.map { it.blockedUserId }; error = null }
            .onFailure { error = "Couldn't load blocked contacts" }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    ViroScreenBackground {
        ViroSafeScreen {
            Column(Modifier.fillMaxSize().padding(ViroSpacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ViroBackButton(onClick = onBack)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Blocked contacts",
                        style = MaterialTheme.typography.titleLarge,
                        color = ViroColors.textPrimary,
                    )
                }
                Spacer(Modifier.height(ViroSpacing.md))
                when {
                    loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ViroColors.accent)
                    }
                    error != null -> Text(error!!, color = ViroColors.textSecondary)
                    blocked.isEmpty() -> Text(
                        "You haven't blocked anyone.",
                        color = ViroColors.textSecondary,
                    )
                    else -> LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(ViroSpacing.sm),
                    ) {
                        items(blocked, key = { it }) { userId ->
                            Card(colors = CardDefaults.cardColors(containerColor = ViroColors.surface)) {
                                Row(
                                    Modifier.fillMaxWidth().padding(ViroSpacing.md),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        userId.take(12),
                                        color = ViroColors.textPrimary,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    TextButton(onClick = {
                                        scope.launch {
                                            runCatching { session.api.unblockUser(userId) }
                                            reload()
                                        }
                                    }) {
                                        Text("Unblock", color = ViroColors.accent)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
