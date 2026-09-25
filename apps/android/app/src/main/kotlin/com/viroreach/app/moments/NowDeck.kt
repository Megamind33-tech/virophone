package com.viroreach.app.moments

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.viroreach.app.moments.engine.MomentMood
import com.viroreach.app.moments.engine.momentActivityPhoto
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.components.ViroAvatar
import com.viroreach.core.designsystem.components.ViroAvatarSize
import com.viroreach.core.network.MomentDto
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/*
 * Now, as a deck of doorways.
 *
 * One Moment at a time holds the screen; the next one shows its top edge
 * underneath so it is obvious there is more, and a vertical swipe moves
 * between them. There is nothing to count here — no reactions, no views —
 * because a Moment is somebody making room for you, not something posted to be
 * scored. The only two things to do are to go and be with them, or knock.
 */

/** One card in the deck: a Moment, and whether it was offered to this person directly. */
internal data class NowEntry(
    val moment: MomentDto,
    /** Somebody asked for this person specifically. */
    val invited: Boolean,
    /** Set when the Moment only reached this person as an invitation, so it can be turned down. */
    val invitationId: String? = null,
)

/**
 * The picture a Moment carries: its activity's photograph. Moments are
 * served without any image of their own (shared films and songs live inside
 * the room, as files, not on Now), so this is the one visual there is. Words
 * written by hand, and activities with only a drawing, stay text.
 */
internal fun MomentDto.thumbnailRes(): Int? =
    if (composition() == MomentComposition.WORDS) null else momentActivityPhoto(intent)

/** Where a knock on a given Moment stands, as far as this device knows. */
internal enum class KnockState { AVAILABLE, SENDING, SENT }

private val CardShape = RoundedCornerShape(28.dp)
private val ActionShape = RoundedCornerShape(16.dp)
private val DockShape = RoundedCornerShape(20.dp)

/** One gutter for header, deck and composer, so their left edges line up. */
internal val NowGutter = 24.dp
/** How much of the next card stays in view beneath the active one. */
private val Peek = 44.dp
private val PageGap = 12.dp
private val MinCardHeight = 280.dp
/** What a card needs when it carries its activity's photograph, and when it doesn't. */
private val MediaCardHeight = 500.dp
private val TextCardHeight = 330.dp
private val ThumbShape = RoundedCornerShape(20.dp)

// The person and the invitation lead; the activity wording supports rather
// than shouts, so the card reads human first.
private val StatusStyle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp)
private val NameStyle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 23.sp)
private val ContextStyle = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp)
private val MetaStyle = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
private val ActionStyle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp)

/** How somebody is, said the way a person would say it. */
internal fun MomentMood.feeling(): String = when (this) {
    MomentMood.HAPPY -> "In good spirits"
    MomentMood.SAD -> "Feeling a bit low"
    MomentMood.ANGRY -> "Feeling wound up"
    MomentMood.CRAZY -> "All over the place"
}

/** "27 min remaining", "1 h 10 min remaining", or "Ending now". */
internal fun remainingPhrase(minutesLeft: Int): String = when {
    minutesLeft <= 0 -> "Ending now"
    minutesLeft < 60 -> "$minutesLeft min remaining"
    minutesLeft % 60 == 0 -> "${minutesLeft / 60} h remaining"
    else -> "${minutesLeft / 60} h ${minutesLeft % 60} min remaining"
}

/** The personal words on a card, when they add something the status does not already say. */
internal fun MomentDto.personalMessage(): String? =
    invitationText?.trim()?.takeIf { it.isNotEmpty() && !it.equals(activity(), ignoreCase = true) }

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

@Composable
internal fun NowHeader(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = NowGutter, end = NowGutter, top = 12.dp, bottom = 20.dp)) {
        Text(
            "Now",
            color = ViroColors.textPrimary,
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.4).sp),
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Moments from your people",
            color = ViroColors.textMuted,
            style = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp),
        )
    }
}

// ---------------------------------------------------------------------------
// Background
// ---------------------------------------------------------------------------

/**
 * The active Moment's own picture as the room around the deck: cropped to the
 * screen, blurred hard, desaturated and shaded so it is a sense of place and
 * never something to read against. Moments without a photograph leave the
 * ordinary Viro background showing.
 *
 * Decoded small on purpose — a blurred backdrop has no use for full
 * resolution — and through Coil, so switching back to a Moment reuses the
 * cached bitmap rather than decoding it again.
 */
