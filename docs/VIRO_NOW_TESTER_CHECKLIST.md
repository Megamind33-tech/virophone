# Viro Now — two-phone acceptance

Install the Firebase App Distribution build named in the release report on both phones. Record the version, phone model, Android version and results below. Use two distinct authenticated accounts, A and B, with an accepted Viro connection.

1. **Existing messaging:** Open an existing conversation, send a message each way, and check unread counts. Return to Home; conversations remain below Now. Check Calls and Contacts navigation.
2. **Creation and realtime:** A opens “What are you up to?”, keeps Free / 15 min / Viro connections, and taps Start. A sees its own Moment. B sees A's activity and remaining time without restarting or manually refreshing. Record the delay.
3. **Detail and calling:** B opens A's card, then taps Talk. Accept on A and confirm two-way audio. End the call and return to Home.
4. **Management:** A opens Manage, extends 15 min, and checks the updated remaining time on both phones. A ends the Moment; it disappears from B without a restart.
5. **Automatic expiry:** A creates a one-minute Moment. Leave both phones open until it expires. It disappears on both. Restart both apps; it does not reappear.
6. **Blocking:** Create a new Moment. A blocks B using the existing block flow. B loses A's Moment and cannot open it again. Unblock and re-establish the connection if necessary before the remaining checks.
7. **Audience:** Choose My contacts only after syncing contacts on A. Verify visibility follows A's contacts. A stranger merely saving A's number must not see A's activity. An unrelated third account can be used for this check if available.
8. **Other types:** Check Break, Listening, Watching, Gaming, Working and Custom; Custom accepts at most 60 characters. Only Free offers Talk in this phase. Do not expect full rooms, room chat, Join or Knock.
9. **Offline and recovery:** Open Home, interrupt the network, and verify cached conversations remain accessible. Reconnect and verify Moments reconcile. Sign out and use a different account; the previous account's Moments must not remain visible.
10. **Layout:** On the narrowest available phone, check Home, the creation sheet, See all and detail, with the keyboard open for Custom. Repeat with light/dark system settings and large text. Capture screenshots showing the full viewport; check for clipped actions, overlapping text and navigation coverage.

Return: build version; device details for A/B; PASS/FAIL for each numbered check; screenshots; and exact steps for any failure. Automated API privacy checks are recorded separately in the release report. Physical acceptance remains pending until these results are returned.
