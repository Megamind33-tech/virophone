package com.viroreach.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viroreach.core.designsystem.ViroColors

/*
 * The inner screens' kit: one header, one kind of grouped section, one kind
 * of row, one way to say loading / empty / failed, one pair of buttons.
 *
 * Every tab and every screen inside a tab used to build these by hand, each a
 * little differently — a different title size here, a default Material card
 * there, a row that was only a blue link — which is what made the app feel
 * assembled rather than designed. These are drawn from what Now, Chats and
 * Contacts already do (16dp gutters, raised surfaces a touch translucent over
 * the background, generous rounding) and use only the existing palette.
 */

/** Rounding shared by grouped sections and the controls inside screens. */
val ViroSectionShape = RoundedCornerShape(20.dp)
private val ViroControlShape = RoundedCornerShape(16.dp)
private val IconTileShape = RoundedCornerShape(10.dp)

private val TitleStyle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp)
private val RowTitleStyle = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp)
private val RowSubtitleStyle = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
private val SectionLabelStyle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp)
private val ButtonStyle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp)

/** The surface grouped content sits on: the same raised tone as the search bar. */
@Composable
fun viroGroupedSurface(): Color = if (ViroColors.isLight) ViroColors.surface else ViroColors.surfaceRaised.copy(alpha = 0.9f)

// ---------------------------------------------------------------------------
// Screen frame
// ---------------------------------------------------------------------------

/**
 * The header every inner screen uses: back, a title, an optional line under
 * it, and room for actions on the right. Same height and type everywhere.
 */
@Composable
fun ViroScreenHeader(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ViroColors.textPrimary)
            }
            Spacer(Modifier.width(4.dp))
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = TitleStyle,
                color = ViroColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            subtitle?.let {
                Text(it, style = RowSubtitleStyle, color = ViroColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/**
 * An inner screen: the Viro background, safe insets, the shared header, and
 * a body. With [scrollable] the body scrolls as one column with the standard
 * gutters and spacing between sections; without it the body gets the whole
 * remaining space, for screens that bring their own list.
 */
@Composable
fun ViroSubScreen(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    scrollable: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ViroScreenBackground {
        ViroSafeScreen(modifier = modifier, applyNavigationBarsPadding = true, applyImePadding = true) {
            Column(Modifier.fillMaxSize()) {
                ViroScreenHeader(title = title, onBack = onBack, subtitle = subtitle, actions = actions)
                if (scrollable) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        content = content,
                    )
                } else {
                    Column(Modifier.weight(1f).fillMaxWidth(), content = content)
                }
                if (bottomBar != null) {
                    Column(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = bottomBar,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Sections and rows
// ---------------------------------------------------------------------------

/**
 * A group of related rows on one rounded surface, with an optional label
 * above and an explanatory line below — the way settings are grouped in
 * every messenger people already know.
 */
@Composable
fun ViroSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title.uppercase(),
                style = SectionLabelStyle,
                color = ViroColors.textMuted,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp).semantics { heading() },
            )
        }
        Surface(shape = ViroSectionShape, color = viroGroupedSurface(), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp), content = content)
        }
        if (footer != null) {
            Text(
                footer,
                style = RowSubtitleStyle,
                color = ViroColors.textSecondary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
    }
}

/** A hairline between rows that are separate things; inset to line up with the text. */
@Composable
fun ViroRowDivider(inset: Boolean = true) {
    HorizontalDivider(
        modifier = Modifier.padding(start = if (inset) 64.dp else 16.dp, end = 16.dp),
        thickness = 0.5.dp,
        color = ViroColors.textPrimary.copy(alpha = 0.08f),
    )
}

/**
 * The icon in front of a row: the accent on a soft tile of itself, so rows
 * read at a glance without adding a single colour to the palette.
 */
@Composable
fun ViroRowIcon(icon: ImageVector, destructive: Boolean = false) {
    val tint = if (destructive) ViroColors.consumerError else ViroColors.accent
    Box(
        Modifier.size(36.dp).clip(IconTileShape).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/**
 * One row of a section.
 *
 * Leading icon, a title with an optional line beneath, an optional value on
 * the right, and a chevron when tapping it goes somewhere. [trailing]
 * replaces value and chevron for a switch or a button.
 */
@Composable
fun ViroListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    showChevron: Boolean = onClick != null && trailing == null,
    subtitleMaxLines: Int = 2,
) {
    val titleColor = when {
        !enabled -> ViroColors.textMuted
        destructive -> ViroColors.consumerError
        else -> ViroColors.textPrimary
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leading != null -> { leading(); Spacer(Modifier.width(12.dp)) }
            icon != null -> { ViroRowIcon(icon, destructive); Spacer(Modifier.width(12.dp)) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = RowTitleStyle, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = RowSubtitleStyle, color = ViroColors.textSecondary, maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        } else {
            value?.let {
                Spacer(Modifier.width(12.dp))
                Text(
                    it,
                    style = RowSubtitleStyle.copy(fontSize = 14.sp),
                    color = ViroColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
            }
            if (showChevron) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = ViroColors.textMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** A row that is a switch: the whole row toggles it, not only the thumb. */
@Composable
fun ViroSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    ViroListRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        modifier = modifier,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = viroSwitchColors(),
            )
        },
    )
}

/** One option of several, chosen with a tick — theme, text size, who can see what. */
@Composable
fun ViroChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    ViroListRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected },
        trailing = {
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = "Selected", tint = ViroColors.accent, modifier = Modifier.size(22.dp))
            } else {
                Spacer(Modifier.size(22.dp))
            }
        },
    )
}

