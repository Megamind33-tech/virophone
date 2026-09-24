package com.viroreach.app.messaging.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.viroreach.app.messaging.ChatMessage
import com.viroreach.app.messaging.MediaFiles
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.network.LinkPreviewDto
import com.viroreach.core.network.MediaDto
import java.io.File

/** A poll: tap to vote (again to change), bars fill as people vote. */
@Composable
fun PollContent(msg: ChatMessage, vibe: Vibe, nameOf: (String) -> String, onVote: (ChatMessage, List<Int>) -> Unit) {
    val poll = msg.poll ?: return
    val total = (poll.totalVoters ?: 0).coerceAtLeast(0)
    val mine = poll.myVotes.orEmpty()
    Column(Modifier.widthIn(min = 230.dp)) {
        Text("📊 ${poll.question}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(
            if (poll.multi == true) "Choose one or more" else "Choose one",
            color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
        )
        Spacer(Modifier.height(6.dp))
        poll.options.orEmpty().forEachIndexed { i, opt ->
            val chosen = i in mine
            val share = if (total == 0) 0f else (opt.votes ?: 0).toFloat() / total
            val animated by animateFloatAsState(share, label = "bar")
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !msg.isPending) {
                        val next = when {
                            poll.multi == true && chosen -> mine - i
                            poll.multi == true -> mine + i
                            chosen -> emptyList()
                            else -> listOf(i)
                        }
                        onVote(msg, next.sorted())
                    }
                    .padding(vertical = 6.dp, horizontal = 2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (chosen) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        null,
                        tint = if (chosen) Color.White else Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(opt.text, color = Color.White, modifier = Modifier.weight(1f))
                    Text("${opt.votes ?: 0}", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.18f))) {
                    Box(Modifier.fillMaxWidth(animated).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(if (chosen) Color.White else vibe.accent))
                }
                // In small groups, who voted for what is part of the fun.
                val voters = opt.voters.orEmpty()
                if (voters.isNotEmpty() && voters.size <= 4) {
                    Text(voters.joinToString { nameOf(it) }, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text("$total ${if (total == 1) "vote" else "votes"}", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
    }
}

/** A GIF, playing inline. */
@Composable
fun GifContent(msg: ChatMessage) {
    val url = msg.gifUrl ?: return
    val ratio = msg.gifSize?.let { (w, h) -> w.toFloat() / h } ?: 1.3f
    Column {
        AsyncImage(
            model = url,
            contentDescription = "GIF",
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(220.dp).aspectRatio(ratio.coerceIn(0.6f, 2f)).clip(RoundedCornerShape(10.dp)),
        )
        Text("GIF", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/** A sticker, drawn big and without a bubble. */
@Composable
fun StickerContent(msg: ChatMessage) {
    val sticker = findSticker(msg.stickerPack, msg.stickerId)
    if (sticker != null) AnimatedSticker(sticker, size = 120.dp)
    else Text(msg.body.orEmpty(), fontSize = 64.sp)
}

/** The card under a message with a link: site, title, summary and a thumbnail. */
@Composable
fun LinkPreviewCard(preview: LinkPreviewDto, media: MediaFiles, mine: Boolean, onClose: (() -> Unit)? = null) {
    val context = LocalContext.current
    val thumb by produceState<File?>(null, preview.mediaId) {
        preview.mediaId?.let { id -> value = media.fetch(MediaDto(id, "IMAGE", "image/jpeg", null, null, null, null, null)) }
    }
    Column(
        Modifier
            .padding(top = 6.dp)
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = if (mine) 0.2f else 0.28f))
            .clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(preview.url))) }
            },
    ) {
        thumb?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(130.dp))
        }
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(preview.siteName ?: Uri.parse(preview.url).host.orEmpty(), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1)
                onClose?.let { Text("✕", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.clickable { it() }.padding(start = 8.dp)) }
            }
            preview.title?.let { Text(it, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            preview.description?.let { Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** "Transcribe" under a voice note, then the text. */
@Composable
fun TranscriptLine(msg: ChatMessage, enabled: Boolean, onTranscribe: (ChatMessage) -> Unit) {
    val text = msg.media?.transcript
    var busy by remember(msg.id) { mutableStateOf(false) }
    LaunchedEffect(text) { if (text != null) busy = false }
    when {
        text != null -> Text(
            text.ifBlank { "(No speech detected)" },
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 14.sp,
            fontStyle = if (text.isBlank()) FontStyle.Italic else FontStyle.Normal,
            modifier = Modifier.padding(top = 6.dp).widthIn(max = 260.dp),
        )
        !enabled || msg.isPending || msg.viewOnce -> Unit
        busy -> Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(12.dp), color = Color.White, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(6.dp))
            Text("Transcribing…", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        }
        else -> Text(
            "Transcribe",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.15f))
                .clickable {
                    busy = true
                    onTranscribe(msg)
                }
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** A stable colour per group member, for their name above their messages. */
fun memberColor(userId: String): Color {
    val palette = listOf(0xFFFF8A80, 0xFFFFD180, 0xFF80D8FF, 0xFFB9F6CA, 0xFFEA80FC, 0xFFFFFF8D, 0xFF84FFFF, 0xFFFF9E80)
    return Color(palette[(userId.hashCode() and 0x7fffffff) % palette.size])
}

@Composable
fun GroupAvatar(title: String, size: androidx.compose.ui.unit.Dp) {
    val initials = title.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "#" }
    Box(
        Modifier.size(size).clip(CircleShape).background(memberColor(title).copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, color = ViroColors.background, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp)
    }
}