@Composable
internal fun NowBackdrop(intentKey: String?, modifier: Modifier = Modifier) {
    val photo = momentActivityPhoto(intentKey)
    val light = ViroColors.isLight
    val background = ViroColors.background
    Crossfade(
        targetState = photo,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "nowBackdrop",
        modifier = modifier,
    ) { res ->
        if (res == null) {
            Box(Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize()) {
                val context = LocalContext.current
                val request = remember(res) {
                    ImageRequest.Builder(context).data(res).size(240).build()
                }
                val muted = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.55f) }) }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = muted,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(36.dp) else Modifier),
                )
                // The shade follows the appearance, because the header text
                // above it does. Heavier where blur is not available.
                val strong = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                Box(
                    Modifier.fillMaxSize().background(
                        if (light) {
                            Brush.verticalGradient(
                                0f to background.copy(alpha = if (strong) 0.90f else 0.80f),
                                1f to background.copy(alpha = 0.94f),
                            )
                        } else {
                            Brush.verticalGradient(
                                0f to ViroColors.NavyBackground.copy(alpha = if (strong) 0.82f else 0.62f),
                                0.55f to ViroColors.NavySurface.copy(alpha = if (strong) 0.86f else 0.74f),
                                1f to ViroColors.NavyBackground.copy(alpha = 0.94f),
                            )
                        },
                    ),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The deck
// ---------------------------------------------------------------------------

/**
 * The swipeable stack. With more than one Moment the active card holds most of
 * the space and the next one peeks in beneath it; with exactly one, the card
 * simply sits in the middle and nothing pretends there is a second.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun NowMomentDeck(
    entries: List<NowEntry>,
    pager: PagerState,
    clock: () -> Long,
    knockState: (String) -> KnockState,
    onBeWith: (NowEntry) -> Unit,
    onKnock: (NowEntry) -> Unit,
    onDecline: (NowEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // Top-aligned and as tall as its content, never centred in whatever height
    // is left: the deck starts right under the header and the composer
    // follows it, so the screen reads as one surface.
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        // A photograph takes a share of the height, within reason, so a tall
        // phone shows more of the Moment rather than more empty background.
        val thumbHeight = (maxHeight * 0.28f).coerceIn(140.dp, 190.dp)
        if (entries.size == 1) {
            val entry = entries[0]
            NowMomentCard(
                entry = entry,
                clock = clock,
                knock = knockState(entry.moment.id),
                stretch = false,
                thumbHeight = thumbHeight,
                onBeWith = { onBeWith(entry) },
                onKnock = { onKnock(entry) },
                onDecline = { onDecline(entry) },
                modifier = Modifier
                    .padding(horizontal = NowGutter)
                    .heightIn(max = maxHeight),
            )
        } else {
            // Each card carries its own height: a Moment with a photograph
            // needs more room than one of words alone. The pager's slot is the
            // tallest card, and a shorter one simply shows more of the next
            // card beneath it — instead of every card inheriting the tallest
            // height and words floating in half a card of nothing.
            val bounded = maxHeight - Peek - PageGap
            val heightOf = { e: NowEntry ->
                min(bounded, if (e.moment.thumbnailRes() != null) MediaCardHeight else TextCardHeight).coerceAtLeast(MinCardHeight)
            }
            val tallest = entries.maxOf(heightOf)
            VerticalPager(
                state = pager,
                pageSize = PageSize.Fixed(tallest),
                pageSpacing = PageGap,
                contentPadding = PaddingValues(bottom = Peek),
                beyondBoundsPageCount = 1,
                key = { entries[it].moment.id },
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pager,
                    snapAnimationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                ),
                modifier = Modifier.fillMaxWidth().height(tallest + PageGap + Peek),
            ) { page ->
                val entry = entries[page]
                NowMomentCard(
                    entry = entry,
                    clock = clock,
                    knock = knockState(entry.moment.id),
                    stretch = true,
                    thumbHeight = thumbHeight,
                    onBeWith = {
                        // A card still peeking comes forward first; only the
                        // one in front is a door.
                        if (page == pager.currentPage) onBeWith(entry)
                        else scope.launch { pager.animateScrollToPage(page) }
                    },
                    onKnock = { onKnock(entry) },
                    onDecline = { onDecline(entry) },
                    modifier = Modifier
                        .padding(horizontal = NowGutter)
                        .fillMaxWidth()
                        .height(heightOf(entry))
                        .graphicsLayer {
                            // Read here, in the layer, so swiping redraws
                            // rather than recomposes.
                            val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                            val f = min(abs(offset), 1f)
                            if (offset < 0f) {
                                // Below the active card: tucked slightly behind it.
                                transformOrigin = TransformOrigin(0.5f, 0f)
                                scaleX = 1f - 0.05f * f
                                scaleY = 1f - 0.05f * f
                                alpha = 1f - 0.42f * f
                            } else {
                                // On its way out above: eases back and fades.
                                transformOrigin = TransformOrigin(0.5f, 1f)
                                scaleX = 1f - 0.04f * f
                                scaleY = 1f - 0.04f * f
                                alpha = 1f - 0.55f * f
                                translationY = size.height * 0.03f * f
                            }
                        },
                )
            }
        }
    }
}

/**
 * One Moment: who, what, how they are and for how long, what they said — and
 * the two things to do about it. Deliberately without anything to count.
 */
@Composable
internal fun NowMomentCard(
    entry: NowEntry,
    clock: () -> Long,
    knock: KnockState,
    stretch: Boolean,
    thumbHeight: Dp,
    onBeWith: () -> Unit,
    onKnock: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val m = entry.moment
    val thumb = m.thumbnailRes()
    // A picture that will not load is simply not there: the card falls back
    // to its text-only shape rather than showing a broken image.
    var thumbFailed by remember(m.id, thumb) { mutableStateOf(false) }
    val showThumb = thumb != null && !thumbFailed
    val light = ViroColors.isLight
    val cardColor = if (light) ViroColors.surface.copy(alpha = 0.97f) else ViroColors.surface.copy(alpha = 0.94f)
    val edge = if (light) ViroColors.divider else Color.White.copy(alpha = 0.08f)
    val firstName = m.displayName.substringBefore(' ').ifBlank { m.displayName }
    Surface(
        shape = CardShape,
        color = cardColor,
        border = BorderStroke(1.dp, edge),
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp, CardShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.30f)),
    ) {
        Box {
            Column(
                Modifier
                    .then(if (stretch) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                    .clickable(onClickLabel = "Be with $firstName", role = Role.Button, onClick = onBeWith)
                    .padding(20.dp),
            ) {
                CardTopRow(entry, clock, onDecline)
                if (showThumb) {
                    Spacer(Modifier.height(14.dp))
                    MomentThumbnail(
                        m = m,
                        res = thumb!!,
                        onFailed = { thumbFailed = true },
                        // In a stack every card is the same height; the
                        // picture takes up whatever the words leave.
                        modifier = if (stretch) Modifier.weight(1f).heightIn(min = 96.dp) else Modifier.height(thumbHeight),
                    )
                    Spacer(Modifier.height(14.dp))
                } else {
                    LaunchedEffect(m.id, thumbFailed) {
                        NowTrace.card(m, thumbnailPresent = false, loadState = if (thumb == null) "none" else "failed")
                    }
                    // In a stack beside cards with pictures, the words sit in
                    // the middle of the card like a quote rather than leaving
                    // a hole under them.
                    if (stretch) Spacer(Modifier.weight(1f).heightIn(min = 18.dp)) else Spacer(Modifier.height(18.dp))
                }
                Text(
                    m.activity(),
                    color = ViroColors.textPrimary,
                    style = StatusStyle,
                    maxLines = if (showThumb) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                ContextLine(m, clock)
                m.personalMessage()?.let { words ->
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "“$words”",
                        color = ViroColors.textPrimary.copy(alpha = 0.86f),
                        style = ContextStyle,
                        maxLines = if (showThumb) 2 else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                WhoIsHere(m, ViroColors.textMuted)
                if (stretch && !showThumb) Spacer(Modifier.weight(1f).heightIn(min = 20.dp)) else Spacer(Modifier.height(20.dp))
                CardActions(m, clock, knock, firstName, onBeWith, onKnock)
            }
            // The one mark that says the Moment is alive, along the top edge.
            MomentPresenceEdge(
                intent = m.intent ?: "BE",
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 28.dp),
            )
        }
    }
}

/**
 * The Moment's picture, inside the card: full width, cropped, never
 * stretched. A quiet surface holds its place while it loads so nothing
 * jumps, and a failure hands the card back its text-only shape.
 */
@Composable
private fun MomentThumbnail(m: MomentDto, res: Int, onFailed: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var loadState by remember(res) { mutableStateOf("loading") }
    val request = remember(res) {
        // Decoded at card size, not at the photograph's own, and cached by
        // Coil for the next time this Moment comes round.
        ImageRequest.Builder(context).data(res).size(1080, 720).crossfade(200).build()
    }
    Box(
        modifier
            .fillMaxWidth()
            .clip(ThumbShape)
            .background(ViroColors.textPrimary.copy(alpha = 0.06f)),
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
            onState = { state ->
                when (state) {
                    is AsyncImagePainter.State.Success -> loadState = "loaded"
                    is AsyncImagePainter.State.Error -> {
                        loadState = "failed"
                        onFailed()
                    }
                    else -> Unit
                }
            },
        )
    }
    LaunchedEffect(m.id, loadState) { NowTrace.card(m, thumbnailPresent = true, loadState = loadState) }
}

