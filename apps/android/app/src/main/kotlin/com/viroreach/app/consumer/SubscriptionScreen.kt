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
import com.viroreach.core.designsystem.components.*
import com.viroreach.core.designsystem.ViroSpacing
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

    ViroSubScreen(title = "Subscription", onBack = onBack) {
        when {
            loading -> ViroLoadingState()
            error != null -> ViroMessageState(
                title = "Plans couldn't load",
                body = "Check your connection and try again.",
                actionLabel = "Try again",
                onAction = { scope.launch { reload() } },
            )
            else -> ViroSection(
                title = "Plans",
                footer = "Payments aren't open yet, so choosing a plan doesn't charge you anything.",
            ) {
                plans.forEachIndexed { index, plan ->
                    if (index > 0) ViroRowDivider(inset = false)
                    val selected = current?.planId == plan.id ||
                        (current?.isDefault == true && plan.name == "Free")
                    ViroChoiceRow(
                        title = plan.name,
                        subtitle = plan.description?.takeIf { it.isNotBlank() },
                        selected = selected,
                        onClick = {
                            if (!selected) scope.launch {
                                runCatching { session.api.selectSubscription(SelectPlanBody(plan.id)) }
                                    .onSuccess {
                                        current = it
                                        status = "You're on ${it.planName}."
                                    }
                                    .onFailure { status = "Couldn't change your plan. Try again." }
                            }
                        },
                    )
                }
            }
        }
        status?.let { ViroStatusLine(it) }
    }
}
