package com.viroreach.app.consumer.messages

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viroreach.app.messaging.ui.GroupAvatar
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.ViroBackButton
import com.viroreach.core.designsystem.components.ViroErrorMessage
import com.viroreach.core.designsystem.components.ViroSafeScreen
import com.viroreach.core.designsystem.components.ViroScreenBackground
import com.viroreach.core.network.GroupInvitePreviewDto
import kotlinx.coroutines.launch

/**
 * Someone opened a group link. The group is shown first — name, description
 * and how many people are in it — and joining is their decision.
 */
@Composable
fun JoinGroupScreen(
    session: SessionManager,
    code: String,
    onBack: () -> Unit,
    onJoined: (conversationId: String, title: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<GroupInvitePreviewDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var joining by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(code) {
        loading = true
        session.messaging.groupInvitePreview(code)
            .onSuccess { preview = it; error = null }
            .onFailure { error = it.message }
        loading = false
    }

    ViroScreenBackground {
        ViroSafeScreen {
            Column(Modifier.fillMaxSize().padding(horizontal = ViroSpacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ViroBackButton(onClick = onBack)
                    Spacer(Modifier.width(ViroSpacing.sm))
                    Text("Group invite", style = MaterialTheme.typography.titleLarge, color = ViroColors.textPrimary)
                }
                Spacer(Modifier.height(ViroSpacing.xl))
                when {
                    loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ViroColors.accent)
                    }
                    error != null -> Column(Modifier.fillMaxWidth()) {
                        ViroErrorMessage(error!!)
                        Spacer(Modifier.height(ViroSpacing.md))
                        Text(
                            "Ask whoever sent it for a new link — group links can be reset or turned off.",
                            color = ViroColors.textSecondary,
                        )
                    }
                    else -> preview?.let { p ->
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            GroupAvatar(p.title ?: "Group", 88.dp)
                            Spacer(Modifier.height(ViroSpacing.md))
                            Text(
                                p.title ?: "Group",
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            p.description?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(it, color = ViroColors.textSecondary)
                            }
                            p.memberCount?.let {
                                Text(
                                    "$it ${if (it == 1) "member" else "members"}",
                                    color = ViroColors.textSecondary,
                                    fontSize = 13.sp,
                                )
                            }
                            Spacer(Modifier.height(ViroSpacing.xl))
                            if (p.alreadyMember == true) {
                                Button(
                                    onClick = { onJoined(p.conversationId, p.title ?: "Group") },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Open the group") }
                                Spacer(Modifier.height(8.dp))
                                Text("You're already in this group.", color = ViroColors.textSecondary, fontSize = 13.sp)
                            } else {
                                Button(
                                    onClick = {
                                        if (joining) return@Button
                                        joining = true
                                        scope.launch {
                                            session.messaging.joinGroupByInvite(code)
                                                .onSuccess { onJoined(it, p.title ?: "Group") }
                                                .onFailure { error = it.message }
                                            joining = false
                                        }
                                    },
                                    enabled = !joining,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(if (joining) "Joining…" else "Join group") }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Everyone in the group will see that you joined, and your name and photo.",
                                    color = ViroColors.textMuted,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