/** Development-only: what each card is showing and why. No URLs, no content. */
internal object NowTrace {
    fun card(m: MomentDto, thumbnailPresent: Boolean, loadState: String) {
        if (!com.viroreach.app.BuildConfig.DEBUG) return
        runCatching {
            android.util.Log.d(
                "ViroNow",
                "moment=${m.id} hasMedia=${m.thumbnailRes() != null} thumbnailPresent=$thumbnailPresent " +
                    "mediaType=${m.intent ?: m.type} loadState=$loadState",
            )
        }
    }
}

@Composable
private fun CardTopRow(entry: NowEntry, clock: () -> Long, onDecline: () -> Unit) {
    val m = entry.moment
    val age by remember(m.createdAt) { derivedStateOf { m.age(clock()) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        PresenceAvatar(m)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                m.displayName,
                color = ViroColors.textPrimary,
                style = NameStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.invited) {
                Text(
                    "Asked for you",
                    color = ViroColors.accent,
                    style = MetaStyle,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(age, color = ViroColors.textMuted, style = MetaStyle, maxLines = 1)
        if (entry.invitationId != null) {
            IconButton(onClick = onDecline, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Not now — turn down ${m.displayName}'s invitation",
                    tint = ViroColors.textMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Their own face, or Viro's default person, with a quiet sign that they are here now. */
@Composable
private fun PresenceAvatar(m: MomentDto) {
    val ring = ViroColors.surface
    Box(Modifier.semantics(mergeDescendants = true) { contentDescription = "${m.displayName}, here now" }) {
        ViroAvatar(size = ViroAvatarSize.Small, imageUrl = m.avatarUrl, displayName = m.displayName)
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(12.dp)
                .clip(CircleShape)
                .background(ring)
                .padding(2.dp)
                .clip(CircleShape)
                .background(ViroColors.GreenAvailable),
        )
    }
}

/**
 * "Feeling a bit low · 27 min remaining". Mood is always said in words, with
 * its colour only as a small mark beside them. Recomposes when the phrase
 * changes, not on every tick of the clock.
 */
@Composable
private fun ContextLine(m: MomentDto, clock: () -> Long) {
    val end = remember(m.expiresAt) { m.endsAt() }
    val remaining by remember(end) { derivedStateOf { remainingPhrase(remainingMinutes(end, clock())) } }
    val mood = MomentMood.of(m.mood)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (mood != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(mood.tint))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            if (mood != null) mood.feeling() + " · " + remaining else remaining,
            color = ViroColors.textMuted,
            style = ContextStyle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CardActions(
    m: MomentDto,
    clock: () -> Long,
    knock: KnockState,
    firstName: String,
    onBeWith: () -> Unit,
    onKnock: () -> Unit,
) {
    val end = remember(m.expiresAt) { m.endsAt() }
    // Only where the room still takes a knock.
    val open by remember(end) { derivedStateOf { end > clock() } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onBeWith,
            shape = ActionShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = ViroColors.accent,
                contentColor = ViroColors.onAccent,
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
            modifier = Modifier.weight(1f).height(52.dp),
        ) {
            Text("Be with them", style = ActionStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (open || knock == KnockState.SENT) {
            Spacer(Modifier.width(12.dp))
            KnockControl(knock, firstName, onKnock)
        }
    }
}

/**
 * Knock is the quieter way in: outlined until used, then no longer a button at
 * all — just a calm note that it has been done.
 */
@Composable
private fun KnockControl(state: KnockState, firstName: String, onKnock: () -> Unit) {
    when (state) {
        KnockState.SENT -> Row(
            Modifier
                .heightIn(min = 52.dp)
                .padding(horizontal = 10.dp)
                .clearAndSetSemantics {
                    contentDescription = "Knocked. $firstName knows you're around."
                    stateDescription = "Sent"
                    disabled()
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = ViroColors.success, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Knocked", color = ViroColors.textMuted, style = ActionStyle.copy(fontWeight = FontWeight.Medium))
        }
        else -> {
            val sending = state == KnockState.SENDING
            OutlinedButton(
                onClick = onKnock,
                enabled = !sending,
                shape = ActionShape,
                border = BorderStroke(1.dp, ViroColors.textPrimary.copy(alpha = if (sending) 0.10f else 0.20f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = ViroColors.textPrimary,
                    disabledContentColor = ViroColors.textMuted,
                ),
                contentPadding = PaddingValues(horizontal = 18.dp),
                modifier = Modifier
                    .height(52.dp)
                    .semantics { contentDescription = if (sending) "Knocking on $firstName's Moment" else "Knock — let $firstName know you're around" },
            ) {
                if (sending) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = ViroColors.textMuted,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("Knock", style = ActionStyle.copy(fontWeight = FontWeight.Medium))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// States around the deck
// ---------------------------------------------------------------------------

/** A still card in the place the first Moment will land, so nothing jumps when it does. */
@Composable
internal fun NowCardSkeleton(modifier: Modifier = Modifier) {
    val block = ViroColors.textPrimary.copy(alpha = 0.07f)
    Surface(
        shape = CardShape,
        color = ViroColors.surface.copy(alpha = 0.9f),
        border = BorderStroke(1.dp, if (ViroColors.isLight) ViroColors.divider else Color.White.copy(alpha = 0.06f)),
        modifier = modifier
            .padding(horizontal = NowGutter)
            .fillMaxWidth()
            .semantics { contentDescription = "Loading Moments" },
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(block))
                Spacer(Modifier.width(12.dp))
                Box(Modifier.height(14.dp).width(120.dp).clip(RoundedCornerShape(7.dp)).background(block))
            }
            Spacer(Modifier.height(22.dp))
            Box(Modifier.height(26.dp).fillMaxWidth(0.7f).clip(RoundedCornerShape(8.dp)).background(block))
            Spacer(Modifier.height(12.dp))
            Box(Modifier.height(14.dp).fillMaxWidth(0.5f).clip(RoundedCornerShape(7.dp)).background(block))
            Spacer(Modifier.height(40.dp))
            Row {
                Box(Modifier.weight(1f).height(52.dp).clip(ActionShape).background(block))
                Spacer(Modifier.width(12.dp))
                Box(Modifier.width(96.dp).height(52.dp).clip(ActionShape).background(block))
            }
        }
    }
}

/** Nobody is around. Said plainly, with the one useful thing to do about it. */
@Composable
internal fun NowEmptyState(showCompose: Boolean, onOpenMoment: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Your people are quiet right now.",
            color = ViroColors.textPrimary,
            style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Open a Moment and let someone know you're around.",
            color = ViroColors.textMuted,
            style = ContextStyle,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (showCompose) {
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onOpenMoment,
                shape = ActionShape,
                colors = ButtonDefaults.buttonColors(containerColor = ViroColors.accent, contentColor = ViroColors.onAccent),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
                contentPadding = PaddingValues(start = 18.dp, end = 22.dp),
                modifier = Modifier.height(52.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open a Moment", style = ActionStyle)
            }
        }
    }
}

/** The list could not be fetched and there is nothing cached to show instead. */
@Composable
internal fun NowLoadError(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Moments couldn't load.", color = ViroColors.textPrimary, style = NameStyle)
        Spacer(Modifier.height(4.dp))
        Text("Check your connection and try again.", color = ViroColors.textMuted, style = MetaStyle.copy(fontSize = 14.sp))
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Try again", color = ViroColors.accent, style = ActionStyle)
        }
    }
}

/** A small line above the dock: a refresh or a knock that did not go through. */
@Composable
internal fun NowNotice(text: String, action: String?, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = ViroColors.textMuted,
            style = MetaStyle.copy(fontSize = 14.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(action, color = ViroColors.accent, style = ActionStyle.copy(fontSize = 14.sp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The composer dock
// ---------------------------------------------------------------------------

/** The way to open your own Moment: part of the dock above the tabs, not another card. */
@Composable
internal fun NowComposer(onOpenMoment: () -> Unit, modifier: Modifier = Modifier) {
    val light = ViroColors.isLight
    Surface(
        onClick = onOpenMoment,
        shape = DockShape,
        color = if (light) ViroColors.surface else ViroColors.surfaceRaised.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, if (light) ViroColors.divider else Color.White.copy(alpha = 0.08f)),
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .semantics { role = Role.Button; contentDescription = "Open a Moment" },
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(ViroColors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = ViroColors.onAccent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text("Open a Moment", color = ViroColors.textPrimary, style = ActionStyle.copy(fontWeight = FontWeight.Medium))
            Spacer(Modifier.weight(1f))
            Text("What are you up to?", color = ViroColors.textMuted, style = MetaStyle, maxLines = 1)
            Spacer(Modifier.width(4.dp))
        }
    }
}

/** Dock-sized frame shared by everything that sits in the dock. */
@Composable
internal fun NowDock(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(start = NowGutter, end = NowGutter, top = 20.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}
