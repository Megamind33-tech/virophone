package com.viroreach.app.consumer.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.viroreach.app.consumer.data.ChatMessage
import com.viroreach.app.consumer.resolveAvatarUrl
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.ViroAvatar
import com.viroreach.core.designsystem.components.ViroAvatarSize
import com.viroreach.core.designsystem.components.ViroBackButton
import com.viroreach.core.designsystem.components.ViroSafeScreen
import com.viroreach.core.designsystem.components.ViroScreenBackground
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    session: SessionManager,
    conversationId: String,
    peerName: String,
    peerUserId: String?,
    peerPhoneE164: String? = null,
    peerAvatarUrl: String? = null,
    onBack: () -> Unit,
) {
    val store = session.messagesStore
    val messages by store.conversationMessages(conversationId).collectAsState()
    val conversations by store.conversations.collectAsState()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val conversationPhone = peerPhoneE164
        ?: conversations.find { it.id == conversationId }?.phoneE164
    var resolvedAvatarUrl by remember(conversationId, peerAvatarUrl, conversationPhone) {
        mutableStateOf(peerAvatarUrl)
    }
    var resolvedName by remember(conversationId, peerName) { mutableStateOf(peerName) }

    LaunchedEffect(conversationId, conversationPhone, peerAvatarUrl, peerName) {
        if (!peerAvatarUrl.isNullOrBlank()) {
            resolvedAvatarUrl = peerAvatarUrl
            resolvedName = peerName
            return@LaunchedEffect
        }
        val contact = conversationPhone?.let { session.contactsRepository.findByPhone(it) }
        resolvedAvatarUrl = contact?.resolveAvatarUrl()
        resolvedName = contact?.effectiveDisplayName ?: peerName
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    ViroScreenBackground {
        ViroSafeScreen(applyImePadding = true) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ViroSpacing.md, vertical = ViroSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViroBackButton(onClick = onBack)
                    Spacer(Modifier.width(8.dp))
                    ViroAvatar(
                        displayName = resolvedName,
                        imageUrl = resolvedAvatarUrl,
                        size = ViroAvatarSize.Small,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        resolvedName,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = ViroSpacing.md),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = ViroSpacing.sm),
                ) {
                    items(messages, key = { it.id }) { msg ->
                        MessageBubble(msg)
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ViroSpacing.md, vertical = ViroSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message", color = ViroColors.MutedBlue) },
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = ViroColors.NavySurfaceElevated,
                            unfocusedContainerColor = ViroColors.NavySurfaceElevated,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            if (draft.isBlank()) return@FilledIconButton
                            val outgoing = draft
                            store.sendMessage(conversationId, outgoing)
                            // Persist + deliver via the server (survives offline,
                            // reaches other devices, pushes when peer is away).
                            if (peerUserId != null) {
                                scope.launch {
                                    runCatching {
                                        session.api.sendMessage(
                                            com.viroreach.core.network.SendMessageBody(
                                                toUserId = peerUserId,
                                                body = outgoing,
                                                clientMsgId = java.util.UUID.randomUUID().toString(),
                                            ),
                                        )
                                    }
                                }
                            } else {
                                // No resolved Viro user (e.g. off-network peer) — best-effort LAN/relay chat.
                                session.callManager.sendChatMessage(conversationId, peerUserId, outgoing)
                            }
                            draft = ""
                            scope.launch {
                                if (messages.isNotEmpty()) {
                                    listState.animateScrollToItem(messages.lastIndex)
                                }
                            }
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = ViroColors.ElectricBlue),
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val align = if (message.isOutgoing) Alignment.End else Alignment.Start
    val color = if (message.isOutgoing) ViroColors.ElectricBlue else ViroColors.NavySurfaceElevated
    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Box(
            Modifier
                .widthIn(max = 280.dp)
                .background(color, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(message.body, color = Color.White)
        }
    }
}
