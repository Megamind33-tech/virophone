package com.viroreach.app.consumer

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.components.*


@Composable
fun HelpScreen(onBack: () -> Unit) {
    ViroSubScreen(title = "Help", onBack = onBack) {
        ViroSection(title = "Calls and messages") {
            HelpTopic(
                Icons.Outlined.Call, "Calling",
                "Tap a contact and Call. Viro uses your data connection, not your mobile minutes. " +
                    "Stay on the call screen until you hear the other person.",
            )
            HelpTopic(
                Icons.Outlined.Groups, "Group calls",
                "On Contacts, tap the group icon, choose the people you want, then tap it again to call. " +
                    "Everyone on Viro is invited, and small groups connect to each other directly.",
            )
            HelpTopic(
                Icons.Outlined.ChatBubbleOutline, "Messages",
                "Open Chats for your conversations, or message someone from Contacts or Calls. " +
                    "Messages are end-to-end encrypted and wait on Viro's servers until the other person's phone collects them.",
            )
        }
        ViroSection(title = "Privacy and safety") {
            HelpTopic(
                Icons.Outlined.People, "Connections",
                "You → People → Connections is where you accept or decline requests. " +
                    "You → Privacy → Who can call me chooses whether only connections, or anyone with your Viro ID, can call you.",
            )
            HelpTopic(
                Icons.Outlined.Block, "Blocking",
                "You → Privacy → Blocked contacts lists everyone you've blocked, and unblocks them. " +
                    "Blocked people can't call or message you.",
            )
            HelpTopic(
                Icons.Outlined.Devices, "Linked devices",
                "You → Devices and data → Linked devices shows every device signed in to your account, and signs any of them out.",
            )
        }
        ViroSection(title = "Your account") {
            HelpTopic(
                Icons.AutoMirrored.Outlined.Login, "Signing in",
                "Choose your country, enter the phone number on this device, then enter the code we send. " +
                    "You can also sign in with the email address on your account.",
            )
            HelpTopic(
                Icons.Outlined.Download, "Download my data",
                "You → Devices and data → Download my data makes a copy of your profile, calls, blocks and messages to save or share.",
            )
            HelpTopic(
                Icons.Outlined.DeleteForever, "Deleting your account",
                "You → Devices and data → Delete account permanently closes your Viro identity, signs out every device " +
                    "and removes you from discovery. This can't be undone.",
            )
        }
    }
}

/** A question that opens in place to its answer. */
@Composable
private fun HelpTopic(icon: ImageVector, title: String, body: String) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.animateContentSize()) {
        ViroListRow(
            title = title,
            icon = icon,
            onClick = { open = !open },
            trailing = {
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "Hide answer" else "Show answer",
                    tint = ViroColors.textMuted,
                )
            },
        )
        if (open) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = ViroColors.textSecondary,
                modifier = Modifier.padding(start = 64.dp, end = 16.dp, bottom = 14.dp),
            )
        }
    }
}
