# Viro Reach — Completion Checklist (A → E)

Tracked plan to take the app from "working 1:1 voice spine" to a complete
calling + messaging product. Checked items are implemented and tested in this
repo. Items needing external credentials/infrastructure are marked ⚠️ and become
functional once the corresponding secret/service is supplied.

## Phase A — Make 1:1 calling production-real
- [x] A1. OTP provider abstraction + pluggable SMS provider (Twilio/HTTP), env-selected. ⚠️ needs SMS creds
- [x] A2. Push tokens: registration API + storage (`push_tokens`)
- [x] A3. Push sender abstraction (FCM) + call-invite push on authorize when callee offline. ⚠️ needs FCM key
- [x] A4. Call lifecycle persistence: RINGING/ACTIVE/answered_at from signaling
- [x] A5. Call quality telemetry: `/calls/:id/events` persists to `call_quality`
- [ ] A6. Android: FCM receiver → wake incoming-call UI; register push token on login

## Phase B — Messaging as a real subsystem
- [ ] B1. Schema: `conversations`, `conversation_participants`, `messages`, `message_receipts`
- [ ] B2. REST: send message, list conversations, message history, mark read
- [ ] B3. Realtime delivery via signaling gateway + offline queue + push fanout
- [ ] B4. Multi-device fanout for messages
- [ ] B5. Android: Messages/Inbox tab + conversation list; persist chat in Room; receipts

## Phase C — Scale & reliability
- [ ] C1. Redis pub/sub in signaling gateway → multi-instance delivery
- [ ] C2. Multi-device call fanout (ring-all) via device registry
- [ ] C3. Apply global ThrottlerGuard; register ApiExceptionFilter
- [ ] C4. Wire offline-trust ticket verification into call authorize
- [ ] C5. List endpoints: `GET /connections`, `GET /blocks`, `GET /calls`
- [ ] C6. Observability: request/WS/TURN metrics endpoint

## Phase D — Group calling
- [ ] D1. Group call signaling: conference create/join/leave + participant fanout (mesh, small N)
- [ ] D2. Android: real multi-party WebRTC (mesh) wiring in ConferenceManager/GroupCallScreen
- [ ] D3. Media/avatar object storage (upload API). ⚠️ needs storage bucket
- [ ] D4. (If needed for large groups) SFU integration. ⚠️ needs SFU service

## Phase E — Product completeness & launch
- [ ] E1. Android Settings (notifications/privacy/help), blocked-list UI, country picker
- [ ] E2. Account deletion / GDPR export
- [ ] E3. Subscriptions/billing service (if in scope)
- [ ] E4. Admin/moderation APIs
- [ ] E5. Android release signing + Play pipeline. ⚠️ needs keystore
- [ ] E6. Remove dead stubs (voice/linphone, transport/*, empty feature modules) or integrate if useful
- [ ] E7. Reconcile stale docs with implementation

## External inputs required (please provide when ready)
- SMS provider credentials (for A1): `OTP_PROVIDER`, `SMS_API_URL`/`SMS_API_KEY`/`SMS_FROM` (or Twilio SID/token/from).
- FCM credentials (for A3/A6): `FCM_SERVER_KEY` (or service account) + `google-services.json` for Android.
- Object storage (for D3) and SFU (for D4) if large group calls are required.
- Two physical Android devices for final end-to-end media verification.
