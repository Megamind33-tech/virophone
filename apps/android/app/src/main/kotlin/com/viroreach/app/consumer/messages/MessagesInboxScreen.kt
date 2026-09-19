package com.viroreach.app.consumer.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.viroreach.app.consumer.ChatRoute
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessagesInboxScreen(
    session: SessionManager,
    onOpenChat: (ChatRoute) -> Unit,
) {
    val conversations by session.messagesStore.conversations.collectAsState()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        loading = true
        val userId = session.tokenStore.getUserId()
        runCatching { session.serverMessagesRepository.listConversations(userId) }
            .onSuccess { list ->
                // The server has no idea what you've named this person locally —
                // it can only fall back to a raw id fragment for an untitled DM.
                // Prefer the saved contact's name whenever one is known, same as
                // every other screen that shows a peer identity.
                val enriched = list.map { conv ->
                    val contact = conv.peerUserId?.let { session.contactsRepository.findByUserId(it) }
                    if (contact != null) conv.copy(peerName = contact.effectiveDisplayName) else conv
                }
                session.messagesStore.replaceConversations(enriched)
                error = null
            }
            .onFailure { error = "Couldn't load conversations" }
        loading = false
    }

    LaunchedEffect(Unit) { refresh() }

    ViroScreenBackground {
        ViroSafeScreen {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = ViroSpacing.md, vertical = ViroSpacing.md)) {
                    Text(
                        "Messages",
                        style = MaterialTheme.typography.headlineMedium,
                        color = ViroColors.textPrimary,
                    )
                    Text(
                        "Chats sync across your devices.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ViroColors.textSecondary,
                    )
                }
                when {
                    loading && conversations.isEmpty() -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = ViroColors.accent)
                    }
                    error != null && conversations.isEmpty() -> Column(
                        Modifier.fillMaxSize().padding(ViroSpacing.md),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(error!!, color = ViroColors.textSecondary)
                        TextButton(onClick = { scope.launch { refresh() } }) {
                            Text("Retry", color = ViroColors.accent)
                        }
                    }
                    conversations.isEmpty() -> ViroEmptyState(
                        title = "No conversations yet",
                        message = "Message someone from Contacts or a call to start a chat.",
                    )
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(conversations, key = { it.id }) { conv ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onOpenChat(
                                            ChatRoute(
                                                conversationId = conv.id,
                                                peerName = conv.peerName,
                                                peerUserId = conv.peerUserId,
                                                peerPhoneE164 = conv.phoneE164,
                                            ),
                                        )
                                    }
                                    .padding(horizontal = ViroSpacing.md, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ViroAvatar(
                                    displayName = conv.peerName,
                                    size = ViroAvatarSize.Medium,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        conv.peerName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = ViroColors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        conv.lastMessage.ifBlank { "No messages yet" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = ViroColors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    if (conv.lastTimestampMs > 0) {
                                        Text(
                                            formatInboxTime(conv.lastTimestampMs),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = ViroColors.textMuted,
                                        )
                                    }
                                    if (conv.unreadCount > 0) {
                                        Spacer(Modifier.height(4.dp))
                                        Badge { Text(conv.unreadCount.toString()) }
                                    }
                                }
                            }
                            HorizontalDivider(color = ViroColors.divider)
                        }
                    }
                }
            }
        }
    }
}

private fun formatInboxTime(ms: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
