# WorkBuddy cloud compatibility notes

Inspected the publicly served [WorkBuddy web app](https://www.workbuddy.cn/app) on 2026-10-31.
The app referenced `main-WwHVMzJn.js` from asset build
`38e1cc7eb1640468090a4241355ae65ae569da48` on `static.workbuddy.cn`.
No authenticated account requests were used for this inspection.

- The model API preserves the `credits` display string, such as `x0.21`.
  Wearbuddy shows this string next to the model name, without guessing a multiplier.
- ACP uses an SSE GET with `Acp-Connection-Id` and optionally `acp-session-token`.
  JSON-RPC responses and notifications can also be returned on the POST response stream.
- Initialize with `protocolVersion: 1`; attach/load a session before prompting.
  The console conversation ID and the returned ACP `sessionId` can differ.
- Replay and live messages use `session/update` with `user_message_chunk`,
  `agent_message_chunk`, `agent_thought_chunk`, and tool progress updates.
  Read complete SSE frames, retain whitespace chunks, and keep full reply text.
- Completion is carried by the prompt RPC `stopReason`, `session/endTurn`,
  `state_update`, or `session_info_update` metadata (`codebuddy.ai/status`
  or nested `codebuddy.ai.status`). Historical completion and snapshot messages
  must not finish a new live turn.
- Artifact notifications use `_codebuddy.ai/artifact`, directly or inside
  `extNotification`, with `event`, `artifact`, and sometimes a separate download `url`.
- Use one loaded connection for replay and prompts. Opening another connection
  for every history refresh can detach live progress from the UI.

Validation: `:wear:testDebugUnitTest`, `:wear:assembleDebug`, and
`:wear:assembleRelease`; release upgrade and cold launch on WearRound33.
Real account cloud execution still requires a logged-in device for end-to-end validation.


## 1.0.8 compatibility update (2026-11-14)

Re-inspected the public WorkBuddy app, asset build
`91e8705fce7fb33abd9e02e26cd5e9d2e3247cfb` (`main-D4DaPwsz.js`).
The current artifact reader first resolves the conversation session and reads
`{e2bDataEndpoint}/files?path={decodedWorkspacePath}&username=user`, using
`X-Access-Token: {session.token}`. COS presigning is a fallback, not the primary
reader for sandbox workspace files. Workspace paths retain `/workspace/` and
are checked for traversal after decoding, including double encoded traversal.

The public client's ordinary ACP transport still opens an SSE GET before
initializing and loading the session. Wearbuddy now checks HTTP status and
content type before reading the connection header, disables cached handshake
responses, and makes up to three safe handshake attempts with renewed session
credentials. Prompt RPCs are not automatically replayed.

Account settings and cookie stores are bound to immutable profile IDs. Existing
single-account preferences remain in `wearbuddy` (legacy profile). Adding an
account uses a separate pending profile and does not activate it until login
succeeds. Old UI jobs and repository links are closed when switching accounts.
Preview cache keys include the conversation; saved files also use profile
subdirectories to avoid collisions between accounts.

The explicit in-app file picker replaces system document/image picker intents.
It uses official Wear Compose Material 3 components and returns selected files
through FileProvider. App artifact folders work without a document provider;
public watch storage requires the OS storage permission. On devices without
storage permission settings, the app folders remain available.

Validation: 24 JVM tests; four device tests on WearRound33 (API 33), covering
account credential isolation, pending-account behavior and the Add account
button, local file selection and FileProvider reads, and model picker scrolling.
Debug and signed release builds pass; APK signature verification passes.
No authenticated real-account cloud execution or artifact transfer was performed.
