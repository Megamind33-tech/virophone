package com.viroreach.app.moments.engine
import com.viroreach.core.designsystem.ViroColors

import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.FastForward
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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

    /**
     * Beside the people: the film, small, still running and still in step.
     *
     * This is what makes the room's shape a choice rather than a consequence.
     * Watching together with the picture large and faces in the corner is one
     * way to be in a room; talking with the film small in the corner is
     * another, and both are reasonable at different points in the same
     * evening.
     */
    @Composable
    override fun Secondary(ctx: MomentRoomContext, modifier: Modifier) {
        val media = ctx.player
        if (media != null && media.view.mediaId != null && media.view.kind == "VIDEO") {
            VideoBar(media, modifier)
        }
    }

    @Composable
    private fun VideoBar(media: MomentMediaContext, modifier: Modifier) {
        val scope = rememberCoroutineScope()
        Surface(shape = RoundedCornerShape(20.dp), color = Color.Black.copy(alpha = 0.55f), modifier = modifier) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                // The one player this room has, in a smaller frame. Nothing is
                // re-buffered and nothing restarts: it is the same playback,
                // drawn somewhere else.
                Box(
                    Modifier.width(104.dp).aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp)).background(Color.Black),
                ) {
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
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        media.view.title ?: "Watching",
                        color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (media.view.buffering) "Catching up…" else if (media.view.playing) "Playing" else "Paused",
                        color = Color.White.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                PlayPause(media.view.playing, size = 36) {
                    scope.launch { if (media.view.playing) media.share.playback.pause() else media.share.playback.play() }
                }
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
            val artwork = rememberSongArtwork(media.exo, media.view.mediaId)
            Box(modifier.fillMaxSize()) {
                // The record's own cover, blurred, behind the whole room — then
                // the scene's colours back over the top of it. The room still
                // reads as the room it is; the song only tints it.
                if (artwork != null) {
                    Image(
                        bitmap = artwork.blurred,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                    val look = sceneLook(ctx.runtime.scene)
                    Canvas(Modifier.matchParentSize()) {
                        drawRect(look.base.copy(alpha = 0.62f))
                        drawRect(
                            Brush.radialGradient(
                                colors = listOf(look.glow.copy(alpha = 0.35f), Color.Transparent),
                                center = Offset(size.width * 0.5f, size.height * 0.62f),
                                radius = size.maxDimension * 0.75f,
                            ),
                        )
                        // Keeps the words over it readable whatever the cover is.
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.35f),
                                0.45f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.45f),
                            ),
                        )
                    }
                }
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (media.view.mediaId != null && media.view.kind == "AUDIO") {
                    Spacer(Modifier.height(24.dp))
                    Record(media.view.playing, artwork)
                    Spacer(Modifier.height(24.dp))
                    Text(media.view.title ?: "", color = Color.White, style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    media.items.firstOrNull { it.id == media.view.mediaId }?.let {
                        Text("shared by ${if (it.ownerUserId == ctx.me) "you" else it.ownerName.substringBefore(' ')}",
                            color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(18.dp))
                    Transport(ctx, media, Modifier.fillMaxWidth())
                }
                Chooser(ctx, media, kind = "AUDIO", modifier = Modifier.weight(1f), compact = media.view.mediaId != null)
            }
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

    /**
     * The record, turning, with the song's own cover as its label.
     *
     * A cover on a spinning disc is the thing a record player actually looks
     * like, and it is how somebody knows at a glance what is on. Without one
     * it stays a black disc rather than a blank white square.
     */
    @Composable
    private fun Record(spinning: Boolean, artwork: SongArtwork?) {
        val turn = rememberInfiniteTransition(label = "record")
        val angle by turn.animateFloat(0f, 360f, infiniteRepeatable(tween(6_000, easing = LinearEasing), RepeatMode.Restart), label = "recordAngle")
        Box(
            Modifier.size(180.dp).rotate(if (spinning) angle else 0f).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            if (artwork != null) {
                // Most of the disc, so it reads as a record rather than a photo
                // in a circle, with the vinyl still showing round the edge.
                Image(
                    bitmap = artwork.full,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(126.dp).clip(CircleShape),
                )
                // The spindle hole, which is what stops it looking like a badge.
                Box(Modifier.size(16.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.75f)))
            } else {
                Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                    Text("♪", fontSize = 24.sp, color = Color.Black)
                }
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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun Transport(ctx: MomentRoomContext, media: MomentMediaContext, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val view = media.view
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = view.durationMs ?: 0L
    val shown = if (dragging != null) ((dragging ?: 0f) * duration).toLong() else view.positionMs
    val progress = if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Column(
        modifier.padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The line under the song, not stacked into the controls: the time is
        // information about the music, and the controls are for acting on it.
        // Crushing them into one row is what made this feel like a widget.
        if (duration > 0) {
            Slider(
                value = progress,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    val to = ((dragging ?: 0f) * duration).toLong()
                    dragging = null
                    scope.launch { media.share.playback.seek(to) }
                },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.22f),
                ),
                thumb = {
                    // Small and round. The default thumb is sized for a
                    // settings screen, and on a record it looks like a handle
                    // bolted to the music.
                    Box(
                        Modifier
                            .size(if (dragging != null) 14.dp else 10.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                    )
                },
                track = { state ->
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f))) {
                        Box(
                            Modifier
                                .fillMaxWidth(state.value.coerceIn(0f, 1f))
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(Color.White),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
                Text(
                    clock(shown),
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.weight(1f))
                // What is left, not what there is in total. Nobody listening
                // to a song wants to do the subtraction.
                Text(
                    "-" + clock((duration - shown).coerceAtLeast(0L)),
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SkipButton(seconds = -15) {
                scope.launch { media.share.playback.seek((view.positionMs - 15_000L).coerceAtLeast(0L)) }
            }
            // The one thing the hand goes to, and it is sized like it.
            PlayPause(view.playing, size = 64) {
                scope.launch { if (view.playing) media.share.playback.pause() else media.share.playback.play() }
            }
            SkipButton(seconds = 15) {
                val limit = if (duration > 0) duration else Long.MAX_VALUE
                scope.launch { media.share.playback.seek((view.positionMs + 15_000L).coerceAtMost(limit)) }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Quiet, because changing the record is rare next to playing and
        // pausing it, and because whoever is listening did not come here to
        // manage a queue.
        TextButton(onClick = { scope.launch { media.share.playback.stop() } }) {
            Text(
                "Choose something else",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        // Who moved it, under everything, where it explains a change rather
        // than announcing one.
        whoChanged(ctx)?.let { name ->
            Text(
                if (view.playing) "$name pressed play" else "$name paused",
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (view.error != null) {
            Text(view.error, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Fifteen seconds, the way every player does it, because everybody knows it. */
@Composable
private fun SkipButton(seconds: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (seconds < 0) Icons.Default.Replay else Icons.Default.FastForward,
            contentDescription = if (seconds < 0) "Back 15 seconds" else "Forward 15 seconds",
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(26.dp),
        )
    }
}

/**
 * One track in the list.
 *
 * A row rather than a card. Chunky cards with a text button on the end read as
 * a settings list; a list of music is rows — a cover, the title, who it came
 * from and how long it is, and nothing else competing for the eye. The
 * playing one says so with bars rather than by being a different colour,
 * because colour has to carry the whole thing when it is the only signal.
 */
@Composable
private fun TrackRow(
    item: com.viroreach.core.network.MomentMediaDto,
    current: Boolean,
    playing: Boolean,
    mine: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onPlay)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // No per-track cover exists — the artwork comes out of whatever is
        // loaded in the player — so this is a plate rather than a fake one.
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.06f)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (playing) EqualizerBars() else Text(
                if (item.kind == "VIDEO") "▶" else "♪",
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                color = if (current) ViroColors.BlueAccent else Color.White,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (mine) "You" else item.ownerName.substringBefore(' '),
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item.durationMs?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                clock(it),
                color = Color.White.copy(alpha = 0.45f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (mine) {
            Box {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More",
                    tint = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { menu = true }
                        .padding(6.dp),
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Remove from the room") },
                        onClick = { menu = false; onRemove() },
                    )
                }
            }
        }
    }
}

/** Three bars, moving, because that is what a playing track looks like. */
@Composable
private fun EqualizerBars() {
    val beat = rememberInfiniteTransition(label = "bars")
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 0 until 3) {
            val height by beat.animateFloat(
                initialValue = 6f,
                targetValue = 18f,
                animationSpec = infiniteRepeatable(
                    tween(durationMillis = 520 + i * 160, easing = LinearEasing),
                    RepeatMode.Reverse,
                ),
                label = "bar$i",
            )
            Box(
                Modifier
                    .width(3.dp)
                    .height(height.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ViroColors.BlueAccent),
            )
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
            TrackRow(
                item = item,
                current = current,
                playing = current && media.view.playing,
                mine = item.ownerUserId == ctx.me,
                onPlay = { if (!current) scope.launch { media.share.playback.load(item.id) } },
                onRemove = { scope.launch { ctx.room.unshare(item.id) } },
            )
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
