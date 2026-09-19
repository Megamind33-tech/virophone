package com.viroreach.app.personalization

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.*
import kotlinx.coroutines.launch

@Composable
fun EditProfileScreen(
    session: SessionManager,
    onBack: () -> Unit,
    onAddPhone: () -> Unit = {},
    onAddEmail: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile by session.profileRepository.profile.collectAsState(initial = UserProfile())
    var displayName by remember(profile.displayName) { mutableStateOf(profile.displayName) }
    var showPhotoOptions by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    fun uploadPhoto(uri: Uri) {
        scope.launch {
            isSaving = true
            statusMessage = null
            session.profileRepository.uploadPhoto(uri)
                .onSuccess { statusMessage = "Photo uploaded" }
                .onFailure { statusMessage = uploadFailureMessage(it) }
            isSaving = false
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        uri?.let { uploadPhoto(it) }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        if (success) cameraUri?.let { uploadPhoto(it) }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val uri = ProfilePhotoCapture.createCameraUri(context)
            cameraUri = uri
            cameraLauncher.launch(uri)
        } else {
            statusMessage = "Camera permission is required to take a photo"
        }
    }

    LaunchedEffect(Unit) {
        session.profileRepository.refreshFromServer()
    }

    if (showPhotoOptions) {
        AlertDialog(
            onDismissRequest = { showPhotoOptions = false },
            title = { Text("Change photo") },
            text = {
                Column {
                    Text(
                        "Choose gallery or take a new photo.",
                        color = ViroColors.textSecondary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPhotoOptions = false
                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Gallery") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPhotoOptions = false
                    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                }) { Text("Take photo") }
            },
        )
    }

    ViroScreenBackground {
        // Scrolls, and applies IME padding: this screen now carries the photo,
        // the name field, Save AND the linked numbers and emails, which does not
        // fit a short screen with the keyboard open. Without this the identities
        // section was simply unreachable on smaller handsets.
        ViroSafeScreen(applyImePadding = true) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(ViroSpacing.md),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ViroBackButton(onClick = onBack)
                    Text(
                        "Edit profile",
                        style = MaterialTheme.typography.headlineMedium,
                        color = ViroColors.textPrimary,
                    )
                }
                Spacer(Modifier.height(ViroSpacing.xl))
                Box(Modifier.align(Alignment.CenterHorizontally)) {
                    ViroAvatar(
                        imageUrl = profile.effectivePhotoUrl,
                        size = ViroAvatarSize.Hero,
                        kind = ViroAvatarKind.USER_PROFILE_IMAGE,
                    )
                }
                Spacer(Modifier.height(ViroSpacing.md))
                Text(
                    if (isSaving) "Uploading…" else "Change photo",
                    color = ViroColors.accent,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable(enabled = !isSaving) { showPhotoOptions = true },
                )
                Text(
                    "Remove photo",
                    color = ViroColors.textSecondary,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable(enabled = !isSaving) {
                            scope.launch {
                                isSaving = true
                                session.profileRepository.removePhoto()
                                    .onSuccess { statusMessage = "Photo removed" }
                                    .onFailure { statusMessage = "Couldn't remove photo" }
                                isSaving = false
                            }
                        },
                )
                statusMessage?.let {
                    Text(
                        it,
                        color = ViroColors.textSecondary,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                Spacer(Modifier.height(ViroSpacing.lg))
                ViroPhoneField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = "Display name",
                )
                Spacer(Modifier.height(ViroSpacing.md))
                ViroContinueButton(
                    text = if (isSaving) "Saving…" else "Save",
                    enabled = !isSaving,
                    onClick = {
                        scope.launch {
                            isSaving = true
                            session.profileRepository.saveProfile(displayName)
                                .onSuccess { onBack() }
                                .onFailure { statusMessage = "Couldn't save profile" }
                            isSaving = false
                        }
                    },
                )
                Spacer(Modifier.height(ViroSpacing.xl))
                ProfileIdentitiesSection(
                    session = session,
                    onAddPhone = onAddPhone,
                    onAddEmail = onAddEmail,
                )
                Spacer(Modifier.height(ViroSpacing.xl))
            }
        }
    }
}

/**
 * Says why an upload failed. A single "Couldn't upload photo" for every cause
 * is what left the last round of testing unable to tell a network drop from a
 * server rejection.
 */
private fun uploadFailureMessage(error: Throwable): String {
    if (error is java.io.IOException) return "Couldn't upload photo: no connection. Try again."
    val code = Regex("""\((\d{3})\)""").find(error.message.orEmpty())?.groupValues?.get(1)
    return when (code) {
        "413" -> "That photo is too large to upload"
        "401", "403" -> "Your session has expired. Sign in again to change your photo."
        null -> "Couldn't upload photo: ${error.message ?: "unknown error"}"
        else -> "Couldn't upload photo (error $code)"
    }
}
