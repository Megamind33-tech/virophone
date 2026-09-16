package com.viroreach.core.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Ensures consumer content respects system bars, cutouts, and optionally IME.
 *
 * Tab screens inside [androidx.compose.material3.Scaffold] should set
 * [applyNavigationBarsPadding] to false — the scaffold bottom bar owns nav insets.
 * Full-screen overlays (dialer, call, auth) should enable nav padding.
 */
@Composable
fun ViroSafeScreen(
    modifier: Modifier = Modifier,
    applyImePadding: Boolean = false,
    applyNavigationBarsPadding: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .then(if (applyNavigationBarsPadding) Modifier.navigationBarsPadding() else Modifier)
            .then(if (applyImePadding) Modifier.imePadding() else Modifier),
        content = content,
    )
}
