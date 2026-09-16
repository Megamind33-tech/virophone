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
- [ ] A6. Android: FCM receiver → wake incoming-call UI; register push token on login

## Phase B — Messaging as a real subsystem
- [x] B1. Schema: `conversations`, `conversation_participants`, `messages`, `message_receipts`
- [x] B2. REST: send message, list conversations, message history, mark read
- [x] B3. Realtime delivery via realtime registry + push fallback when offline
- [x] B4. Multi-device fanout for messages (via realtime registry / push)
- [ ] B5. Android: Messages/Inbox tab + conversation list; persist chat in Room; receipts

## Phase C — Scale & reliability
- [x] C1. Redis pub/sub delivery bus → multi-instance signaling + messaging
- [~] C2. Multi-device fanout: messages fan out to all devices; call ring-all still 1 device
- [x] C3. Apply global ThrottlerGuard; register ApiExceptionFilter
- [x] C4. Offline-trust call ticket accepted as an alternative authorization in `authorize`
- [x] C5. List endpoints: `GET /connections`, `GET /blocks`, `GET /calls/history`
- [ ] C6. Observability: request/WS/TURN metrics endpoint

## Phase D — Group calling
- [x] D1. Group call signaling: conference create/join/leave + participant fanout (mesh, small N)
- [ ] D2. Android: real multi-party WebRTC (mesh) wiring in ConferenceManager/GroupCallScreen
- [ ] D3. Media/avatar object storage (upload API). ⚠️ needs storage bucket
- [ ] D4. (If needed for large groups) SFU integration. ⚠️ needs SFU service

## Phase E — Product completeness & launch
- [ ] E1. Android Settings (notifications/privacy/help), blocked-list UI, country picker
- [ ] E2. Account deletion / GDPR export
- [ ] E3. Subscriptions/billing service (if in scope)
- [ ] E4. Admin/moderation APIs
- [ ] E5. Android release signing + Play pipeline. ⚠️ needs keystore
- [~] E6. Removed dead forked Linphone engine (voice/linphone). transport/* kept (referenced by CallRouteEngine/tests); empty feature modules kept as placeholders for planned screens.
- [ ] E7. Reconcile stale docs with implementation

## Android client wiring status
- [x] API client methods added for push tokens, call history/telemetry, messaging, conferences (`core/network/ViroApiService.kt`).
- [ ] A6. FCM receiver + token registration on login. ⚠️ needs `google-services.json` + Firebase deps
- [~] B5. Server-backed messages repository done (`ServerMessagesRepository`); inbox screen + realtime `message.new` merge into UI remaining
- [ ] D2. Group mesh client wiring in ConferenceManager/GroupCallScreen (signaling API ready)
- [ ] E1. Settings screens, blocked-list UI, country picker

## External inputs required (please provide when ready)
- SMS provider credentials (for A1): `OTP_PROVIDER`, `SMS_API_URL`/`SMS_API_KEY`/`SMS_FROM` (or Twilio SID/token/from).
- FCM credentials (for A3/A6): `FCM_SERVER_KEY` (or service account) + `google-services.json` for Android.
- Object storage (for D3) and SFU (for D4) if large group calls are required.
- Two physical Android devices for final end-to-end media verification.
