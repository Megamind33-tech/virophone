package com.viroreach.core.designsystem.components

/**
 * What is waiting, per tab.
 *
 * The bottom bar used to say only where you were, which meant the one question
 * people actually open an app to ask — is there anything for me — could only
 * be answered by visiting all five tabs and looking. These are the answers,
 * and every one of them is a real pending thing rather than a decoration:
 * somebody asked to connect, somebody knocked, somebody wrote, somebody rang.
 *
 * Zero means nothing is waiting and nothing is drawn. A badge that is always
 * there stops being read, which would cost more than it gave.
 */
data class ViroNavCounts(
    /** Moments inviting you in, and anyone knocking on yours. */
    val now: Int = 0,
    /** Unread messages, across every conversation not archived or hidden. */
    val chats: Int = 0,
    /** Calls missed since the call list was last looked at. */
    val calls: Int = 0,
    /** People asking to connect, waiting on an answer. */
    val contacts: Int = 0,
) {
    companion object {
        val None = ViroNavCounts()
    }
}
