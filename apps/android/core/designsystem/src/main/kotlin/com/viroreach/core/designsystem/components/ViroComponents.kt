package com.viroreach.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroShapes
import com.viroreach.core.designsystem.ViroSpacing

@Composable
fun ViroPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
        shape = ViroShapes.button,
    ) { Text(text) }
}

@Composable
fun ViroSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        shape = ViroShapes.button,
    ) { Text(text) }
}

@Composable
fun ViroTopBar(title: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 0.dp) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(
                horizontal = ViroSpacing.md,
                vertical = ViroSpacing.md,
            ),
        )
    }
}

@Composable
fun ViroPhoneField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        shape = ViroShapes.field,
        textStyle = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
fun ViroContactRow(
    name: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ViroSpacing.md, vertical = ViroSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ViroSpacing.md),
    ) {
        ViroAvatar(displayName = name, imageUrl = imageUrl, size = ViroAvatarSize.Medium)
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

enum class ViroReachabilityVisual {
    READY,
    CONNECTING,
    LIMITED,
    OFFLINE,
    RECONNECTING,
}

@Composable
fun ViroStatusIndicator(
    state: ViroReachabilityVisual,
    modifier: Modifier = Modifier,
) {
    val (label, color) = when (state) {
        ViroReachabilityVisual.READY -> "Calls available" to ViroColors.GreenAvailable
        ViroReachabilityVisual.CONNECTING -> "Connecting…" to ViroColors.ElectricBlue
        ViroReachabilityVisual.LIMITED -> "Weak network" to ViroColors.Warning
        ViroReachabilityVisual.OFFLINE -> "Offline" to ViroColors.Error
        ViroReachabilityVisual.RECONNECTING -> "Reconnecting…" to ViroColors.ElectricBlue
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = ViroColors.NavySurfaceElevated.copy(alpha = 0.92f),
        shadowElevation = 2.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = ViroColors.textPrimary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
fun ViroEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(ViroSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ViroSpacing.sm),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = ViroColors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = ViroColors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun ViroLoadingIndicator(message: String = "Loading…", modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(ViroSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ViroSpacing.md),
    ) {
        ViroPulseMark()
        Text(message, style = MaterialTheme.typography.bodyMedium, color = ViroColors.textSecondary)
    }
}

@Composable
fun ViroPulseMark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(ViroShapes.avatar)
            .background(ViroColors.accent),
        contentAlignment = Alignment.Center,
    ) {
        Text("V", color = ViroColors.textPrimary, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun ViroSkeletonContact(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ViroSpacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(ViroColors.surfaceRaised),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth(0.6f).height(14.dp).background(ViroColors.surfaceRaised))
            Box(Modifier.fillMaxWidth(0.4f).height(12.dp).background(ViroColors.surface))
        }
    }
}

@Composable
fun ViroErrorMessage(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.fillMaxWidth(),
    )
}

enum class ViroConsumerTab {
    Home,
    Contacts,
    Messages,
    Calls,
    You,
}

@Composable
fun ViroBottomNavigation(
    selected: ViroConsumerTab,
    onSelect: (ViroConsumerTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier) {
        NavigationBarItem(
            selected = selected == ViroConsumerTab.Home,
            onClick = { onSelect(ViroConsumerTab.Home) },
            label = { Text("Home") },
            icon = {},
        )
        NavigationBarItem(
            selected = selected == ViroConsumerTab.Contacts,
            onClick = { onSelect(ViroConsumerTab.Contacts) },
            label = { Text("Contacts") },
            icon = {},
        )
        NavigationBarItem(
            selected = selected == ViroConsumerTab.Messages,
            onClick = { onSelect(ViroConsumerTab.Messages) },
            label = { Text("Messages") },
            icon = {},
        )
        NavigationBarItem(
            selected = selected == ViroConsumerTab.Calls,
            onClick = { onSelect(ViroConsumerTab.Calls) },
            label = { Text("Calls") },
            icon = {},
        )
        NavigationBarItem(
            selected = selected == ViroConsumerTab.You,
            onClick = { onSelect(ViroConsumerTab.You) },
            label = { Text("You") },
            icon = {},
        )
    }
}
