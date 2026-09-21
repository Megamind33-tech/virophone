package com.viroreach.core.designsystem.components

import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.viroreach.core.designsystem.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import android.os.Build
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing

data class ViroWallpaperConfig(
    val imageUri: String? = null,
    val dimAmount: Float = 0.35f,
    val blurRadiusDp: Float = 12f,
    val contrast: Float = 1.1f,
)

val LocalViroWallpaper = staticCompositionLocalOf { ViroWallpaperConfig() }

/**
 * The Viro mark.
 *
 * This drew a blue rounded square containing the letter "V" — a stand-in from
 * before there was artwork. The real logo now ships in the design system module
 * under drawable-<density>, which is where it has to live for this component
 * to reach it: it was added under the app module, and a drawable there is not
 * visible to code in core:designsystem.
 *
 * [compact] drops the wordmark and tagline and shows the mark alone, for places
 * where the brand sits in a corner rather than introducing a screen.
 */
@Composable
fun ViroCallBrand(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.viro_logo),
                // Named rather than decorative: on the auth screens this mark is
                // the only thing identifying which app is asking for a phone
                // number, which a screen reader user needs told.
                contentDescription = "Viro",
                modifier = Modifier.size(if (compact) 28.dp else 40.dp),
                contentScale = ContentScale.Fit,
            )
            if (!compact) {
                Text(
                    "People closer",
                    style = MaterialTheme.typography.labelSmall,
                    color = ViroColors.TaglineBlue,
                    letterSpacing = 0.5.sp,
                )
            }
        }
    }
}

/**
 * The mark on its own at a chosen size, for the startup screen — where a small
 * corner badge would be lost and the wordmark is already rendered as text.
 */
/** The artwork's natural size; rendering larger visibly softens it. */
val NATIVE_LOGO_DP = 96.dp

@Composable
fun ViroLogoMark(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = NATIVE_LOGO_DP,
) {
    Image(
        painter = painterResource(R.drawable.viro_logo),
        contentDescription = "Viro",
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
    )
}

@Composable
fun ViroScreenBackground(content: @Composable BoxScope.() -> Unit) {
    val wallpaper = LocalViroWallpaper.current
    CompositionLocalProvider(LocalContentColor provides ViroColors.textPrimary) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!wallpaper.imageUri.isNullOrBlank()) {
                val context = LocalContext.current
                val blurMod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wallpaper.blurRadiusDp > 0f) {
                    Modifier.blur(wallpaper.blurRadiusDp.dp)
                } else {
                    Modifier
                }
                val contrast = wallpaper.contrast.coerceIn(0.7f, 1.6f)
                val scrimAlpha = (wallpaper.dimAmount + (contrast - 1f) * 0.3f).coerceIn(0f, 0.85f)
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(wallpaper.imageUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(blurMod),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to Color(0xFF00122C).copy(alpha = (scrimAlpha * 0.55f).coerceIn(0.25f, 0.4f)),
                                    0.45f to Color.Black.copy(alpha = scrimAlpha.coerceIn(0.3f, 0.42f)),
                                    1f to Color(0xFF00122C).copy(alpha = (scrimAlpha * 1.05f).coerceIn(0.35f, 0.48f)),
                                ),
                            ),
                        ),
                )
                if (contrast < 1f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = ((1f - contrast) * 0.15f).coerceIn(0f, 0.12f))),
                    )
                }
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(ViroColors.background),
                )
            }
            Box(Modifier.fillMaxSize(), content = content)
        }
    }
}

@Composable
fun ViroContinueButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = ViroColors.ElectricBlue,
            disabledContainerColor = ViroColors.NavySurfaceElevated,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, fontWeight = FontWeight.SemiBold)
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}

