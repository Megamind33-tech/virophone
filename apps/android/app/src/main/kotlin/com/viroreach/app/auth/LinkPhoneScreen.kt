package com.viroreach.app.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.*
import com.viroreach.feature.contacts.CountryOption
import com.viroreach.feature.contacts.PhoneNumberFormatter

/**
 * Asks an email-signed-in account for a phone number.
 *
 * Without one, the account is invisible to phone-number contact discovery:
 * nobody who has the user's number can find or call them, which on a phone app
 * makes the account close to useless. Skippable, because forcing it at sign-up
 * blocks people who only wanted to look around.
 */
@Composable
fun LinkPhoneScreen(
    phoneDigits: String,
    country: CountryOption,
    loading: Boolean,
    errorMessage: String?,
    onPhoneDigitsChange: (String) -> Unit,
    onCountryChange: (CountryOption) -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    var pickingCountry by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(false)
    }
    if (pickingCountry) {
        CountryPickerScreen(
            selected = country,
            onSelect = { onCountryChange(it); pickingCountry = false },
            onBack = { pickingCountry = false },
        )
        return
    }
    val displayPhone = remember(phoneDigits, country.iso2) {
        PhoneNumberFormatter.formatForDisplay(phoneDigits, country.iso2)
    }

    ViroScreenBackground {
        ViroSafeScreen(applyImePadding = true, applyNavigationBarsPadding = true) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ViroSpacing.lg),
            ) {
                Spacer(Modifier.height(ViroSpacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) { ViroCallBrand() }

                Spacer(Modifier.height(ViroSpacing.xl))
                Text(
                    "Add your phone number",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "So people who have your number can find you on Viro. " +
                        "We'll text you a code to confirm it's yours.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(ViroSpacing.lg))
                ViroPhoneInputCard(
                    countryCode = country.dialCode,
                    phoneDigits = displayPhone,
                    flagEmoji = country.flagEmoji,
                    onCountryClick = { pickingCountry = true },
                )

                // Said before they commit, not after: if the number already has a
                // Viro account, this account folds into that one and they end up
                // signed in as it. That is a surprising outcome to discover.
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ⓘ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "If this number already has a Viro account, you'll be signed " +
                            "into that account and can use either your email or your " +
                            "number from then on.",
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
                    text = if (loading) "Sending code…" else "Send code",
                    onClick = onContinue,
                    enabled = !loading && phoneDigits.isNotBlank(),
                )
                Spacer(Modifier.height(ViroSpacing.sm))
                TextButton(onClick = onSkip, enabled = !loading) {
                    Text("Not now")
                }

                Spacer(Modifier.height(ViroSpacing.md))
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

/**
 * Code entry for the number being linked. Separate from [OtpScreen] because that
 * one signs a user in; this one attaches a number to a session that already
 * exists, and its outcomes ("linked" vs "adopted") differ.
 */
@Composable
fun LinkPhoneOtpScreen(
    phoneE164: String,
    otp: String,
    loading: Boolean,
    errorMessage: String?,
    onOtpChange: (String) -> Unit,
    onVerify: () -> Unit,
    onChangeNumber: () -> Unit,
    onSkip: () -> Unit,
) {
    ViroScreenBackground {
        ViroSafeScreen(applyImePadding = true, applyNavigationBarsPadding = true) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ViroSpacing.lg),
            ) {
                Spacer(Modifier.height(ViroSpacing.xl))
                Text(
                    "Confirm your number",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Enter the 6-digit code sent to $phoneE164.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(ViroSpacing.lg))
                OtpDigitRow(otp = otp)

                errorMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    ViroErrorMessage(it)
                }

                Spacer(Modifier.height(ViroSpacing.md))
                ViroContinueButton(
                    text = if (loading) "Confirming…" else "Confirm",
                    onClick = onVerify,
                    enabled = !loading && otp.length == 6,
                )
                Spacer(Modifier.height(ViroSpacing.sm))
                TextButton(onClick = onChangeNumber, enabled = !loading) {
                    Text("Use a different number")
                }
                TextButton(onClick = onSkip, enabled = !loading) {
                    Text("Not now")
                }

                Spacer(Modifier.height(ViroSpacing.md))
                ViroNumericKeypad(
                    onDigit = { d -> if (otp.length < 6) onOtpChange(otp + d) },
                    onBackspace = { if (otp.isNotEmpty()) onOtpChange(otp.dropLast(1)) },
                    modifier = Modifier.padding(bottom = ViroSpacing.md),
                )
            }
        }
    }
}
