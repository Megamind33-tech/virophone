package com.viroreach.app.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.*
import com.viroreach.feature.contacts.PhoneNumberFormatter

@Composable
fun LoginScreen(
    phoneDigits: String,
    loading: Boolean,
    errorMessage: String?,
    returningUser: Boolean = false,
    onPhoneDigitsChange: (String) -> Unit,
    onContinue: () -> Unit,
) {
    val displayPhone = remember(phoneDigits) {
        PhoneNumberFormatter.formatForDisplay(phoneDigits)
    }

    ViroScreenBackground {
        ViroSafeScreen(applyImePadding = true) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = ViroSpacing.lg),
            ) {
                Spacer(Modifier.height(ViroSpacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    ViroCallBrand()
                }
                Spacer(Modifier.height(ViroSpacing.xl))
                Text(
                    if (returningUser) "Welcome back" else "Enter your phone number",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (returningUser) {
                        "Verify your number to continue using Viro Call."
                    } else {
                        "We'll send you a verification code to get started."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(ViroSpacing.lg))
                ViroPhoneInputCard(
                    countryCode = "+260",
                    phoneDigits = displayPhone,
                    onCountryClick = {},
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ⓘ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Use the phone number on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                errorMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    ViroErrorMessage(it)
                }
                Spacer(Modifier.height(ViroSpacing.md))
                ViroContinueButton(
                    text = if (loading) "Sending…" else "Continue",
                    onClick = onContinue,
                    enabled = !loading && phoneDigits.isNotBlank(),
                )
                Spacer(Modifier.weight(1f))
                ViroNumericKeypad(
                    onDigit = { d ->
                        val next = phoneDigits + d
                        if (next.length <= 15) onPhoneDigitsChange(next)
                    },
                    onBackspace = {
                        if (phoneDigits.isNotEmpty()) onPhoneDigitsChange(phoneDigits.dropLast(1))
                    },
                    modifier = Modifier.padding(bottom = ViroSpacing.md),
                )
            }
        }
    }
}
