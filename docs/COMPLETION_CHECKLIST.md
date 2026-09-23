# Viro Reach — Completion Checklist (A → E)

Tracked plan to take the app from "working 1:1 voice spine" to a complete
calling + messaging product. Checked items are implemented and tested in this
repo. Items needing external credentials/infrastructure are marked ⚠️ and become
functional once the corresponding secret/service is supplied.

## Phase A — Make 1:1 calling production-real
- [x] A1. OTP provider abstraction: SMS (Twilio/HTTP) AND email (SMTP + **Mailtrap**) channels, env-selected. Email login path (`/api/v1/auth/email/otp/*`) implemented and **verified end-to-end via Mailtrap sandbox** (email delivered, code authenticated). Switch `MAILTRAP_MODE=live` after verifying the sending domain DNS.
- [x] A2. Push tokens: registration API + storage (`push_tokens`)
- [x] A3. Push sender abstraction (FCM) + call-invite push on authorize when callee offline. ⚠️ needs FCM key
- [x] A4. Call lifecycle persistence: RINGING/ACTIVE/answered_at from signaling
- [x] A5. Call quality telemetry: `/calls/:id/events` persists to `call_quality`
- [x] A6. Android: FCM receiver → wake incoming-call UI; register push token on login. ViroFirebaseMessagingService is in the manifest and calls registerPushToken on refresh.

## Phase B — Messaging as a real subsystem
- [x] B1. Schema: `conversations`, `conversation_participants`, `messages`, `message_receipts`
- [x] B2. REST: send message, list conversations, message history, mark read
- [x] B3. Realtime delivery via realtime registry + push fallback when offline
- [x] B4. Multi-device fanout for messages (via realtime registry / push)
- [x] B5. Android chat is server-backed (send/history/`message.new`) **and** dedicated Messages inbox tab syncs conversations from the API

## Phase C — Scale & reliability
- [x] C1. Redis pub/sub delivery bus → multi-instance signaling + messaging
- [x] C2. Multi-device call ring-all: authorize stores every online callee device; invite/offer/ICE fan out until the first device answers; others receive `call.busy`
- [x] C3. Apply global ThrottlerGuard; register ApiExceptionFilter
- [x] C4. Offline-trust call ticket accepted as an alternative authorization in `authorize`
- [x] C5. List endpoints: `GET /connections`, `GET /blocks`, `GET /calls/history` (history enriched with peer display name + direction)
- [x] C6. Observability: `GET /health/metrics` (HTTP/WS/TURN/call counters)

## Phase D — Group calling
- [x] D1. Group call signaling: conference create/join/leave + participant fanout (mesh, small N)
- [x] D2. Android mesh client: `ConferenceManager` + `MeshVoiceEngine` (one PeerConnection per remote device), `conf.join`/`conf.invite`/`conf.offer`/`conf.answer`/`conf.ice`
- [ ] D3. Media/avatar object storage (upload API). ⚠️ full bucket storage still needed; local avatar serve via `GET /api/v1/media/avatars/:file` exists
- [ ] D4. (If needed for large groups) SFU integration. ⚠️ needs SFU service

## Phase E — Product completeness & launch
- [x] E1. Android Settings: blocked-contacts list, login country picker, Help, devices, connections, calling privacy, account export share, notifications. The notifications row opens the system per-channel page, which is where the switches for these channels actually live.
- [x] E2. Account deletion (`DELETE /api/v1/me`) and GDPR export (`GET /api/v1/me/export`) + Android “Download my data”
- [x] E3. Subscriptions/billing **stub**: `GET /plans`, `GET|POST /me/subscription` (Free/Plus seed, no Stripe/Play yet) + Android Subscription screen
- [x] E4. Admin/moderation APIs (`/api/v1/admin/*`) via `X-Admin-Key` or ADMIN/SECURITY_ADMIN role
- [ ] E5. Android release signing + Play pipeline. ⚠️ needs keystore
- [~] E6. Removed dead forked Linphone engine (voice/linphone). transport/* kept (referenced by CallRouteEngine/tests); empty feature modules kept as placeholders for planned screens.
- [x] E7. Docs reconciled with current calling/messaging/conference/admin/subscription surface

## Android client wiring status
- [x] API client methods for push, call history/telemetry, messaging, conferences, account export/delete, devices, connections, plans/subscription
- [x] A6. FCM receiver + token registration on login. Shipping; google-services.json and the Firebase dependencies are in place.
- [x] B5. Messages inbox tab + ChatScreen history hydrate / server send
- [x] Calls tab merges server `GET /calls/history` into local call log
- [x] D2. Group mesh client wiring in ConferenceManager/GroupCallScreen
- [x] E1. Help, blocked-list, country picker, devices, connections, calling privacy, export
- [x] E3. Subscription preview screen

## External inputs required (please provide when ready)
- SMS provider credentials (for A1): `OTP_PROVIDER`, `SMS_API_URL`/`SMS_API_KEY`/`SMS_FROM` (or Twilio SID/token/from).
- FCM credentials (for A3/A6): `FCM_SERVER_KEY` (or service account) + `google-services.json` for Android.
- Object storage (for D3) and SFU (for D4) if large group calls are required.
- Play Billing / Stripe (to replace E3 stub with real charges).
- Android release keystore (for E5).
- Two physical Android devices for final end-to-end media verification.
- Optional `ADMIN_API_KEY` for the moderation API (or promote a user `admin_role` to `ADMIN`).
