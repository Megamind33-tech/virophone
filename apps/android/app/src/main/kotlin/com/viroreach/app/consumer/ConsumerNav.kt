package com.viroreach.app.consumer



import android.Manifest

import android.content.pm.PackageManager

import androidx.activity.ComponentActivity

import androidx.activity.compose.BackHandler

import androidx.activity.compose.rememberLauncherForActivityResult

import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.layout.Box

import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.layout.size

import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.filled.Dialpad

import androidx.compose.material3.FabPosition

import androidx.compose.material3.FloatingActionButton

import androidx.compose.material3.FloatingActionButtonDefaults

import androidx.compose.material3.Icon

import androidx.compose.material3.Scaffold

import androidx.compose.runtime.*

import androidx.compose.ui.Modifier

import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.unit.dp

import androidx.core.content.ContextCompat

import com.viroreach.app.consumer.call.CallPresentation

import com.viroreach.app.consumer.call.ConsumerCallScreen

import com.viroreach.app.consumer.call.GroupCallScreen

import com.viroreach.app.consumer.data.CallLogType

import com.viroreach.app.consumer.messages.ChatScreen

import com.viroreach.app.developer.DeveloperHarnessScreen

import com.viroreach.app.personalization.AppearanceScreen

import com.viroreach.app.personalization.EditProfileScreen

import com.viroreach.app.session.SessionManager

import com.viroreach.core.designsystem.ViroColors

import com.viroreach.core.designsystem.components.ViroConsumerBottomBar

import com.viroreach.core.designsystem.components.ViroConsumerTab

import com.viroreach.feature.contacts.PhoneNumberFormatter

import kotlinx.coroutines.launch

import java.util.UUID



enum class ConsumerOverlay {

    None,

    Developer,

    Dialer,

    Appearance,

    EditProfile,

    ContactDetail,

    Call,

    GroupCall,

    Chat,

}



data class ChatRoute(

    val conversationId: String,

    val peerName: String,

    val peerUserId: String?,

    val peerPhoneE164: String? = null,

    val peerAvatarUrl: String? = null,

)



@Composable