@Composable
fun ViroPhoneInputCard(
    countryCode: String,
    phoneDigits: String,
    onCountryClick: () -> Unit,
    modifier: Modifier = Modifier,
    flagEmoji: String = "🇿🇲",
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "Phone number",
            style = MaterialTheme.typography.labelMedium,
            color = ViroColors.MutedBlue,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(ViroColors.NavySurfaceElevated)
                .border(1.dp, ViroColors.ElectricBlue, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.clickable(onClick = onCountryClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(flagEmoji, fontSize = 20.sp)
                Spacer(Modifier.width(8.dp))
                Text(countryCode, color = Color.White, fontWeight = FontWeight.Medium)
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = ViroColors.MutedBlue)
            }
            Spacer(
                Modifier
                    .padding(horizontal = 12.dp)
                    .width(1.dp)
                    .height(28.dp)
                    .background(ViroColors.MutedBlue.copy(alpha = 0.4f)),
            )
            Text(
                phoneDigits.ifEmpty { " " },
                modifier = Modifier.weight(1f),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
fun ViroNumericKeypad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = listOf(
        listOf("1" to "", "2" to "ABC", "3" to "DEF"),
        listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
        listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { (digit, sub) ->
                    KeypadKey(
                        digit = digit,
                        subLabel = sub,
                        onClick = { onDigit(digit) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            KeypadKey(digit = "*", subLabel = "", onClick = { onDigit("*") }, modifier = Modifier.weight(1f))
            KeypadKey(digit = "0", subLabel = "+", onClick = { onDigit("0") }, modifier = Modifier.weight(1f))
            KeypadKey(digit = "#", subLabel = "", onClick = { onDigit("#") }, modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.weight(1f))
            KeypadIconKey(
                icon = Icons.Default.Clear,
                onClick = onBackspace,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun KeypadKey(
    digit: String,
    subLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ViroColors.NavySurfaceElevated)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(digit, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            if (subLabel.isNotEmpty()) {
                Text(subLabel, color = ViroColors.MutedBlue, fontSize = 9.sp, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
private fun KeypadIconKey(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ViroColors.NavySurfaceElevated)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = "Backspace", tint = Color.White)
    }
}

@Composable
fun ViroSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        placeholder = {
            Text(
                placeholder,
                color = ViroColors.MutedBlue,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = ViroColors.MutedBlue,
                modifier = Modifier.size(20.dp),
            )
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = ViroColors.NavySurfaceElevated.copy(alpha = 0.94f),
            unfocusedContainerColor = ViroColors.NavySurfaceElevated.copy(alpha = 0.88f),
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = ViroColors.ElectricBlue,
        ),
    )
}

@Composable
fun ViroFilterChipRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Surface(
                onClick = { onSelect(option) },
                shape = RoundedCornerShape(20.dp),
                color = if (isSelected) ViroColors.ElectricBlue else Color.Transparent,
                border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, ViroColors.MutedBlue),
            ) {
                Text(
                    option,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = if (isSelected) Color.White else ViroColors.MutedBlue,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
fun ViroContactRow(
    name: String,
    formattedPhone: String?,
    showViroBadge: Boolean,
    onRowClick: () -> Unit,
    onCall: () -> Unit,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    onMessage: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
) {
    val openProfile: () -> Unit = {
        if (selectionMode) onToggleSelect?.invoke() else onRowClick()
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ViroSpacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelect?.invoke() })
            Spacer(Modifier.width(4.dp))
        }
        ViroAvatar(
            imageUrl = imageUrl,
            size = ViroAvatarSize.Medium,
            modifier = Modifier
                .size(ViroAvatarSize.Medium.diameter)
                .clickable(onClick = openProfile),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = openProfile),
        ) {
            Text(
                name,
                color = ViroColors.textPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            formattedPhone?.let {
                Text(
                    it,
                    color = ViroColors.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showViroBadge) {
                Text("Viro", color = ViroColors.success, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (!selectionMode) {
            IconButton(onClick = { onMessage?.invoke() }, enabled = onMessage != null) {
                Icon(Icons.Default.Email, contentDescription = "Message", tint = ViroColors.textSecondary)
            }
            IconButton(onClick = onCall) {
                Icon(Icons.Default.Call, contentDescription = "Call", tint = ViroColors.accent)
            }
            IconButton(onClick = { onMore?.invoke() }, enabled = onMore != null) {
                Icon(Icons.Default.MoreVert, contentDescription = "More", tint = ViroColors.textSecondary)
            }
        }
    }
    HorizontalDivider(color = ViroColors.divider, thickness = 0.5.dp)
}

@Composable
fun ViroReferenceContactRow(
    name: String,
    statusLabel: String,
    onViroCall: Boolean,
    onCall: () -> Unit,
    onMessage: () -> Unit,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    formattedPhone: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onCall)
            .padding(horizontal = ViroSpacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViroAvatar(
            displayName = name,
            imageUrl = imageUrl,
            size = ViroAvatarSize.Medium,
            modifier = Modifier.size(ViroAvatarSize.Medium.diameter),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            formattedPhone?.let {
                Text(
                    it,
                    color = ViroColors.MutedBlue,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (onViroCall) ViroColors.GreenAvailable else ViroColors.MutedBlue),
                )
                Spacer(Modifier.width(6.dp))
                Text(statusLabel, color = ViroColors.MutedBlue, style = MaterialTheme.typography.bodySmall)
            }
        }
        IconButton(onClick = onMessage) {
            Icon(Icons.Default.Email, contentDescription = "Message", tint = ViroColors.MutedBlue)
        }
        IconButton(onClick = onCall) {
            Icon(Icons.Default.Call, contentDescription = "Call", tint = ViroColors.ElectricBlue)
        }
        IconButton(onClick = {}) {
            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = ViroColors.MutedBlue)
        }
    }
    HorizontalDivider(color = ViroColors.NavySurfaceElevated, thickness = 0.5.dp)
}

enum class ViroCallLogDirection {
    INCOMING,
    OUTGOING,
    MISSED,
    FAILED,
    COMPLETED,
    GROUP,
}

@Composable
fun ViroCallLogRow(
    name: String,
    statusLabel: String,
    time: String,
    duration: String,
    isMissed: Boolean,
    isGroup: Boolean = false,
    onViewContact: () -> Unit,
    onCallBack: () -> Unit,
    onMessage: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    direction: ViroCallLogDirection = ViroCallLogDirection.COMPLETED,
    messageInOverflow: Boolean = false,
    horizontalPadding: Dp = ViroSpacing.md,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val resolvedDirection = when {
        direction != ViroCallLogDirection.COMPLETED -> direction
        isGroup -> ViroCallLogDirection.GROUP
        isMissed -> ViroCallLogDirection.MISSED
        else -> direction
    }
    val statusColor = when (resolvedDirection) {
        ViroCallLogDirection.FAILED,
        ViroCallLogDirection.MISSED,
        -> ViroColors.RedEndCall
        else -> ViroColors.MutedBlue
    }
    val directionIcon = when (resolvedDirection) {
        ViroCallLogDirection.OUTGOING -> Icons.AutoMirrored.Filled.CallMade
        ViroCallLogDirection.INCOMING -> Icons.AutoMirrored.Filled.CallReceived
        ViroCallLogDirection.MISSED -> Icons.AutoMirrored.Filled.CallMissed
        ViroCallLogDirection.FAILED -> Icons.Default.Close
        ViroCallLogDirection.GROUP -> Icons.Default.Group
        ViroCallLogDirection.COMPLETED -> Icons.Default.Check
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onViewContact)
            .padding(horizontal = horizontalPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViroAvatar(
            displayName = name,
            imageUrl = imageUrl,
            size = ViroAvatarSize.Medium,
            modifier = Modifier.size(ViroAvatarSize.Medium.diameter),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    directionIcon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    statusLabel,
                    color = statusColor,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (time.isNotBlank()) {
                    Text(
                        " • $time",
                        color = ViroColors.MutedBlue,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
            }
            if (duration.isNotBlank() && duration != "—") {
                Text(
                    duration,
                    color = ViroColors.MutedBlue,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (!messageInOverflow) {
            IconButton(
                onClick = onMessage,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Default.Email, contentDescription = "Message", tint = ViroColors.MutedBlue)
            }
        }
        FilledTonalIconButton(
            onClick = onCallBack,
            modifier = Modifier.size(40.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = ViroColors.ElectricBlue.copy(alpha = 0.18f),
                contentColor = ViroColors.ElectricBlue,
            ),
        ) {
            Icon(Icons.Default.Call, contentDescription = "Call", modifier = Modifier.size(20.dp))
        }
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "More", tint = ViroColors.MutedBlue)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("View contact") },
                    onClick = { menuExpanded = false; onViewContact() },
                )
                if (messageInOverflow) {
                    DropdownMenuItem(
                        text = { Text("Message") },
                        onClick = { menuExpanded = false; onMessage() },
                    )
                }
                onToggleFavorite?.let { toggle ->
                    DropdownMenuItem(
                        text = { Text("Add to favorites") },
                        onClick = { menuExpanded = false; toggle() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = { menuExpanded = false; onDelete() },
                )
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = horizontalPadding),
        color = ViroColors.NavySurfaceElevated.copy(alpha = 0.65f),
        thickness = 0.5.dp,
    )
}

@Composable
fun ViroConsumerBottomBar(
    selected: ViroConsumerTab,
    onSelect: (ViroConsumerTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier.fillMaxWidth(),
        windowInsets = NavigationBarDefaults.windowInsets,
        containerColor = ViroColors.NavyBackground.copy(alpha = 0.98f),
        contentColor = ViroColors.MutedBlue,
        tonalElevation = 8.dp,
    ) {
        val tabs = listOf(
            ViroConsumerTab.Now to (Icons.Default.Bolt to "Now"),
            ViroConsumerTab.Chats to (Icons.Default.Chat to "Chats"),
            ViroConsumerTab.Calls to (Icons.Default.Phone to "Calls"),
            ViroConsumerTab.Contacts to (Icons.Default.Contacts to "Contacts"),
            ViroConsumerTab.You to (Icons.Default.AccountCircle to "You"),
        )
        tabs.forEach { (tab, iconLabel) ->
            val isSelected = selected == tab
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(tab) },
                icon = {
                    Icon(
                        iconLabel.first,
                        contentDescription = iconLabel.second,
                        modifier = Modifier.size(22.dp),
                        tint = if (isSelected) ViroColors.ElectricBlue else ViroColors.MutedBlue.copy(alpha = 0.85f),
                    )
                },
                label = {
                    Text(
                        iconLabel.second,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) ViroColors.ElectricBlue else ViroColors.MutedBlue.copy(alpha = 0.85f),
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = ViroColors.ElectricBlue.copy(alpha = 0.14f),
                    selectedIconColor = ViroColors.ElectricBlue,
                    selectedTextColor = ViroColors.ElectricBlue,
                    unselectedIconColor = ViroColors.MutedBlue.copy(alpha = 0.85f),
                    unselectedTextColor = ViroColors.MutedBlue.copy(alpha = 0.85f),
                ),
            )
        }
    }
}

@Composable
fun ViroBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
    }
}

data class ViroCallControl(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val activeIcon: ImageVector = icon,
    val isActive: Boolean = false,
)

@Composable
fun ViroEndCallButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(76.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = ViroColors.RedEndCall),
        ) {
            Icon(
                Icons.Default.CallEnd,
                contentDescription = "End call",
                tint = Color.White,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("End call", color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun ViroCallControlGrid(
    controls: List<ViroCallControl>,
    onControl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        controls.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                row.forEach { control ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = { onControl(control.id) },
                            modifier = Modifier.size(60.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (control.isActive) {
                                    ViroColors.ElectricBlue.copy(alpha = 0.35f)
                                } else {
                                    ViroColors.NavySurfaceElevated
                                },
                            ),
                        ) {
                            Icon(
                                if (control.isActive) control.activeIcon else control.icon,
                                contentDescription = control.label,
                                tint = if (control.isActive) ViroColors.ElectricBlue else Color.White,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            control.label,
                            color = if (control.isActive) Color.White else ViroColors.MutedBlue,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ViroIncomingCallActions(
    onDecline: () -> Unit,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FilledIconButton(
                onClick = onDecline,
                modifier = Modifier.size(76.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = ViroColors.RedEndCall),
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = "Decline", tint = Color.White, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text("Decline", color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FilledIconButton(
                onClick = onAccept,
                modifier = Modifier.size(76.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = ViroColors.GreenAvailable),
            ) {
                Icon(Icons.Default.Call, contentDescription = "Accept", tint = Color.White, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text("Accept", color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}
