package com.viroreach.app.consumer.messages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viroreach.app.consumer.ChatRoute
import com.viroreach.app.messaging.ChatMessage
import com.viroreach.app.messaging.ui.previewOf
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Search every chat. Results from this phone show at once and work offline;
 * older messages come from the server a moment later.
 */
@Composable
fun SearchScreen(session: SessionManager, onBack: () -> Unit, onOpen: (ChatRoute) -> Unit) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val conversations by session.messaging.conversations().collectAsState(initial = emptyList())
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val focus = remember { FocusRequester() }
    val me = remember { session.tokenStore.getUserId() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(conversations) {
        names = conversations.associate { c ->
            c.id to (if (c.isGroup) c.title ?: "Group" else c.peerUserId?.let { session.contactsRepository.nameForUserId(it) } ?: "Viro user")
        }
    }
    LaunchedEffect(q) {
        if (q.trim().length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        searching = true
        results = session.messaging.search(q)
        searching = false
    }

    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(ViroColors.background).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = ViroColors.textPrimary) }
            OutlinedTextField(
                value = q,
                onValueChange = { q = it.take(100) },
                singleLine = true,
                placeholder = { Text("Search messages") },
                modifier = Modifier.weight(1f).focusRequester(focus),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = ViroColors.textPrimary, unfocusedTextColor = ViroColors.textPrimary),
            )
        }
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth(), color = ViroColors.accent)
        if (q.trim().length >= 2 && results.isEmpty() && !searching) {
            Text("No messages found.", color = ViroColors.textSecondary, modifier = Modifier.padding(24.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { it.id }) { m ->
                val conv = conversations.firstOrNull { it.id == m.conversationId }
                val chatName = names[m.conversationId] ?: "Chat"
                Column(
                    Modifier.fillMaxWidth().clickable {
                        onOpen(
                            ChatRoute(
                                conversationId = m.conversationId,
                                peerName = chatName,
                                peerUserId = conv?.peerUserId,
                                focusMessageId = m.id,
                            ),
                        )
                    }.padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row {
                        Text(chatName, color = ViroColors.textPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(m.createdAt)), color = ViroColors.textSecondary, fontSize = 12.sp)
                    }
                    Text(
                        highlight((if (m.senderUserId == me) "You: " else "") + previewOf(m), q.trim(), ViroColors.textPrimary),
                        color = ViroColors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider(color = ViroColors.divider)
            }
        }
    }
}

// The colour is handed in rather than read here: this builds a string, not a
// screen, so it cannot ask the theme for anything itself.
private fun highlight(text: String, term: String, match: androidx.compose.ui.graphics.Color) = buildAnnotatedString {
    if (term.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    var i = 0
    val lower = text.lowercase()
    val t = term.lowercase()
    while (i < text.length) {
        val hit = lower.indexOf(t, i)
        if (hit < 0) {
            append(text.substring(i))
            break
        }
        append(text.substring(i, hit))
        withStyle(SpanStyle(color = match, fontWeight = FontWeight.Bold)) { append(text.substring(hit, hit + t.length)) }
        i = hit + t.length
    }
}
