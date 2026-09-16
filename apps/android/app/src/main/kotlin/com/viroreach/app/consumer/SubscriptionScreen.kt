package com.viroreach.app.consumer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.ViroBackButton
import com.viroreach.core.designsystem.components.ViroSafeScreen
import com.viroreach.core.designsystem.components.ViroScreenBackground
import com.viroreach.core.network.PlanDto
import com.viroreach.core.network.SelectPlanBody
import com.viroreach.core.network.SubscriptionDto
import kotlinx.coroutines.launch

@Composable
fun SubscriptionScreen(
    session: SessionManager,
    onBack: () -> Unit,
) {
    var plans by remember { mutableStateOf<List<PlanDto>>(emptyList()) }
    var current by remember { mutableStateOf<SubscriptionDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        loading = true
        runCatching {
            plans = session.api.listPlans()
            current = session.api.getMySubscription()
        }.onSuccess { error = null }
            .onFailure { error = "Couldn't load plans" }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    ViroScreenBackground {
        ViroSafeScreen {
            Column(Modifier.fillMaxSize().padding(ViroSpacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ViroBackButton(onClick = onBack)
                    Spacer(Modifier.width(ViroSpacing.sm))
                    Text(
                        "Subscription",
                        style = MaterialTheme.typography.titleLarge,
                        color = ViroColors.textPrimary,
                    )
                }
                Spacer(Modifier.height(ViroSpacing.sm))
                Text(
                    "Billing providers are not wired yet. You can preview plan selection.",
                    color = ViroColors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(ViroSpacing.md))
                current?.let {
                    Text(
                        "Current: ${it.planName}",
                        color = ViroColors.textPrimary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                status?.let {
                    Text(it, color = ViroColors.accent, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(ViroSpacing.md))
                when {
                    loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ViroColors.accent)
                    }
                    error != null -> Text(error!!, color = ViroColors.textSecondary)
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(ViroSpacing.sm)) {
                        items(plans, key = { it.id }) { plan ->
                            val selected = current?.planId == plan.id ||
                                (current?.isDefault == true && plan.name == "Free")
                            Card(colors = CardDefaults.cardColors(containerColor = ViroColors.surface)) {
                                Column(Modifier.padding(ViroSpacing.md)) {
                                    Text(
                                        plan.name,
                                        color = ViroColors.textPrimary,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        plan.description ?: "",
                                        color = ViroColors.textSecondary,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    if (selected) {
                                        Text("Selected", color = ViroColors.accent)
                                    } else {
                                        TextButton(onClick = {
                                            scope.launch {
                                                runCatching {
                                                    session.api.selectSubscription(SelectPlanBody(plan.id))
                                                }.onSuccess {
                                                    current = it
                                                    status = "Switched to ${it.planName}"
                                                }.onFailure {
                                                    status = "Couldn't change plan"
                                                }
                                            }
                                        }) {
                                            Text("Select", color = ViroColors.accent)
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
}
