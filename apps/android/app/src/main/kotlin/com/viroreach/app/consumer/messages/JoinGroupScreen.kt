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
import com.viroreach.core.designsystem.components.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import com.viroreach.core.designsystem.ViroSpacing
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
            .onFailure { error = "This invite link isn't working." }
        loading = false
    }

    ViroSubScreen(title = "Group invite", onBack = onBack) {
        when {
            loading -> ViroLoadingState()
            error != null -> ViroMessageState(
                title = error!!,
                body = "Ask whoever sent it for a new link — group links can be reset or turned off.",
                icon = Icons.Default.LinkOff,
            )
            else -> preview?.let { p ->
                Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    GroupAvatar(p.title ?: "Group", 96.dp)
                    Spacer(Modifier.height(ViroSpacing.md))
                    Text(p.title ?: "Group", color = ViroColors.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    p.description?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = ViroColors.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    p.memberCount?.let {
                        Text("$it ${if (it == 1) "member" else "members"}", color = ViroColors.textSecondary, fontSize = 13.sp)
                    }
                }
                if (p.alreadyMember == true) {
                    ViroActionButton("Open the group", onClick = { onJoined(p.conversationId, p.title ?: "Group") })
                    ViroFootnote("You're already in this group.", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                } else {
                    ViroActionButton(
                        text = "Join group",
                        busy = joining,
                        onClick = {
                            if (!joining) {
                                joining = true
                                scope.launch {
                                    session.messaging.joinGroupByInvite(code)
                                        .onSuccess { onJoined(it, p.title ?: "Group") }
                                        .onFailure { error = "Couldn't join the group." }
                                    joining = false
                                }
                            }
                        },
                    )
                    ViroFootnote(
                        "Everyone in the group will see that you joined, with your name and photo.",
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}