/** Slider colours from the palette. */
@Composable
fun viroSliderColors() = androidx.compose.material3.SliderDefaults.colors(
    thumbColor = ViroColors.accent,
    activeTrackColor = ViroColors.accent,
    inactiveTrackColor = ViroColors.textPrimary.copy(alpha = 0.14f),
)

/** Switch colours from the palette, the same in every screen. */
@Composable
fun viroSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = ViroColors.onAccent,
    checkedTrackColor = ViroColors.accent,
    checkedBorderColor = ViroColors.accent,
    uncheckedThumbColor = ViroColors.textSecondary,
    uncheckedTrackColor = ViroColors.textPrimary.copy(alpha = 0.10f),
    uncheckedBorderColor = ViroColors.textPrimary.copy(alpha = 0.18f),
)

/** A line of explanation outside any section, in the same voice as section footers. */
@Composable
fun ViroFootnote(text: String, modifier: Modifier = Modifier, textAlign: TextAlign = TextAlign.Start) {
    Text(
        text,
        style = RowSubtitleStyle,
        color = ViroColors.textSecondary,
        textAlign = textAlign,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

// ---------------------------------------------------------------------------
// Fields
// ---------------------------------------------------------------------------

/**
 * A text field in the same filled, borderless style as the Contacts search:
 * the raised surface, no outline at rest, the accent only while focused.
 */
@Composable
fun ViroTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    label: String? = null,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
) {
    androidx.compose.material3.TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        isError = isError,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        textStyle = RowTitleStyle.copy(color = ViroColors.textPrimary),
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, color = ViroColors.textSecondary) } },
        leadingIcon = leadingIcon?.let { { Icon(it, contentDescription = null, tint = ViroColors.textSecondary, modifier = Modifier.size(20.dp)) } },
        trailingIcon = trailing,
        supportingText = supportingText?.let { { Text(it) } },
        shape = ViroControlShape,
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = viroGroupedSurface(),
            unfocusedContainerColor = viroGroupedSurface(),
            disabledContainerColor = viroGroupedSurface().copy(alpha = 0.5f),
            errorContainerColor = viroGroupedSurface(),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
            focusedTextColor = ViroColors.textPrimary,
            unfocusedTextColor = ViroColors.textPrimary,
            focusedLabelColor = ViroColors.accent,
            unfocusedLabelColor = ViroColors.textSecondary,
            cursorColor = ViroColors.accent,
            focusedSupportingTextColor = ViroColors.textSecondary,
            unfocusedSupportingTextColor = ViroColors.textSecondary,
            errorSupportingTextColor = ViroColors.consumerError,
            errorLabelColor = ViroColors.consumerError,
        ),
    )
}

// ---------------------------------------------------------------------------
// Actions
// ---------------------------------------------------------------------------

/** The one strong action on a screen. */
@Composable
fun ViroActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = ViroControlShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = ViroColors.accent,
            contentColor = ViroColors.onAccent,
            disabledContainerColor = ViroColors.accent.copy(alpha = 0.35f),
            disabledContentColor = ViroColors.onAccent.copy(alpha = 0.7f),
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = modifier.fillMaxWidth().height(52.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(strokeWidth = 2.dp, color = ViroColors.onAccent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = ButtonStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A quieter action next to or under the main one. */
@Composable
fun ViroQuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    icon: ImageVector? = null,
) {
    val tone = if (destructive) ViroColors.consumerError else ViroColors.textPrimary
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = ViroControlShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, tone.copy(alpha = if (enabled) 0.22f else 0.10f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = tone, disabledContentColor = ViroColors.textMuted),
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = modifier.fillMaxWidth().height(52.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = ButtonStyle.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------
// States
// ---------------------------------------------------------------------------

/** Loading inside a screen: a small spinner and, optionally, what is loading. */
@Composable
fun ViroLoadingState(modifier: Modifier = Modifier, message: String? = null) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(26.dp), color = ViroColors.accent, strokeWidth = 2.5.dp)
        message?.let { Text(it, style = RowSubtitleStyle.copy(fontSize = 14.sp), color = ViroColors.textSecondary) }
    }
}

/**
 * Nothing here yet, or something went wrong: a title, a line, and at most
 * one thing to do about it. Never a raw error message.
 */
@Composable
fun ViroMessageState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(ViroColors.accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = ViroColors.accent, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
        Text(
            title,
            style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
            color = ViroColors.textPrimary,
            textAlign = TextAlign.Center,
        )
        body?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = RowTitleStyle.copy(fontSize = 15.sp), color = ViroColors.textSecondary, textAlign = TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(actionLabel, style = ButtonStyle.copy(fontSize = 15.sp), color = ViroColors.accent)
            }
        }
    }
}

/** A short status line inside a screen (saved, sent, couldn't do that) — calm, never alarming. */
@Composable
fun ViroStatusLine(text: String, modifier: Modifier = Modifier, positive: Boolean = false) {
    Text(
        text,
        style = RowSubtitleStyle.copy(fontSize = 14.sp),
        color = if (positive) ViroColors.success else ViroColors.textSecondary,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

/** Fills the rest of a scrollable screen so a bottom action can sit at the end. */
@Composable
fun ColumnScope.ViroFlexibleSpace() {
    Spacer(Modifier.weight(1f, fill = true))
}