fun ConsumerNav(

    session: SessionManager,

    showDeveloperEntry: Boolean,

    onLogout: () -> Unit,

) {

    val context = LocalContext.current

    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(ViroConsumerTab.Home) }

    var overlay by remember { mutableStateOf(ConsumerOverlay.None) }

    var chatRoute by remember { mutableStateOf<ChatRoute?>(null) }

    var callPresentation by remember { mutableStateOf<CallPresentation?>(null) }

    var selectedContact by remember { mutableStateOf<ContactListItem?>(null) }

    var userInitiatedCall by remember { mutableStateOf(false) }



    val callState by session.callManager.state.collectAsState()

    val isCaller by session.callManager.isCallerRole.collectAsState()

    val incomingCall by session.callManager.incomingCall.collectAsState()

    val conferenceActive by session.conferenceManager.state.collectAsState()

    var pendingCallAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->

        if (ok) pendingCallAction?.invoke() else pendingCallAction = null

    }



    LaunchedEffect(incomingCall?.callId) {
        val info = incomingCall ?: return@LaunchedEffect
        if (userInitiatedCall) return@LaunchedEffect
        callPresentation = runCatching {
            resolveIncomingCallerPresentation(session, info)
        }.getOrElse {
            CallPresentation(
                displayName = info.callerDisplayName?.takeIf { it.isNotBlank() } ?: "Incoming call",
                phoneE164 = info.callerPhoneE164,
            )
        }
    }



    LaunchedEffect(callState, incomingCall, isCaller, callPresentation) {
        if (callState == com.viroreach.core.model.CallStateMachineState.RINGING &&
            !isCaller &&
            incomingCall != null &&
            !session.callHistoryStore.hasActiveCall()
        ) {
            val pres = callPresentation
            session.callHistoryStore.onCallStarted(
                name = pres?.displayName ?: "Incoming call",
                phoneE164 = pres?.phoneE164 ?: incomingCall?.callerPhoneE164,
                outgoing = false,
            )
        }
    }

    LaunchedEffect(callState, isCaller) {
        if (CallNavigationPolicy.isIncomingRinging(callState, isCaller) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(callState, conferenceActive.isActive, isCaller, userInitiatedCall, incomingCall) {

        when {

            conferenceActive.isActive -> overlay = ConsumerOverlay.GroupCall

            userInitiatedCall ||

                CallNavigationPolicy.isCallOverlayState(callState) ||

                CallNavigationPolicy.isIncomingRinging(callState, isCaller) -> {

                overlay = ConsumerOverlay.Call

            }

            CallNavigationPolicy.shouldDismissOverlay(callState) -> {

                if (overlay == ConsumerOverlay.Call) {

                    overlay = ConsumerOverlay.None

                    userInitiatedCall = false

                    callPresentation = null

                }

            }

        }

    }



    LaunchedEffect(Unit) {

        session.callManager.chatEvents.collect { event ->

            // Land inbound messages in the sender's conversation (keyed by peer
            // userId) so they appear regardless of the server-side conversation id.
            val peerUserId = event.fromUserId
            val convId = if (peerUserId != null) {
                session.messagesStore.openOrCreateConversation(
                    peerName = peerUserId.take(8),
                    peerUserId = peerUserId,
                    phoneE164 = null,
                )
            } else {
                event.conversationId
            }
            session.messagesStore.receiveMessage(convId, event.body)

        }

    }



    fun withMic(action: () -> Unit) {

        when (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)) {

            PackageManager.PERMISSION_GRANTED -> action()

            else -> {

                pendingCallAction = action

                micLauncher.launch(Manifest.permission.RECORD_AUDIO)

            }

        }

    }



    fun beginCall(presentation: CallPresentation) {

        callPresentation = presentation

        userInitiatedCall = true

        overlay = ConsumerOverlay.Call

        session.callHistoryStore.onCallStarted(

            name = presentation.displayName,

            phoneE164 = presentation.phoneE164,

            outgoing = true,

        )

    }



    fun callContact(contact: ContactListItem) {

        val phone = contact.phoneE164 ?: return

        beginCall(

            CallPresentation(

                displayName = contact.effectiveDisplayName,

                avatarUrl = contact.resolveAvatarUrl(),

                phoneE164 = phone,

            ),

        )

        withMic {

            scope.launch {

                runCatching {
                    session.placeOutgoingCall(
                        phoneE164 = phone,
                        displayName = contact.effectiveDisplayName,
                        userId = contact.userId,
                    )
                }

            }

        }

    }



    fun dismissOverlay() {
        when (overlay) {
            ConsumerOverlay.ContactDetail -> selectedContact = null
            ConsumerOverlay.Chat -> chatRoute = null
            else -> Unit
        }
        overlay = ConsumerOverlay.None
    }

    fun handleBackNavigation() {
        when (overlay) {
            ConsumerOverlay.Call -> {
                session.incomingCallRinger.stop()
                scope.launch {
                    runCatching {
                        when {
                            CallNavigationPolicy.isIncomingRinging(callState, isCaller) ->
                                session.callManager.rejectCall()
                            CallNavigationPolicy.isCallOverlayState(callState) ->
                                session.callManager.hangUp()
                        }
                    }
                }
                overlay = ConsumerOverlay.None
                userInitiatedCall = false
                callPresentation = null
            }
            ConsumerOverlay.GroupCall -> {
                session.conferenceManager.endConference()
                overlay = ConsumerOverlay.None
            }
            ConsumerOverlay.None -> {
                if (tab != ViroConsumerTab.Home) {
                    tab = ViroConsumerTab.Home
                } else {
                    (context as? ComponentActivity)?.moveTaskToBack(true)
                }
            }
            else -> dismissOverlay()
        }
    }

    BackHandler(onBack = ::handleBackNavigation)

    fun openChat(contact: ContactListItem) {

        val id = session.messagesStore.openOrCreateConversation(

            contact.displayName,

            contact.userId,

            contact.phoneE164,

        )

        chatRoute = ChatRoute(

            conversationId = id,

            peerName = contact.effectiveDisplayName,

            peerUserId = contact.userId,

            peerPhoneE164 = contact.phoneE164,

            peerAvatarUrl = contact.resolveAvatarUrl(),

        )

        overlay = ConsumerOverlay.Chat

    }



    when (overlay) {

        ConsumerOverlay.Developer -> {

            DeveloperHarnessScreen(onBack = { overlay = ConsumerOverlay.None })

            return

        }

        ConsumerOverlay.Dialer -> {

            DialerScreen(

                session = session,

                onBack = { overlay = ConsumerOverlay.None },

                onBeginCall = { presentation, action ->

                    beginCall(presentation)

                    withMic { scope.launch { action() } }

                },

            )

            return

        }

        ConsumerOverlay.Appearance -> {

            AppearanceScreen(session = session, onBack = { overlay = ConsumerOverlay.None })

            return

        }

        ConsumerOverlay.EditProfile -> {

            EditProfileScreen(session = session, onBack = { overlay = ConsumerOverlay.None })

            return

        }

        ConsumerOverlay.ContactDetail -> {

            val contact = selectedContact
            if (contact == null) {
                LaunchedEffect(Unit) { overlay = ConsumerOverlay.None }
                return
            }

            ContactDetailScreen(

                contact = contact,

                session = session,

                onBack = { overlay = ConsumerOverlay.None },

                onCall = {

                    overlay = ConsumerOverlay.None

                    callContact(contact)

                },

                onMessage = {

                    overlay = ConsumerOverlay.None

                    openChat(contact)

                },

                onOpenRelated = { related -> selectedContact = related },

                onDelete = {

                    overlay = ConsumerOverlay.None

                    selectedContact = null

                },

            )

            return

        }

        ConsumerOverlay.Call -> {

            val presentation = callPresentation ?: CallPresentation("Connecting…")

            ConsumerCallScreen(

                session = session,

                presentation = presentation,

                onDismiss = {

                    overlay = ConsumerOverlay.None

                    userInitiatedCall = false

                    callPresentation = null

                },

                onOpenChat = {

                    val id = session.messagesStore.openOrCreateConversation(

                        presentation.displayName,

                        null,

                        presentation.phoneE164,

                    )

                    chatRoute = ChatRoute(

                        conversationId = id,

                        peerName = presentation.displayName,

                        peerUserId = null,

                        peerPhoneE164 = presentation.phoneE164,

                        peerAvatarUrl = presentation.avatarUrl,

                    )

                    overlay = ConsumerOverlay.Chat

                },

                onAcceptIncoming = {
                    session.incomingCallRinger.stop()
                    withMic {
                        scope.launch {
                            val id = session.callManager.incomingCall.value?.callId
                                ?: session.callManager.pendingCallId()
                                ?: return@launch
                            session.callManager.acceptCall(id)
                        }
                    }
                },

            )

            return

        }

        ConsumerOverlay.GroupCall -> {

            GroupCallScreen(

                session = session,

                onEndCall = { overlay = ConsumerOverlay.None },

                onOpenChat = {

                    val id = session.messagesStore.openOrCreateConversation("Group", null, null)

                    chatRoute = ChatRoute(id, "Group", null)

                    overlay = ConsumerOverlay.Chat

                },

            )

            return

        }

        ConsumerOverlay.Chat -> {

            val route = chatRoute
            if (route == null) {
                LaunchedEffect(Unit) { overlay = ConsumerOverlay.None }
                return
            }

            ChatScreen(

                session = session,

                conversationId = route.conversationId,

                peerName = route.peerName,

                peerUserId = route.peerUserId,

                peerPhoneE164 = route.peerPhoneE164,

                peerAvatarUrl = route.peerAvatarUrl,

                onBack = { overlay = ConsumerOverlay.None },

            )

            return

        }

        ConsumerOverlay.None -> Unit

    }



    Scaffold(

        containerColor = androidx.compose.ui.graphics.Color.Transparent,

        bottomBar = {

            ViroConsumerBottomBar(selected = tab, onSelect = { tab = it })

        },

        floatingActionButtonPosition = FabPosition.End,

        floatingActionButton = {

            if (tab == ViroConsumerTab.Home && overlay == ConsumerOverlay.None) {

                FloatingActionButton(

                    onClick = { overlay = ConsumerOverlay.Dialer },

                    modifier = Modifier.size(56.dp),

                    containerColor = ViroColors.ElectricBlue,

                    contentColor = androidx.compose.ui.graphics.Color.White,

                    elevation = FloatingActionButtonDefaults.elevation(

                        defaultElevation = 6.dp,

                        pressedElevation = 10.dp,

                    ),

                ) {

                    Icon(

                        Icons.Default.Dialpad,

                        contentDescription = "Open dialer",

                        modifier = Modifier.size(26.dp),

                    )

                }

            }

        },

    ) { padding ->

        Box(Modifier.padding(padding).fillMaxSize()) {

            when (tab) {

                ViroConsumerTab.Home -> HomeScreen(

                    session = session,

                    onOpenDialer = { overlay = ConsumerOverlay.Dialer },

                    onCallContact = ::callContact,

                    onContactDetail = { contact ->

                        selectedContact = contact

                        overlay = ConsumerOverlay.ContactDetail

                    },

                    onViewCallLog = { log ->

                        scope.launch {

                            runCatching {
                                session.contactsRepository.contactForCallLog(log.name, log.phoneE164)
                            }.getOrNull()?.let { contact ->
                                selectedContact = contact
                                overlay = ConsumerOverlay.ContactDetail
                            }

                        }

                    },

                    onMessageCallLog = { log ->

                        val phone = log.phoneE164 ?: return@HomeScreen

                        val id = session.messagesStore.openOrCreateConversation(log.name, null, phone)

                        chatRoute = ChatRoute(

                            conversationId = id,

                            peerName = log.name,

                            peerUserId = null,

                            peerPhoneE164 = phone,

                        )

                        overlay = ConsumerOverlay.Chat

                    },

                )

                ViroConsumerTab.Contacts -> ContactsScreen(

                    session = session,

                    onCallContact = ::callContact,

                    onMessageContact = ::openChat,

                    onContactDetail = { contact ->

                        selectedContact = contact

                        overlay = ConsumerOverlay.ContactDetail

                    },

                    onStartGroupCall = { contacts ->

                        session.conferenceManager.startConference(contacts.map { it.displayName })

                        session.callHistoryStore.add(

                            com.viroreach.app.consumer.data.CallLogEntry(

                                id = UUID.randomUUID().toString(),

                                name = contacts.joinToString(", ") { it.displayName },

                                phoneE164 = null,

                                type = CallLogType.GROUP,

                                timestampMs = System.currentTimeMillis(),

                                durationSeconds = 0,

                            ),

                        )

                        overlay = ConsumerOverlay.GroupCall

                    },

                )

                ViroConsumerTab.Calls -> CallsScreen(

                    session = session,

                    onCallBack = { phone, name ->

                        phone ?: return@CallsScreen

                        beginCall(

                            CallPresentation(

                                displayName = name ?: PhoneNumberFormatter.formatE164International(phone),

                                phoneE164 = phone,

                            ),

                        )

                        withMic {

                            scope.launch {

                                val cached = session.contactsRepository.findByPhone(phone)

                                session.placeOutgoingCall(

                                    phoneE164 = phone,

                                    displayName = name ?: cached?.effectiveDisplayName

                                        ?: PhoneNumberFormatter.formatE164International(phone),

                                    userId = cached?.userId,

                                )

                            }

                        }

                    },

                    onViewContact = { log ->

                        scope.launch {

                            runCatching {
                                session.contactsRepository.contactForCallLog(log.name, log.phoneE164)
                            }.getOrNull()?.let { contact ->
                                selectedContact = contact
                                overlay = ConsumerOverlay.ContactDetail
                            }

                        }

                    },

                    onMessage = { phone, name ->

                        phone ?: return@CallsScreen

                        val id = session.messagesStore.openOrCreateConversation(

                            name ?: PhoneNumberFormatter.formatE164International(phone),

                            null,

                            phone,

                        )

                        chatRoute = ChatRoute(

                            conversationId = id,

                            peerName = name ?: phone,

                            peerUserId = null,

                            peerPhoneE164 = phone,

                        )

                        overlay = ConsumerOverlay.Chat

                    },

                )

                ViroConsumerTab.You -> YouScreen(

                    session = session,

                    showDeveloperEntry = showDeveloperEntry,

                    onDeveloper = { overlay = ConsumerOverlay.Developer },

                    onAppearance = { overlay = ConsumerOverlay.Appearance },

                    onEditProfile = { overlay = ConsumerOverlay.EditProfile },

                    onLogout = onLogout,

                )

            }

        }

    }

}


