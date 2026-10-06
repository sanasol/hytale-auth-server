# Client notification delivery, release 0.6.8

Verified 2026-10-06 with the native release client and isolated WsTestB1006 account.
The gateway accepts `chat` notifications carrying `{message: string}`. Static
inspection found ChatPushPayload but no registered display callback or generic
receipt/read ACK in the inspected dispatcher. This is bounded evidence, not a
claim about every protocol in the game.

Runtime checks:
- Main menu at 20:34:26: notification received, then `Gateway dispatch skipped: no handler for ChatPushPayload`; no toast or notification-bell entry.
- Entered a local world at 20:42:03 (`GameLoading to InGame`).
- Targeted `chat` sent at 20:42:25: notification received, same missing-handler warning.
- Opened in-game chat: only the normal world-join line appeared, no administrative message.

Test text contained plain text, `<b>bold</b>` and an HTTPS URL. Since the payload
never reached a renderer, HTML/link support is unverified, not supported by this
experiment. Current social friend/party/world-invite toasts are separate typed
notifications and cannot honestly substitute for arbitrary announcements.

No working announcement composer is exposed: this client needs a display handler
(or a separately verified supported notification type) first. Current Redis
pub/sub targets connected clients and is not an offline inbox. Durable queuing,
expiry, replay policy and idempotent receipt ACK would need an explicit protocol;
a successful WebSocket send alone proves neither display nor receipt. Game-server
chat packets are a separate transport from the social gateway.
