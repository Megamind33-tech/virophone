package com.viroreach.app.engineering

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.viroreach.core.network.*
import com.viroreach.core.security.DeviceIdentityManager
import com.viroreach.feature.calling.OfflineTrustStore
import com.viroreach.feature.contacts.PhoneNormalizer
import kotlinx.coroutines.launch

@Composable
fun AuthEngineeringScreen(session: EngineeringSession) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val tokenStore = session.tokenStore
    val api = session.api
    val identity = remember { DeviceIdentityManager(context) }

    var phone by remember {
        mutableStateOf(session.testIdentityStore.getPhoneE164() ?: "")
    }
    var otpCode by remember { mutableStateOf("") }
    var challengeId by remember { mutableStateOf<String?>(null) }
    var fingerprint by remember { mutableStateOf("NOT AVAILABLE") }
    var busy by remember { mutableStateOf(false) }

    var apiStatus by remember { mutableStateOf(StageStatus.NOT_STARTED) }
    var apiDetail by remember { mutableStateOf("NOT CHECKED") }
    var otpRequestStatus by remember { mutableStateOf(StageStatus.NOT_STARTED) }
    var otpRequestDetail by remember { mutableStateOf("NOT STARTED") }
    var otpVerifyStatus by remember { mutableStateOf(StageStatus.NOT_STARTED) }
    var otpVerifyDetail by remember { mutableStateOf("NOT STARTED") }
    var deviceRegisterStatus by remember { mutableStateOf(StageStatus.NOT_STARTED) }
    var deviceRegisterDetail by remember { mutableStateOf("NOT STARTED") }
    var sessionStatus by remember { mutableStateOf(StageStatus.NOT_STARTED) }
    var sessionDetail by remember { mutableStateOf("NOT AUTHENTICATED") }
    var offlineTrustStatus by remember { mutableStateOf(StageStatus.NOT_STARTED) }
    var offlineTrustDetail by remember { mutableStateOf("NOT SYNCED") }

    LaunchedEffect(Unit) {
        fingerprint = identity.getPublicKeyBase64().take(24) + "..."
        apiStatus = StageStatus.RUNNING
        val health = ApiDiagnostics.checkHealth()
        apiStatus = if (health.reachable) StageStatus.PASS else StageStatus.FAIL
        apiDetail = if (health.reachable) {
            "CONNECTED (HTTP ${health.httpStatus})"
        } else {
            "FAILED: ${health.error ?: "unreachable"}"
        }
        if (tokenStore.getAccessToken() != null) {
            sessionStatus = StageStatus.PASS
            sessionDetail = buildAuthenticatedDetail(tokenStore)
            val trust = OfflineTrustStore(context)
            offlineTrustStatus = StageStatus.PASS
            offlineTrustDetail = "SYNCED (${trust.getMaterial().size} entries)"
        }
    }

    Column(
        Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Phase 1A — Auth / Device", style = MaterialTheme.typography.titleMedium)

        if (session.isAuthenticated) {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("AUTHENTICATED AS", style = MaterialTheme.typography.labelMedium)
                    Text("Phone: ${tokenStore.getAuthenticatedPhoneE164() ?: phone}")
                    Text("User ID: ${tokenStore.getUserId()?.take(8)}…")
                    Text("Device ID: ${tokenStore.getDeviceId()?.take(8)}…")
                    Text("Keystore: $fingerprint")
                }
            }
        } else {
            Text("Keystore fingerprint: $fingerprint")
        }

        StageLine("API", apiStatus, apiDetail)
        StageLine("OTP REQUEST", otpRequestStatus, otpRequestDetail)
        StageLine("OTP VERIFY", otpVerifyStatus, otpVerifyDetail)
        StageLine("DEVICE REGISTER", deviceRegisterStatus, deviceRegisterDetail)
        StageLine("SESSION", sessionStatus, sessionDetail)
        StageLine("OFFLINE TRUST", offlineTrustStatus, offlineTrustDetail)

        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone E.164") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !session.isAuthenticated && !busy,
        )
        OutlinedTextField(
            value = otpCode,
            onValueChange = { otpCode = it },
            label = { Text("Hardware test verification code") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )

        Text(
            "HARDWARE TEST IDENTITY — enter verification code manually after REQUEST OTP. " +
                "Allowlisted: +260961582985 (Phone A), +260977426940 (Phone B).",
            style = MaterialTheme.typography.bodySmall,
        )

        Button(
            onClick = {
                scope.launch {
                    busy = true
                    otpRequestStatus = StageStatus.RUNNING
                    otpRequestDetail = "RUNNING"
                    try {
                        val normalized = PhoneNormalizer.normalizeToE164(phone)
                            ?: throw IllegalArgumentException("Invalid E.164")
                        phone = normalized
                        val otp = api.requestOtp(OtpRequestBody(normalized))
                        challengeId = otp.challengeId
                        otpRequestStatus = StageStatus.PASS
                        otpRequestDetail = "PASS challenge=${otp.challengeId.take(8)}… expires=${otp.expiresAt.take(19)}"
                    } catch (e: Exception) {
                        val fail = ApiDiagnostics.parseFailure("POST", "/api/v1/auth/otp/request", e)
                        ApiDiagnostics.logFailure(fail)
                        otpRequestStatus = StageStatus.FAIL
                        otpRequestDetail = fail.summary()
                    }
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("REQUEST OTP") }

        Button(
            onClick = {
                scope.launch {
                    busy = true
                    val cid = challengeId
                    if (cid.isNullOrBlank()) {
                        otpVerifyStatus = StageStatus.FAIL
                        otpVerifyDetail = "FAIL: request OTP first"
                        busy = false
                        return@launch
                    }
                    if (otpCode.length !in 6..32) {
                        otpVerifyStatus = StageStatus.FAIL
                        otpVerifyDetail = "FAIL: enter configured test verification code (6–32 chars)"
                        busy = false
                        return@launch
                    }
                    otpVerifyStatus = StageStatus.RUNNING
                    otpVerifyDetail = "RUNNING"
                    deviceRegisterStatus = StageStatus.RUNNING
                    deviceRegisterDetail = "RUNNING"
                    try {
                        val normalized = PhoneNormalizer.normalizeToE164(phone)
                            ?: throw IllegalArgumentException("Invalid E.164")
                        phone = normalized
                        val pubkey = identity.getPublicKeyBase64()
                        val verify = api.verifyOtp(
                            OtpVerifyBody(cid, otpCode, pubkey, "ANDROID", "0.1.0-phase1a"),
                        )
                        otpVerifyStatus = StageStatus.PASS
                        otpVerifyDetail = "PASS"
                        deviceRegisterStatus = StageStatus.PASS
                        deviceRegisterDetail =
                            "PASS device=${verify.deviceId.take(8)}… keystore_pubkey=${pubkey.take(20)}…"
                        session.sessionTokenManager.saveSessionFromAuth(
                            verify.accessToken,
                            verify.refreshToken,
                            verify.userId,
                            verify.deviceId,
                            normalized,
                            verify.expiresIn,
                        )
                        session.onAuthenticationSuccess(normalized)
                        sessionStatus = StageStatus.PASS
                        sessionDetail = buildAuthenticatedDetail(tokenStore)
                    } catch (e: Exception) {
                        val fail = ApiDiagnostics.parseFailure("POST", "/api/v1/auth/otp/verify", e)
                        ApiDiagnostics.logFailure(fail)
                        otpVerifyStatus = StageStatus.FAIL
                        otpVerifyDetail = fail.summary()
                        deviceRegisterStatus = StageStatus.FAIL
                        deviceRegisterDetail = "NOT REACHED"
                        sessionStatus = StageStatus.FAIL
                        sessionDetail = "NOT AUTHENTICATED"
                    }
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("VERIFY OTP / LOGIN") }

        Button(
            onClick = {
                scope.launch {
                    busy = true
                    if (tokenStore.getAccessToken() == null) {
                        offlineTrustStatus = StageStatus.FAIL
                        offlineTrustDetail = "FAIL: authenticate first"
                        busy = false
                        return@launch
                    }
                    offlineTrustStatus = StageStatus.RUNNING
                    offlineTrustDetail = "RUNNING"
                    try {
                        val trust = OfflineTrustStore(context)
                        trust.saveMaterial(api.getOfflineTrustMaterial().material)
                        offlineTrustStatus = StageStatus.PASS
                        offlineTrustDetail = "SYNCED (${trust.getMaterial().size} entries)"
                    } catch (e: Exception) {
                        val fail = ApiDiagnostics.parseFailure("GET", "/api/v1/offline-trust/material", e)
                        ApiDiagnostics.logFailure(fail)
                        offlineTrustStatus = StageStatus.FAIL
                        offlineTrustDetail = fail.summary()
                    }
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("SYNC OFFLINE TRUST") }

        WssControlButton(session)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    session.logout()
                    sessionStatus = StageStatus.NOT_STARTED
                    sessionDetail = "NOT AUTHENTICATED"
                    offlineTrustStatus = StageStatus.NOT_STARTED
                    offlineTrustDetail = "NOT SYNCED"
                },
                modifier = Modifier.weight(1f),
            ) { Text("LOG OUT") }
            Button(
                onClick = {
                    session.resetTestSession()
                    phone = ""
                    sessionStatus = StageStatus.NOT_STARTED
                    sessionDetail = "NOT AUTHENTICATED"
                    offlineTrustStatus = StageStatus.NOT_STARTED
                    offlineTrustDetail = "NOT SYNCED"
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("RESET TEST SESSION") }
        }
    }
}

private fun buildAuthenticatedDetail(tokenStore: TokenStore): String =
    "AUTHENTICATED user=${tokenStore.getUserId()?.take(8)}… device=${tokenStore.getDeviceId()?.take(8)}…"

@Composable
private fun StageLine(label: String, status: StageStatus, detail: String) {
    Text("$label: ${status.name} — $detail", style = MaterialTheme.typography.bodySmall)
}
