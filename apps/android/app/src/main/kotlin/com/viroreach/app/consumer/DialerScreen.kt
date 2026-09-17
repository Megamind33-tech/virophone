package com.viroreach.app.consumer

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.viroreach.app.consumer.call.CallPresentation
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.*
import com.viroreach.feature.contacts.PhoneNumberFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DialerScreen(
    session: SessionManager,
    onBack: (() -> Unit)? = null,
    onBeginCall: ((CallPresentation, suspend () -> Unit) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialInput by rememberSaveable { mutableStateOf("") }
    var pendingCall by remember { mutableStateOf<(() -> Unit)?>(null) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pendingCall?.invoke() else pendingCall = null
    }

    val cachedContacts = session.contactsCoordinator.uiState.contacts
    var deviceContacts by remember { mutableStateOf<List<ContactListItem>?>(null) }

    val region = remember { DeviceRegion.current(context) }

    LaunchedEffect(Unit) {
        deviceContacts = withContext(Dispatchers.IO) {
            runCatching { DeviceContactsReader.read(context, region) }.getOrDefault(emptyList())
        }
    }

    val dialerContacts = remember(cachedContacts, deviceContacts) {
        val merged = LinkedHashMap<String, ContactListItem>()
        cachedContacts.forEach { contact ->
            contact.phoneE164?.let { merged[it] = contact }
        }
        deviceContacts?.forEach { contact ->
            contact.phoneE164?.let { phone ->
                merged.putIfAbsent(phone, contact)
            }
        }
        merged.values.toList()
    }

    val dialDigits = remember(dialInput) { dialInput.filter { it.isDigit() } }
    val displayNumber = remember(dialDigits) {
        PhoneNumberFormatter.formatForDisplay(dialDigits, region)
    }
    val matchedContact = remember(dialDigits, dialerContacts) {
        DeviceContactsReader.findByDigitsIn(dialerContacts, dialDigits)
    }

    fun startCall() {
        val e164 = PhoneNumberFormatter.canonicalE164(dialDigits, region) ?: return
        val name = matchedContact?.displayName ?: displayNumber
        session.updateLastTargetInput(e164)
        val presentation = CallPresentation(
            displayName = name,
            avatarUrl = matchedContact?.resolveAvatarUrl(),
            phoneE164 = e164,
        )
        val work: suspend () -> Unit = {
            val cached = session.contactsRepository.findByPhone(e164)
            session.placeOutgoingCall(
                phoneE164 = e164,
                displayName = cached?.effectiveDisplayName ?: name,
                userId = cached?.userId,
            )
        }
        if (onBeginCall != null) {
            onBeginCall(presentation, work)
        } else {
            scope.launch { work() }
        }
    }

    ViroScreenBackground {
        ViroSafeScreen(applyImePadding = true, applyNavigationBarsPadding = true) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = ViroSpacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (onBack != null) ViroBackButton(onClick = onBack) else Spacer(Modifier.width(48.dp))
                    ViroCallBrand(compact = true)
                }
                Spacer(Modifier.height(ViroSpacing.md))
                Text(
                    text = if (dialInput.isEmpty()) "Enter number" else displayNumber,
                    style = MaterialTheme.typography.headlineMedium,
                    color = ViroColors.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (matchedContact != null) {
                    val contact = matchedContact!!
                    Spacer(Modifier.height(ViroSpacing.md))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ViroSpacing.sm),
                    ) {
                        ViroAvatar(imageUrl = contact.resolveAvatarUrl(), size = ViroAvatarSize.Medium)
                        Column {
                            Text(
                                contact.displayName,
                                color = ViroColors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            contact.phoneE164?.let {
                                Text(
                                    PhoneNumberFormatter.formatE164International(it),
                                    color = ViroColors.textSecondary,
                                    maxLines = 1,
                                )
                            }
                            if (contact.isReachable) {
                                Text("Viro", color = ViroColors.success, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                ViroNumericKeypad(
                    onDigit = { key ->
                        val next = dialInput + key
                        if (next.length <= 20) dialInput = next
                    },
                    onBackspace = { if (dialInput.isNotEmpty()) dialInput = dialInput.dropLast(1) },
                )
                Spacer(Modifier.height(ViroSpacing.md))
                FilledIconButton(
                    onClick = {
                        if (PhoneNumberFormatter.canonicalE164(dialDigits, region) == null) return@FilledIconButton
                        val action = { startCall() }
                        when (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)) {
                            PackageManager.PERMISSION_GRANTED -> action()
                            else -> {
                                pendingCall = action
                                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = ViroColors.success),
                ) {
                    Icon(
                        Icons.Default.Call,
                        contentDescription = "Call",
                        tint = ViroColors.textPrimary,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.height(ViroSpacing.md))
            }
        }
    }
}
