package com.viroreach.app.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.ViroBackButton
import com.viroreach.core.designsystem.components.ViroSafeScreen
import com.viroreach.core.designsystem.components.ViroScreenBackground
import com.viroreach.feature.contacts.CountryCatalog
import com.viroreach.feature.contacts.CountryOption

@Composable
fun CountryPickerScreen(
    selected: CountryOption,
    onSelect: (CountryOption) -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val matches = remember(query) { CountryCatalog.search(query) }

    ViroScreenBackground {
        ViroSafeScreen(applyImePadding = true) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(ViroSpacing.md),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ViroBackButton(onClick = onBack)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Country / region",
                        style = MaterialTheme.typography.titleLarge,
                        color = ViroColors.textPrimary,
                    )
                }
                Spacer(Modifier.height(ViroSpacing.md))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search country or code") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ViroColors.textPrimary,
                        unfocusedTextColor = ViroColors.textPrimary,
                        focusedBorderColor = ViroColors.accent,
                        unfocusedBorderColor = ViroColors.textMuted,
                    ),
                )
                Spacer(Modifier.height(ViroSpacing.sm))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(matches, key = { it.iso2 + it.dialCode }) { country ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(country) }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(country.flagEmoji, style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    country.name,
                                    color = if (country.iso2 == selected.iso2) {
                                        ViroColors.accent
                                    } else {
                                        ViroColors.textPrimary
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    country.iso2,
                                    color = ViroColors.textSecondary,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            Text(country.dialCode, color = ViroColors.textSecondary)
                        }
                    }
                }
            }
        }
    }
}
