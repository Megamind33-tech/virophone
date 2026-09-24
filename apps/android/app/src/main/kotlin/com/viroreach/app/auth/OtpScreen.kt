package com.viroreach.app.auth

import com.viroreach.core.designsystem.ViroColors
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.*

@Composable
fun OtpScreen(
    phoneE164: String,
    otp: String,
    loading: Boolean,
    errorMessage: String?,
    hardwareTestMode: Boolean,
    onOtpChange: (String) -> Unit,
    onVerify: () -> Unit,
    onResend: () -> Unit,
    onChangeNumber: () -> Unit,
) {
    ViroScreenBackground {
        ViroSafeScreen(applyImePadding = true) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(ViroSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(ViroSpacing.lg),
        ) {
            Spacer(Modifier.height(ViroSpacing.xl))
            ViroCallBrand(modifier = Modifier.fillMaxWidth())
            Text("Enter verification code", style = MaterialTheme.typography.headlineMedium, color = ViroColors.textPrimary)
            Text(
                "Sent to $phoneE164",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hardwareTestMode) {
                ViroPhoneField(
                    value = otp,
                    onValueChange = onOtpChange,
                    label = "Verification code",
                    enabled = !loading,
                )
                Text(
                    "Hardware test builds accept the configured verification code from your server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OtpDigitRow(otp = otp)
            }
            errorMessage?.let { ViroErrorMessage(it) }
            ViroContinueButton(
                text = if (loading) "Verifying…" else "Verify",
                onClick = onVerify,
                enabled = !loading && otp.isNotBlank(),
            )
            ViroSecondaryButton(text = "Resend code", onClick = onResend, enabled = !loading)
            ViroSecondaryButton(text = "Change number", onClick = onChangeNumber, enabled = !loading)
            if (!hardwareTestMode) {
                Spacer(Modifier.weight(1f))
                ViroNumericKeypad(
                    onDigit = { if (otp.length < 6) onOtpChange(otp + it) },
                    onBackspace = { if (otp.isNotEmpty()) onOtpChange(otp.dropLast(1)) },
                )
            }
        }
        }
    }
}

@Composable
internal fun OtpDigitRow(otp: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ViroSpacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        repeat(6) { index ->
            val digit = otp.getOrNull(index)?.toString() ?: "·"
            Text(
                text = digit,
                style = MaterialTheme.typography.headlineMedium,
                color = ViroColors.textPrimary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
