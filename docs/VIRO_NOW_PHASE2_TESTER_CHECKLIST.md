# Viro Now Phase 2 — two-phone acceptance

Install the Firebase App Distribution build named in `VIRO_NOW_PHASE2_REPORT.md` on both phones. Two distinct authenticated accounts (A and B) with an accepted Viro connection. The bottom bar is now Now / Chats / Calls / Contacts — check each destination still does its own job.

1. **Navigation split:** Now shows only Moments (Your Moment, Active now, Invitations when any). Chats shows conversations with no Moments above them. Nothing appears twice.
2. **Room entry (§33):** A starts a Free Moment and opens the room from Manage. A invites B from People → Invite. B taps the invitation on Now and lands directly in the room. Both phones show **2 participants**; A is marked Host.
3. **Room chat:** B sends "I'm in"; A receives it live without restarting. A long-presses the message and reacts 🔥; B sees the reaction live. No new conversation appears in Chats from any of this.
4. **Leave:** B backs out of the room; A's participant count returns to **1** without a restart. B re-joins; still exactly 2 (no duplicates).
5. **Knock (§34):** B taps **Knock** on A's Free card (back on Now). A gets "B wants to talk" wherever A is in the app. Accept → the normal call screen launches; two-way audio; the call appears in Calls history afterward. Repeat with Not now → dismisses cleanly.
6. **Talk:** From the room's People tab, B taps "Talk to A"; the existing call flow starts after access is rechecked.
7. **Message action:** In People, B taps A's row ("Message"); the existing normal chat with A opens.
8. **Ending (§19):** A ends the Moment from Manage. B's open room shows "This Moment has ended." and returns to Now. B cannot rejoin or reopen; the invitation is gone from B's Now.
9. **Expiry:** Repeat with a 1-minute Moment and leave both phones on the room: at expiry the room closes by itself, both apps drop the Moment, restart does not revive it.
10. **Blocking:** With a new Moment, A blocks B. B loses the card, cannot join or knock, and any invitation disappears from B's Now. Unblock before the remaining checks.
11. **Layout:** On the narrowest phone, check Now, the room (Chat and People, keyboard open in chat), Chats, light/dark, and large text. One primary action per card.

Return: build version; device details A/B; PASS/FAIL per numbered check; screenshots of any layout issue. Automated privacy/block/expiry checks are recorded separately in the release report.
