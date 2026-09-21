package com.viroreach.app.moments.engine

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.viroreach.core.designsystem.components.ViroAvatar
import com.viroreach.core.designsystem.components.ViroAvatarSize
import com.viroreach.core.network.MomentMediaDto
import kotlinx.coroutines.launch

/** Everything a media module needs about the room's one shared player. */
class MomentMediaContext(
    val share: MomentMediaShare,
    val view: SharedPlayerView,
    val items: List<MomentMediaDto>,
    val exo: androidx.media3.exoplayer.ExoPlayer?,
)

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** "Natasha paused", "You pressed play" — who changed the player, in words. */
private fun whoChanged(ctx: MomentRoomContext): String? {
    val by = ctx.player?.view?.lastChangeBy ?: return null
    if (by == ctx.me) return null
    return ctx.participants.firstOrNull { it.userId == by }?.displayName?.substringBefore(' ')
}

/**
 * Watching something together. The film is the room; the people are a small
 * row of faces over it, and anyone can pause for everyone.
 */
object VideoModule : MomentModule {
    override val key = "VIDEO"

    @Composable
    override fun Primary(ctx: MomentRoomContext, modifier: Modifier) {
        val media = ctx.player
        if (media == null) {
            Unavailable(modifier)
        } else if (media.view.mediaId != null && media.view.kind == "VIDEO") {
            Screen(ctx, media, modifier)
        } else {
            Chooser(ctx, media, kind = "VIDEO", modifier = modifier)
        }
    }

    @Composable
    private fun Screen(ctx: MomentRoomContext, media: MomentMediaContext, modifier: Modifier) {
        var controls by remember { mutableStateOf(true) }
        Box(modifier.fillMaxSize().background(Color.Black).clickable { controls = !controls }) {
            val exo = media.exo
            if (exo != null) {
                AndroidView(
                    factory = { context ->
                        PlayerView(context).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            player = exo
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { it.player = null },
                )
            }
            FaceStrip(ctx, Modifier.align(Alignment.TopStart).padding(12.dp))
            if (media.view.buffering) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text("Catching up…", color = Color.White.copy(alpha = 0.8f))
                }
            }
            if (controls || !media.view.playing) {
                Transport(ctx, media, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            }
        }
    }
}

/**
 * Listening together. A turning record, what is playing and who brought it,
 * and the songs people have shared.
 */
object MusicModule : MomentModule {
    override val key = "MUSIC"

    @Composable
    override fun Primary(ctx: MomentRoomContext, modifier: Modifier) {
        val media = ctx.player
        if (media == null) {
            Unavailable(modifier)
        } else {
            Column(modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (media.view.mediaId != null && media.view.kind == "AUDIO") {
                    Spacer(Modifier.height(24.dp))
                    Record(media.view.playing)
                    Spacer(Modifier.height(24.dp))
                    Text(media.view.title ?: "", color = Color.White, style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    media.items.firstOrNull { it.id == media.view.mediaId }?.let {
                        Text("shared by ${if (it.ownerUserId == ctx.me) "you" else it.ownerName.substringBefore(' ')}",
                            color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(12.dp))
                    Transport(ctx, media, Modifier.fillMaxWidth())
                }
                Chooser(ctx, media, kind = "AUDIO", modifier = Modifier.weight(1f), compact = media.view.mediaId != null)
            }
        }
    }

    /** Beside cooking, talking, anything: a slim bar with the song and one button. */
    @Composable
    override fun Secondary(ctx: MomentRoomContext, modifier: Modifier) {
        val media = ctx.player
        if (media != null) MusicBar(ctx, media, modifier)
    }

    @Composable
    private fun MusicBar(ctx: MomentRoomContext, media: MomentMediaContext, modifier: Modifier) {
        val scope = rememberCoroutineScope()
        var choosing by remember { mutableStateOf(false) }
        Surface(shape = RoundedCornerShape(20.dp), color = Color.Black.copy(alpha = 0.45f), modifier = modifier) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("♪", color = Color.White, fontSize = 18.sp)
                    Spacer(Modifier.width(10.dp))
                    val playingSong = media.view.mediaId != null && media.view.kind == "AUDIO"
                    Text(
                        if (playingSong) media.view.title ?: "" else "Music",
                        color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clickable { choosing = !choosing },
                    )
                    if (playingSong) {
                        PlayPause(media.view.playing, size = 36) {
                            scope.launch { if (media.view.playing) media.share.playback.pause() else media.share.playback.play() }
                        }
                    } else {
                        TextButton(onClick = { choosing = !choosing }) { Text(if (choosing) "Close" else "Choose", color = Color.White) }
                    }
                }
                if (choosing) Chooser(ctx, media, kind = "AUDIO", modifier = Modifier.fillMaxWidth().height(220.dp), compact = true)
            }
        }
    }

    @Composable
    private fun Record(spinning: Boolean) {
        val turn = rememberInfiniteTransition(label = "record")
        val angle by turn.animateFloat(0f, 360f, infiniteRepeatable(tween(6_000, easing = LinearEasing), RepeatMode.Restart), label = "recordAngle")
        Box(
            Modifier.size(180.dp).rotate(if (spinning) angle else 0f).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                Text("♪", fontSize = 24.sp, color = Color.Black)
            }
        }
    }
}

