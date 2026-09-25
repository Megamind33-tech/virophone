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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.components.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
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

    val removePhoto: () -> Unit = {
        scope.launch {
            isSaving = true
            session.profileRepository.removePhoto()
                .onSuccess { statusMessage = "Photo removed" }
                .onFailure { statusMessage = "Couldn't remove photo" }
            isSaving = false
        }
    }
    val save: () -> Unit = {
        scope.launch {
            isSaving = true
            session.profileRepository.saveProfile(displayName)
                .onSuccess { onBack() }
                .onFailure { statusMessage = "Couldn't save your profile. Try again." }
            isSaving = false
        }
    }

    // Scrolls, and applies IME padding: this screen carries the photo, the
    // name field, Save AND the linked numbers and emails, which does not fit a
    // short screen with the keyboard open.
    ViroSubScreen(title = "Edit profile", onBack = onBack) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                ViroAvatar(
                    imageUrl = profile.effectivePhotoUrl,
                    size = ViroAvatarSize.Hero,
                    kind = ViroAvatarKind.USER_PROFILE_IMAGE,
                    modifier = Modifier.clickable(enabled = !isSaving, onClickLabel = "Change photo") { showPhotoOptions = true },
                )
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(ViroColors.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.PhotoCamera, contentDescription = null, tint = ViroColors.onAccent, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showPhotoOptions = true }, enabled = !isSaving) {
                    Text(if (isSaving) "Uploading…" else "Change photo", color = ViroColors.accent)
                }
                if (!profile.effectivePhotoUrl.isNullOrBlank()) {
                    TextButton(onClick = removePhoto, enabled = !isSaving) {
                        Text("Remove", color = ViroColors.textSecondary)
                    }
                }
            }
            statusMessage?.let {
                Text(it, color = ViroColors.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ViroTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = "Display name",
                leadingIcon = Icons.Outlined.Person,
            )
            ViroFootnote("This is the name people see on Viro.")
        }
        ViroActionButton(text = "Save", onClick = save, busy = isSaving)

        ProfileIdentitiesSection(
            session = session,
            onAddPhone = onAddPhone,
            onAddEmail = onAddEmail,
        )
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
        null -> "Couldn't upload photo. Try again."
        else -> "Couldn't upload photo (error $code)"
    }
}
