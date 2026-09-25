package com.viroreach.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.viroreach.core.designsystem.ViroColors

enum class ViroAvatarSize(val diameter: Dp) {
    /** Beside a chat message, where a full Small circle would crowd the bubble. */
    Tiny(28.dp),
    Small(40.dp),
    Medium(48.dp),
    Large(72.dp),
    Hero(120.dp),
}

enum class ViroAvatarKind {
    REMOTE_PROFILE_IMAGE,
    LOCAL_CONTACT_IMAGE,
    USER_PROFILE_IMAGE,
    DEFAULT_PERSON,
}

@Composable
fun ViroAvatar(
    modifier: Modifier = Modifier,
    size: ViroAvatarSize = ViroAvatarSize.Medium,
    imageUrl: String? = null,
    kind: ViroAvatarKind = resolveAvatarKind(imageUrl),
    displayName: String = "",
) {
    val diameter = size.diameter
    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .background(ViroColors.surfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        if (!imageUrl.isNullOrBlank()) {
            val context = LocalContext.current
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .crossfade(true)
                    .size((diameter.value * 2).toInt())
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                loading = { DefaultPersonIcon(diameter) },
                error = { DefaultPersonIcon(diameter) },
            )
        } else {
            DefaultPersonIcon(diameter)
        }
    }
}

private fun resolveAvatarKind(imageUrl: String?): ViroAvatarKind {
    val url = imageUrl ?: return ViroAvatarKind.DEFAULT_PERSON
    if (url.isBlank()) return ViroAvatarKind.DEFAULT_PERSON
    return when {
        url.startsWith("content://") -> ViroAvatarKind.LOCAL_CONTACT_IMAGE
        url.startsWith("file://") -> ViroAvatarKind.USER_PROFILE_IMAGE
        else -> ViroAvatarKind.REMOTE_PROFILE_IMAGE
    }
}

@Composable
private fun DefaultPersonIcon(diameter: Dp) {
    Icon(
        imageVector = Icons.Default.Person,
        contentDescription = null,
        tint = ViroColors.textSecondary,
        modifier = Modifier.size(diameter * 0.45f),
    )
}