/** Faces over the film: who is watching with you, small. */
@Composable
private fun FaceStrip(ctx: MomentRoomContext, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ctx.others.take(4).forEach { p ->
            val peer = ctx.media.peers.firstOrNull { it.identity == p.userId }
            val video = ctx.video
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                if (video != null && peer?.cameraOn == true && !peer.videoPaused) {
                    video.Show(p.userId, Modifier.fillMaxSize())
                } else {
                    ViroAvatar(size = ViroAvatarSize.Small, displayName = p.displayName)
                }
            }
        }
    }
}

@Composable
private fun PlayPause(playing: Boolean, size: Int, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = Color.White, modifier = Modifier.size(size.dp).clickable(
        onClickLabel = if (playing) "Pause for everyone" else "Play for everyone", onClick = onClick)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", tint = Color.Black)
        }
    }
}

/** Play, pause and where we are — pressing any of them does it for everyone. */
@Composable
private fun Transport(ctx: MomentRoomContext, media: MomentMediaContext, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val view = media.view
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = view.durationMs ?: 0L
    Column(modifier.background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 16.dp, vertical = 10.dp)) {
        whoChanged(ctx)?.let { name ->
            Text(if (view.playing) "$name pressed play" else "$name paused", color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium)
        }
        if (media.view.error != null) {
            Text(media.view.error, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayPause(view.playing, size = 48) {
                scope.launch { if (view.playing) media.share.playback.pause() else media.share.playback.play() }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(view.title ?: "", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (duration > 0) {
                    Slider(
                        value = dragging ?: (view.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            val to = ((dragging ?: 0f) * duration).toLong()
                            dragging = null
                            scope.launch { media.share.playback.seek(to) }
                        },
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White),
                    )
                    Text("${clock(if (dragging != null) ((dragging ?: 0f) * duration).toLong() else view.positionMs)} / ${clock(duration)}",
                        color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                }
            }
            TextButton(onClick = { scope.launch { media.share.playback.stop() } }) { Text("Change", color = Color.White) }
        }
    }
}

/**
 * What has been shared, to pick from, and a way to share something from this
 * phone. What people may share is said plainly, once.
 */
@Composable
private fun Chooser(ctx: MomentRoomContext, media: MomentMediaContext, kind: String, modifier: Modifier, compact: Boolean = false) {
    val scope = rememberCoroutineScope()
    val progress by media.share.progress.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) media.share.share(uri, thenPlay = true)
    }
    val items = media.items.filter { it.kind == kind }
    androidx.compose.foundation.lazy.LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!compact) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (kind == "VIDEO") "What shall we watch?" else "What shall we listen to?",
                        color = Color.White, style = MaterialTheme.typography.headlineSmall)
                    if (kind == "VIDEO" && media.view.kind == "AUDIO") {
                        Text("A song is playing. Choosing a video replaces it.", color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        items(items.size, key = { items[it].id }) { i ->
            val item = items[i]
            val current = item.id == media.view.mediaId
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.White.copy(alpha = if (current) 0.22f else 0.1f),
                modifier = Modifier.fillMaxWidth().clickable(enabled = !current) { scope.launch { media.share.playback.load(item.id) } },
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                if (item.ownerUserId == ctx.me) "from you" else "from ${item.ownerName.substringBefore(' ')}",
                                item.durationMs?.let { clock(it) },
                            ).joinToString(" · "),
                            color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (item.ownerUserId == ctx.me) {
                        TextButton(onClick = { scope.launch { ctx.room.unshare(item.id) } }) {
                            Text("Remove", color = Color.White.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val p = progress
                if (p != null && p.error == null) {
                    Text("Sharing ${p.title}…", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth(), color = Color.White)
                } else {
                    if (p?.error != null) {
                        Text(p.error, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.clickable { media.share.dismissProblem() })
                        Spacer(Modifier.height(6.dp))
                    }
                    Surface(
                        shape = RoundedCornerShape(24.dp), color = Color.White,
                        modifier = Modifier.clickable { media.share.dismissProblem(); pick.launch(if (kind == "VIDEO") "video/*" else "audio/*") },
                    ) {
                        Text(if (kind == "VIDEO") "Share a video from your phone" else "Share a song from your phone",
                            color = Color.Black, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                    }
                }
                if (!compact) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Share only what you have the right to share. It plays only for the people here, " +
                            "leaves with you, and is deleted when the Moment ends.",
                        color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun Unavailable(modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("The player isn't ready on this phone.", color = Color.White, textAlign = TextAlign.Center)
    }
}
