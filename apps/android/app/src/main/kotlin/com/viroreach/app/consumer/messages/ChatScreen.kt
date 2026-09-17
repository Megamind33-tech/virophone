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
import java.util.UUID

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
    var activeConversationId by remember(conversationId) { mutableStateOf(conversationId) }
    // Distinct from activeConversationId: openOrCreateConversation() mints a
    // fresh random UUID for any conversation with no local cache entry yet,
    // purely client-side with no relation to anything on the server. Looking
    // that id up in the local store to decide whether it's "known" was
    // circular — the store always contains an entry for it, since that's how
    // it got created. Only set this once the server has actually confirmed
    // the conversation exists; only then is it safe to send with it.
    var serverConversationId by remember(conversationId) { mutableStateOf<String?>(null) }
    val messages by store.conversationMessages(activeConversationId).collectAsState()
    val conversations by store.conversations.collectAsState()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val currentUserId = session.tokenStore.getUserId()

    val conversationPhone = peerPhoneE164
        ?: conversations.find { it.id == activeConversationId }?.phoneE164
    var resolvedAvatarUrl by remember(activeConversationId, peerAvatarUrl, conversationPhone) {
        mutableStateOf(peerAvatarUrl)
    }
    var resolvedName by remember(activeConversationId, peerName) { mutableStateOf(peerName) }
    var resolvedPeerUserId by remember(activeConversationId, peerUserId) { mutableStateOf(peerUserId) }
    var sendError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(conversationId, resolvedPeerUserId) {
        val userId = currentUserId
        // Prefer server conversation when we know the peer. This used to key
        // off the raw nav-time peerUserId, which is exactly null in the case
        // the resolution effect below fixes (peer only known after a local
        // contact lookup) — so it never ran, activeConversationId stayed a
        // client-only local id the server has never heard of, and every send
        // 404'd with "Conversation not found."
        if (resolvedPeerUserId != null) {
            runCatching {
                val remote = session.serverMessagesRepository.listConversations(userId)
                val match = remote.find { it.peerUserId == resolvedPeerUserId }
                if (match != null) {
                    store.upsertConversation(match)
                    activeConversationId = match.id
                    serverConversationId = match.id
                }
            }
        }
        runCatching {
            val history = session.serverMessagesRepository.history(activeConversationId, userId)
            if (history.isNotEmpty()) store.replaceMessages(activeConversationId, history)
        }
        runCatching {
            session.serverMessagesRepository.markRead(activeConversationId)
            store.clearUnread(activeConversationId)
        }
    }

    LaunchedEffect(activeConversationId, conversationPhone, peerAvatarUrl, peerName, peerUserId) {
        // Always re-check the locally cached contact, even when nav already
        // supplied a photo/name — a device contact almost always has a local
        // photo before it's ever matched to a Viro account, so bailing out
        // early here (as this used to) skipped the one lookup that could
        // populate resolvedPeerUserId, silently leaving "isn't on Viro yet"
        // showing for someone who actually is.
        val contact = conversationPhone?.let { session.contactsRepository.findByPhone(it) }
            ?: peerUserId?.let { session.contactsRepository.findByUserId(it) }
        resolvedAvatarUrl = peerAvatarUrl?.takeIf { it.isNotBlank() } ?: contact?.resolveAvatarUrl()
        resolvedName = contact?.effectiveDisplayName ?: peerName
        resolvedPeerUserId = peerUserId ?: contact?.userId
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
                sendError?.let { message ->
                    Text(
                        message,
                        color = ViroColors.MutedBlue,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = ViroSpacing.md),
                    )
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
                            val toUserId = resolvedPeerUserId
                            if (toUserId == null) {
                                // No known Viro account for this peer — there's no working
                                // transport to deliver a message (the call-signaling channel
                                // this used to fall back to always fails with call_not_found,
                                // since a conversation is never a real call session).
                                sendError = "$resolvedName isn't on Viro yet — invite them to message."
                                return@FilledIconButton
                            }
                            sendError = null
                            val outgoing = draft.trim()
                            draft = ""
                            store.sendMessage(activeConversationId, outgoing)
                            scope.launch {
                                runCatching {
                                    val serverConvId = session.serverMessagesRepository.send(
                                        toUserId = toUserId,
                                        conversationId = serverConversationId,
                                        body = outgoing,
                                        clientMsgId = UUID.randomUUID().toString(),
                                    )
                                    serverConversationId = serverConvId
                                    if (serverConvId != activeConversationId) {
                                        activeConversationId = serverConvId
                                    }
                                    val history = session.serverMessagesRepository.history(
                                        serverConvId,
                                        currentUserId,
                                    )
                                    store.replaceMessages(serverConvId, history)
                                }.onFailure {
                                    sendError = "Couldn't send — try again."
                                }
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
