package com.viroreach.app.moments.engine

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.viroreach.app.moments.MomentRoomState
import com.viroreach.core.network.MomentDto
import com.viroreach.core.network.MomentParticipantDto
import com.viroreach.core.network.MomentRuntimeDto
import com.viroreach.voice.webrtc.PresenceSnapshot

/**
 * Everything a module is told about the room it is drawn in.
 *
 * Presence, people, time and the ability to change the room are all here, so
 * a new module — studying together, a game — only has to draw itself. It never
 * rebuilds participants, chat, scenes or realtime; those belong to the engine.
 */
class MomentRoomContext(
    val room: MomentRoomState,
    val moment: MomentDto?,
    val runtime: MomentRuntimeDto,
    val participants: List<MomentParticipantDto>,
    val me: String?,
    /** Wall-clock now, ticking once a second, for anything that counts time. */
    val now: Long,
    /** Faces and voices, as they are right now. Empty when there is no live link. */
    val media: PresenceSnapshot = PresenceSnapshot(),
    /** Draws someone's camera; null when this room has no live link. */
    val video: MomentVideo? = null,
    /** Front camera to back, for showing what's in front of you. */
    val flipCamera: () -> Unit = {},
    /** The room's shared player and what has been shared; null without one. */
    val player: MomentMediaContext? = null,
) {
    /** Everyone here but me. */
    val others: List<MomentParticipantDto> get() = participants.filter { it.userId != me }
    val iAmHost: Boolean get() = moment?.creatorUserId == me
}

/**
 * A kind of shared experience a room can be.
 *
 * A module draws itself as the room ([Primary]) and, if it can sit beside
 * another one, compactly ([Secondary]). The engine decides which is which; the
 * server decides for everyone at once, so every phone draws the same room.
 */
interface MomentModule {
    val key: String

    /** Drawn as the room: most of the screen, and the reason to be there. */
    @Composable
    fun Primary(ctx: MomentRoomContext, modifier: Modifier)

    /** Drawn beside the room, small. Modules that can only be the room draw nothing. */
    @Composable
    fun Secondary(ctx: MomentRoomContext, modifier: Modifier) {}
}

/**
 * The modules this build can draw.
 *
 * Adding one is registering it here; the intents that need it appear by
 * themselves. Nothing is offered that cannot be delivered.
 */
object MomentModules {
    private val registered: Map<String, MomentModule> = listOf<MomentModule>(
        PresenceModule,
        QuietModule,
        VideoModule,
        MusicModule,
    ).associateBy { it.key }

    fun find(key: String): MomentModule? = registered[key]

    fun has(key: String): Boolean = registered.containsKey(key)
}

/**
 * What people can come together to do, in their words.
 *
 * [needs] lists every module the activity requires to be real — not just the
 * one the room opens into. "Cook with me" needs a camera, not a list of
 * avatars; until the camera exists here it is not offered.
 */
enum class MomentIntent(
    val key: String,
    val label: String,
    /** How it reads on the card: "Natasha is cooking". */
    val activity: String,
    /** The type the Now feed understands, for Moments made from this intent. */
    val legacyType: String,
    val needs: Set<String>,
) {
    BE("BE", "Be with me", "Wants company", "FREE", setOf("PRESENCE")),
    TALK("TALK", "Talk with me", "Wants to talk", "FREE", setOf("PRESENCE", "VOICE")),
    WATCH("WATCH", "Watch with me", "Watching something", "WATCHING", setOf("VIDEO")),
    LISTEN("LISTEN", "Listen with me", "Listening", "LISTENING", setOf("MUSIC")),
    PLAY("PLAY", "Play with me", "Wants to play", "GAMING", setOf("PRESENCE", "CHOICE")),
    COOK("COOK", "Cook with me", "Cooking", "FREE", setOf("PRESENCE", "CAMERA")),
    WALK("WALK", "Walk with me", "Out for a walk", "FREE", setOf("PRESENCE", "VOICE")),
    CHOOSE("CHOOSE", "Help me choose", "Needs help choosing", "FREE", setOf("PRESENCE", "CHOICE")),
    LEARN("LEARN", "Learn with me", "Learning something", "FREE", setOf("PRESENCE", "CAMERA")),
    CELEBRATE("CELEBRATE", "Celebrate with me", "Celebrating", "FREE", setOf("PRESENCE", "CAMERA")),
    REMEMBER("REMEMBER", "Remember with me", "Remembering", "FREE", setOf("PRESENCE", "VOICE")),
    STAY("STAY", "Just stay", "Here, quietly", "FREE", setOf("QUIET")),
    ;

    /** True when everything this activity needs exists in this build. */
    val available: Boolean get() = needs.all { MomentCapabilities.has(it) }

    companion object {
        fun of(key: String?): MomentIntent? = values().firstOrNull { it.key == key }

        /** What can be started, or turned into, right now. */
        fun offered(): List<MomentIntent> = values().filter { it.available }
    }
}

/**
 * Capabilities beyond the modules themselves — voice, the camera. A module
 * name counts as a capability once it is registered.
 */
object MomentCapabilities {
    /**
     * Voice and the camera come with live presence. When a server has no live
     * media the room says so and carries on as presence, so these stay on.
     */
    private val extra = mutableSetOf("VOICE", "CAMERA")

    fun has(capability: String): Boolean = MomentModules.has(capability) || capability in extra

    internal fun enable(capability: String) {
        extra += capability
    }
}
