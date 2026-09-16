package com.viroreach.app.consumer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.viroreach.app.consumer.data.CallLogEntry
import com.viroreach.app.consumer.data.CallLogType
import com.viroreach.app.consumer.data.syncFromServer
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.*
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun CallsScreen(
    session: SessionManager,
    onCallBack: (phone: String?, name: String?) -> Unit,
    onViewContact: (CallLogEntry) -> Unit,
    onMessage: (phone: String?, name: String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("Recent") }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    val entries by session.callHistoryStore.entries.collectAsState()
    val grouped = remember(entries) { session.callHistoryStore.groupedByDate() }

    LaunchedEffect(Unit) {
        session.callHistoryStore.syncFromServer(session.api, session.tokenStore.getUserId())
    }

    val filtered = entries.filter { entry ->
        val matchesFilter = when (filter) {
            "Missed" -> entry.type == CallLogType.MISSED || entry.type == CallLogType.FAILED
            "Voicemail" -> entry.type == CallLogType.VOICEMAIL
            else -> true
        }
        val matchesSearch = searchQuery.isBlank() ||
            entry.name.contains(searchQuery, true) ||
            (entry.phoneE164?.contains(searchQuery) == true)
        matchesFilter && matchesSearch
    }
    val displayGrouped = if (filter == "Recent") {
        grouped.map { (label, logs) -> label to logs.filter { log -> filtered.any { it.id == log.id } } }
    } else {
        listOf("All" to filtered)
    }

    ViroScreenBackground {
        ViroSafeScreen {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ViroSpacing.md, vertical = ViroSpacing.md),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Viro Call", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                        Text(
                            "PEOPLE. VOICE. PROGRESS.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row {
                        IconButton(onClick = { searchOpen = !searchOpen }) {
                            Icon(Icons.Default.Search, contentDescription = "Search calls", tint = Color.White)
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Call options", tint = Color.White)
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("Clear call history") },
                                    onClick = {
                                        menuExpanded = false
                                        session.callHistoryStore.clearAll()
                                    },
                                )
                            }
                        }
                    }
                }
                if (searchOpen) {
                    ViroSearchBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        placeholder = "Search calls",
                        modifier = Modifier.padding(horizontal = ViroSpacing.md),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                ViroFilterChipRow(
                    listOf("Recent", "Missed", "Voicemail"),
                    filter,
                    { filter = it },
                    modifier = Modifier.padding(horizontal = ViroSpacing.md),
                )
                Spacer(Modifier.height(8.dp))
                if (filtered.isEmpty() && entries.isEmpty()) {
                    ViroEmptyState("No calls yet", "Your recent, missed, and voicemail calls appear here.")
                } else if (filtered.isEmpty()) {
                    ViroEmptyState("No matches", "Try a different search or filter.")
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        displayGrouped.forEach { (dateLabel, logs) ->
                            if (logs.isEmpty()) return@forEach
                            item {
                                Text(
                                    dateLabel,
                                    modifier = Modifier.padding(horizontal = ViroSpacing.md, vertical = 8.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            items(logs, key = { it.id }) { log ->
                                ViroCallLogRow(
                                    name = log.name,
                                    statusLabel = callLogStatusLabel(log.type),
                                    direction = log.type.toCallLogDirection(),
                                    time = formatCallLogTime(log.timestampMs),
                                    duration = if (log.durationSeconds > 0) {
                                        formatDuration(log.durationSeconds)
                                    } else {
                                        "—"
                                    },
                                    isMissed = callLogIsFailure(log.type),
                                    isGroup = log.type == CallLogType.GROUP,
                                    onViewContact = { onViewContact(log) },
                                    onCallBack = { onCallBack(log.phoneE164, log.name) },
                                    onMessage = { onMessage(log.phoneE164, log.name) },
                                    onDelete = { session.callHistoryStore.deleteEntry(log.id) },
                                    onToggleFavorite = log.phoneE164?.let { phone ->
                                        {
                                            scope.launch {
                                                val contact = session.contactsRepository.findByPhone(phone)
                                                contact?.let {
                                                    session.contactsRepository.setFavorite(it.id, true)
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}
