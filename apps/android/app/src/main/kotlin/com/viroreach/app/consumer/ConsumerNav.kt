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

import com.viroreach.app.consumer.messages.MessagesInboxScreen

import com.viroreach.app.consumer.data.syncFromServer

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

    AddPhone,

    AddEmail,

    Developer,

    Dialer,

    Appearance,

    EditProfile,

    ContactDetail,

    Call,

    GroupCall,

    Chat,

    BlockedContacts,

    Help,

    Devices,

    Connections,

    Subscription,

    Relationship,

    NewGroup,

    GroupInfo,

    Search,

}



/**
 * A chat to open. [conversationId] is the server's id when known; null opens
 * the chat with [peerUserId] (the real conversation is found or created on the
 * first message) — never a locally invented id.
 */
data class ChatRoute(

    val conversationId: String? = null,

    val peerName: String,

    val peerUserId: String?,

    val peerPhoneE164: String? = null,

    val peerAvatarUrl: String? = null,

    /** Open scrolled to this message (from search). */
    val focusMessageId: String? = null,

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

    var relationshipTarget by remember { mutableStateOf<Triple<String?, String?, String>?>(null) }

    var connectionsRequested by remember { mutableStateOf(false) }

    var groupInfoId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(connectionsRequested) {
        // One-shot: the Messages tab has switched to Connections by now.
        if (connectionsRequested) {
            kotlinx.coroutines.delay(800)
            connectionsRequested = false
        }
    }

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

    LaunchedEffect(callState, conferenceActive.isActive, conferenceActive.incomingInvite, isCaller, userInitiatedCall, incomingCall) {

        when {

            conferenceActive.isActive || conferenceActive.incomingInvite != null -> overlay = ConsumerOverlay.GroupCall

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



    // A notification tap: open the chat, or Connections.
    val pendingNav by com.viroreach.app.AppNavigation.pending.collectAsState()
    LaunchedEffect(pendingNav) {
        val target = com.viroreach.app.AppNavigation.consume() ?: return@LaunchedEffect
        when (target.screen) {
            com.viroreach.app.MainActivity.OPEN_CONNECTIONS -> {
                overlay = ConsumerOverlay.None
                tab = ViroConsumerTab.Messages
                connectionsRequested = true
            }
            com.viroreach.app.MainActivity.OPEN_CHAT -> {
                val peer = target.peerUserId ?: return@LaunchedEffect
                val contact = runCatching { session.contactsRepository.findByUserId(peer) }.getOrNull()
                chatRoute = ChatRoute(
                    conversationId = null,
                    peerName = contact?.effectiveDisplayName ?: PeerNameResolver.resolve(session = session, userId = peer, phoneE164 = null),
                    peerUserId = peer,
                    peerPhoneE164 = contact?.phoneE164,
                    peerAvatarUrl = contact?.resolveAvatarUrl(),
                )
                overlay = ConsumerOverlay.Chat
            }
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

        // A phone number isn't required when we already know the Viro user id
        // (e.g. calling back a server-history entry with no number on file).
        val phone = contact.phoneE164
        if (phone == null && contact.userId == null) return

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
            ConsumerOverlay.Relationship -> relationshipTarget = null
            ConsumerOverlay.GroupInfo -> {
                groupInfoId = null
                overlay = if (chatRoute != null) ConsumerOverlay.Chat else ConsumerOverlay.None
                return
            }
            else -> Unit
        }
        overlay = ConsumerOverlay.None
    }

    fun handleBackNavigation() {
        when (overlay) {
            // A phone call can't be accidentally ended by a stray back-press —
            // this used to reject/hang up unconditionally, so navigating back
            // (even by habit, or an accidental gesture) silently ended a real
            // call with no confirmation. Ending a call is now only ever what
            // the explicit End Call / Decline buttons on the call screen do.
            ConsumerOverlay.Call -> Unit
            ConsumerOverlay.GroupCall -> Unit
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

        chatRoute = ChatRoute(

            conversationId = null,

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

        ConsumerOverlay.AddPhone -> {

            AddPhoneOverlay(session = session, onDone = { overlay = ConsumerOverlay.None })

            return

        }

        ConsumerOverlay.AddEmail -> {

            AddEmailOverlay(session = session, onDone = { overlay = ConsumerOverlay.None })

            return

        }

        ConsumerOverlay.EditProfile -> {

            EditProfileScreen(
                session = session,
                onBack = { overlay = ConsumerOverlay.None },
                onAddPhone = { overlay = ConsumerOverlay.AddPhone },
                onAddEmail = { overlay = ConsumerOverlay.AddEmail },
            )

            return

        }

        ConsumerOverlay.BlockedContacts -> {

            BlockedContactsScreen(session = session, onBack = { overlay = ConsumerOverlay.None })

            return

        }

        ConsumerOverlay.Help -> {

            HelpScreen(onBack = { overlay = ConsumerOverlay.None })

            return

        }

        ConsumerOverlay.Devices -> {

            DevicesScreen(

                session = session,

                onBack = { overlay = ConsumerOverlay.None },

                onLogout = onLogout,

            )

            return

        }

        ConsumerOverlay.Connections -> {

            ConnectionsScreen(session = session, onBack = { overlay = ConsumerOverlay.None })

            return

        }

        ConsumerOverlay.Subscription -> {

            SubscriptionScreen(session = session, onBack = { overlay = ConsumerOverlay.None })

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

                onOpenRelationship = {

                    relationshipTarget = Triple(contact.userId, contact.phoneE164, contact.effectiveDisplayName)

                    overlay = ConsumerOverlay.Relationship

                },

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

                    chatRoute = ChatRoute(

                        conversationId = null,

                        peerName = presentation.displayName,

                        peerUserId = session.callManager.incomingCall.value?.callerUserId,

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

                    // Group chats are not part of messaging yet; go to Messages.

                    overlay = ConsumerOverlay.None

                    tab = ViroConsumerTab.Messages

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

                route = route,

                onBack = { overlay = ConsumerOverlay.None },

                onCall = { peer, phone, name ->

                    beginCall(CallPresentation(displayName = name, phoneE164 = phone, avatarUrl = route.peerAvatarUrl))

                    withMic { scope.launch { runCatching { session.placeOutgoingCall(phone, name, peer) } } }

                },

                onOpenRelationship = { peer, phone, name ->

                    relationshipTarget = Triple(peer, phone, name)

                    overlay = ConsumerOverlay.Relationship

                },

                onOpenConversation = { next -> chatRoute = next },

                onOpenGroupInfo = { id ->

                    groupInfoId = id

                    overlay = ConsumerOverlay.GroupInfo

                },

                onGroupCall = { memberIds ->

                    scope.launch {

                        val contacts = memberIds.mapNotNull { id -> runCatching { session.contactsRepository.findByUserId(id) }.getOrNull() }

                        if (contacts.isEmpty()) return@launch

                        withMic {

                            session.conferenceManager.startConference(contacts)

                            overlay = ConsumerOverlay.GroupCall

                        }

                    }

                },

            )

            return

        }

        ConsumerOverlay.NewGroup -> {

            com.viroreach.app.consumer.messages.NewGroupScreen(

                session = session,

                onBack = { overlay = ConsumerOverlay.None },

                onCreated = { id, title ->

                    chatRoute = ChatRoute(conversationId = id, peerName = title, peerUserId = null)

                    overlay = ConsumerOverlay.Chat

                },

            )

            return

        }

        ConsumerOverlay.GroupInfo -> {

            val id = groupInfoId
            if (id == null) {
                LaunchedEffect(Unit) { overlay = ConsumerOverlay.None }
                return
            }

            com.viroreach.app.consumer.messages.GroupInfoScreen(

                session = session,

                conversationId = id,

                onBack = {

                    groupInfoId = null

                    overlay = if (chatRoute != null) ConsumerOverlay.Chat else ConsumerOverlay.None

                },

                onLeft = {

                    groupInfoId = null

                    chatRoute = null

                    overlay = ConsumerOverlay.None

                    tab = ViroConsumerTab.Messages

                },

            )

            return

        }

        ConsumerOverlay.Search -> {

            com.viroreach.app.consumer.messages.SearchScreen(

                session = session,

                onBack = { overlay = ConsumerOverlay.None },

                onOpen = { route ->

                    chatRoute = route

                    overlay = ConsumerOverlay.Chat

                },

            )

            return

        }

        ConsumerOverlay.Relationship -> {

            val target = relationshipTarget
            if (target == null) {
                LaunchedEffect(Unit) { overlay = ConsumerOverlay.None }
                return
            }

            com.viroreach.app.relationships.ui.RelationshipScreen(

                session = session,

                peerUserId = target.first,

                phone = target.second,

                name = target.third,

                onBack = {

                    relationshipTarget = null

                    overlay = if (chatRoute != null) ConsumerOverlay.Chat else ConsumerOverlay.None

                },

                onOpenChat = {

                    chatRoute = ChatRoute(conversationId = null, peerName = target.third, peerUserId = target.first, peerPhoneE164 = target.second)

                    relationshipTarget = null

                    overlay = ConsumerOverlay.Chat

                },

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

                        val phone = log.phoneE164
                        if (phone == null && log.peerUserId == null) return@HomeScreen

                        scope.launch {

                            // Resolve the registered contact so the chat is sent via the
                            // real message API (peerUserId != null). Leaving this null
                            // silently routed the message through the call-signaling
                            // channel instead, which drops it (no active call session).
                            val resolvedUserId = log.peerUserId ?: phone?.let {
                                runCatching {
                                    session.contactsRepository.contactForCallLog(log.name, it)
                                }.getOrNull()?.userId
                            }

                            chatRoute = ChatRoute(

                                conversationId = null,

                                peerName = log.name,

                                peerUserId = resolvedUserId,

                                peerPhoneE164 = phone,

                            )

                            overlay = ConsumerOverlay.Chat

                        }

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

                        session.conferenceManager.startConference(contacts)

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

                ViroConsumerTab.Messages -> MessagesInboxScreen(

                    session = session,

                    onOpenChat = { route ->

                        chatRoute = route

                        overlay = ConsumerOverlay.Chat

                    },

                    onCall = { peer, phone, name ->

                        beginCall(CallPresentation(displayName = name, phoneE164 = phone))

                        withMic { scope.launch { runCatching { session.placeOutgoingCall(phone, name, peer) } } }

                    },

                    onOpenRelationship = { peer, phone, name ->

                        relationshipTarget = Triple(peer, phone, name)

                        overlay = ConsumerOverlay.Relationship

                    },

                    startOnConnections = connectionsRequested,

                    onSearch = { overlay = ConsumerOverlay.Search },

                    onNewGroup = { overlay = ConsumerOverlay.NewGroup },

                )

                ViroConsumerTab.Calls -> CallsScreen(

                    session = session,

                    onCallBack = { phone, name, callLogUserId ->

                        if (phone == null && callLogUserId == null) return@CallsScreen

                        val fallbackLabel = phone?.let { PhoneNumberFormatter.formatE164International(it) }
                            ?: "Viro user"

                        beginCall(

                            CallPresentation(

                                displayName = name ?: fallbackLabel,

                                phoneE164 = phone,

                            ),

                        )

                        withMic {

                            scope.launch {

                                val cached = phone?.let { session.contactsRepository.findByPhone(it) }
                                    ?: callLogUserId?.let { session.contactsRepository.findByUserId(it) }

                                session.placeOutgoingCall(

                                    phoneE164 = phone,

                                    displayName = name ?: cached?.effectiveDisplayName ?: fallbackLabel,

                                    userId = callLogUserId ?: cached?.userId,

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

                    onMessage = { phone, name, callLogUserId ->

                        if (phone == null && callLogUserId == null) return@CallsScreen

                        scope.launch {

                            val resolvedUserId = callLogUserId ?: phone?.let {
                                runCatching { session.contactsRepository.findByPhone(it) }.getOrNull()?.userId
                            }

                            val label = name
                                ?: phone?.let { PhoneNumberFormatter.formatE164International(it) }
                                ?: "Viro user"

                            chatRoute = ChatRoute(

                                conversationId = null,

                                peerName = label,

                                peerUserId = resolvedUserId,

                                peerPhoneE164 = phone,

                            )

                            overlay = ConsumerOverlay.Chat

                        }

                    },

                )

                ViroConsumerTab.You -> YouScreen(

                    session = session,

                    showDeveloperEntry = showDeveloperEntry,

                    onDeveloper = { overlay = ConsumerOverlay.Developer },

                    onAppearance = { overlay = ConsumerOverlay.Appearance },

                    onEditProfile = { overlay = ConsumerOverlay.EditProfile },

                    onAddPhone = { overlay = ConsumerOverlay.AddPhone },

                    onBlockedContacts = { overlay = ConsumerOverlay.BlockedContacts },

                    onConnections = { overlay = ConsumerOverlay.Connections },

                    onDevices = { overlay = ConsumerOverlay.Devices },

                    onSubscription = { overlay = ConsumerOverlay.Subscription },

                    onHelp = { overlay = ConsumerOverlay.Help },

                    onLogout = onLogout,

                )

            }

        }

    }

}


