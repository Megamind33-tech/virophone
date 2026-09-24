package com.viroreach.app.messaging.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.viroreach.app.messaging.MessagingRepository
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.network.GifDto
import com.viroreach.core.network.PollBody
import kotlinx.coroutines.delay

/**
 * Stickers and GIFs, under the composer. Stickers always work. GIF search
 * appears when the server has a provider key; a GIF from the gallery works
 * either way.
 */
@Composable
fun StickerGifPanel(
    repo: MessagingRepository,
    vibe: Vibe,
    onSticker: (Sticker) -> Unit,
    onGif: (GifDto) -> Unit,
    onGifFromGallery: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val features by repo.features.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth().height(320.dp).background(ViroColors.surface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("Stickers", "GIFs").forEachIndexed { i, label ->
                Text(
                    label,
                    color = if (tab == i) vibe.accent else ViroColors.textSecondary,
                    fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clickable { tab = i }.padding(12.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = ViroColors.textSecondary) }
        }
        if (tab == 0) {
            val recent = remember { RecentStickers.get(context) }
            LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                if (recent.isNotEmpty()) {
                    item(span = { GridItemSpan(4) }) { PackTitle("Recent") }
                    items(recent, key = { "r-${it.pack}-${it.id}" }) { s -> StickerCell(s) { RecentStickers.add(context, s); onSticker(s) } }
                }
                STICKER_PACKS.forEach { pack ->
                    item(span = { GridItemSpan(4) }, key = "t-${pack.id}") { PackTitle(pack.title) }
                    items(pack.stickers, key = { "${it.pack}-${it.id}" }) { s -> StickerCell(s) { RecentStickers.add(context, s); onSticker(s) } }
                }
            }
        } else {
            GifTab(repo, enabled = features.gifs == true, provider = features.gifProvider, onGif = onGif, onGifFromGallery = onGifFromGallery)
        }
    }
}

@Composable
private fun PackTitle(t: String) {
    Text(t.uppercase(), color = ViroColors.textSecondary, fontSize = 11.sp, letterSpacing = 1.sp, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun StickerCell(s: Sticker, onClick: () -> Unit) {
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        AnimatedSticker(s, size = 64.dp, animate = false)
    }
}

@Composable
private fun GifTab(
    repo: MessagingRepository,
    enabled: Boolean,
    provider: String?,
    onGif: (GifDto) -> Unit,
    onGifFromGallery: () -> Unit,
) {
    var q by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<GifDto>>(emptyList()) }
    var next by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState()

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        TextButton(onClick = onGifFromGallery) {
            Icon(Icons.Default.Photo, null)
            Spacer(Modifier.width(6.dp))
            Text("Send a GIF from your gallery")
        }
        // if/else, never an early `return@Column`: the Compose compiler (1.5.x)
        // emits one group end too many on an early return out of an inline
        // layout lambda, and the next recomposition that flips the branch
        // crashes in Stack.pop (IndexOutOfBoundsException: Index -1).
        if (!enabled) {
            Text(
                "GIF search isn't switched on yet. GIFs from your gallery still work.",
                color = ViroColors.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(8.dp),
            )
        } else {
            LaunchedEffect(q) {
                delay(350)
                loading = true
                repo.gifs(q).onSuccess {
                    items = it.items.orEmpty()
                    next = it.next
                    error = null
                }.onFailure { error = "Couldn't load GIFs." }
                loading = false
            }
            // Load more at the end.
            val atEnd by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index == grid.layoutInfo.totalItemsCount - 1 } }
            LaunchedEffect(atEnd, next) {
                val pos = next
                if (atEnd && pos != null && !loading && items.isNotEmpty()) {
                    loading = true
                    repo.gifs(q, pos).onSuccess {
                        items = items + it.items.orEmpty()
                        next = it.next
                    }
                    loading = false
                }
            }
            OutlinedTextField(
                value = q,
                onValueChange = { q = it.take(60) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Search ${if (provider == "tenor") "Tenor" else "GIPHY"}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = ViroColors.textSecondary, fontSize = 13.sp) }
            LazyVerticalGrid(GridCells.Fixed(3), state = grid, modifier = Modifier.fillMaxSize().padding(top = 6.dp)) {
                items(items, key = { it.id }) { g ->
                    AsyncImage(
                        model = g.previewUrl ?: g.url,
                        contentDescription = "GIF",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.padding(2.dp).aspectRatio(1f).clip(RoundedCornerShape(8.dp)).clickable { onGif(g) },
                    )
                }
            }
        }
    }
}

@Composable
fun PollCreateDialog(vibe: Vibe, onDismiss: () -> Unit, onCreate: (PollBody) -> Unit) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var multi by remember { mutableStateOf(false) }
    val clean = options.map { it.trim() }.filter { it.isNotEmpty() }
    val valid = question.isNotBlank() && clean.size >= 2 && clean.toSet().size == clean.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create a poll") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = question, onValueChange = { question = it.take(200) }, label = { Text("Question") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                options.forEachIndexed { i, o ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = o,
                            onValueChange = { v ->
                                options[i] = v.take(100)
                                if (i == options.lastIndex && v.isNotBlank() && options.size < 12) options.add("")
                            },
                            label = { Text("Option ${i + 1}") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        if (options.size > 2) {
                            IconButton(onClick = { options.removeAt(i) }) { Icon(Icons.Default.Close, "Remove", tint = ViroColors.textSecondary) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow more than one answer", modifier = Modifier.weight(1f))
                    Switch(checked = multi, onCheckedChange = { multi = it })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onCreate(PollBody(question.trim(), clean, multi)) }) { Text("Send", color = if (valid) vibe.accent else Color.Gray) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
