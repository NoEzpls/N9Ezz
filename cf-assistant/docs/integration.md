# Integration notes

The addon is pinned at build time to the network.1 loader SHA-256
`bd6c983ac22f436e914f5510faa598a18c218f08e10c8b5c36504ebf31e40100`.
It uses the loader's native `N9ModInitializer` client entrypoint and contains no
Minecraft class references in descriptors (`namespace: none`). Game linkage uses
the pinned Minecraft 1.21.4 intermediary names reflectively. No server changes,
new mixins, raw packets, client module rewrites, or external dependencies are added.

## Initialization and event routing

Loader READY occurs inside the Minecraft constructor before GameOptions exists.
Installation validates reflection members and subscribes Orbit ConsumerListeners
to `pulse.events.EventBusService.EVENT_BUS`; it never obtains Pulse's singleton or
reads player, world, options or UI state during installation. API-only loader
execution skips this step when the game classes are absent.

Subscriptions explicitly use Orbit's `IListener` overload at highest priority;
the Object overload would instead scan annotations. Partial subscription failure
rolls back already installed listeners. Callback exceptions stop automation.

| Event | Handling |
| --- | --- |
| ChatSendEvent | Cancel `/cfbot` before processing it; pass ordinary chat through; manual `/cf` stops correlation |
| PacketEvent RECEIVE | Extract only non-overlay GameMessageS2CPacket text into a bounded queue; never cancel or alter packets |
| ClientTickEvent | Drain messages and advance the controller on the player tick |
| WorldClearEvent / WorldChangeEvent / GameJoinEvent | Invalidate queued messages and stop before the next command or tick |

Packet receive fires on the network thread. Controller file writes, command sends,
and UI feedback run on the normal game thread. The queue holds at most 256 system
messages; overflow disables automation until restart. A message can arrive between
queue drain and tick processing, so the session uses processing-time monotonic
timestamps. It may stop conservatively after a long render/game-thread stall.

## Verified mappings

Names were checked against pinned Yarn sources and the existing host bytecode;
this is compatibility evidence, not a gameplay test.

| Purpose | Member |
| --- | --- |
| Minecraft singleton | `class_310.method_1551()` |
| Play handler | `class_310.method_1562()` |
| Player / world / screen | `class_310.field_1724`, `field_1687`, `field_1755` |
| System text / overlay flag | `class_7439.comp_763()`, `comp_906()` |
| Ordinary command send | `class_634.method_45730(String)` without leading slash |
| Text literal | `class_2561.method_43470(String)` |
| Local display | `class_1657.method_7353(class_2561, boolean)` |

Vanilla's network handler owns command serialization and transport. The addon does
not change movement, rotation, onGround, velocity, teleport, transaction handling,
packet order, or jitter. It changes no files in the prior network update.

## Correlation and recovery limits

The supplied server messages are approximate; the default configuration contains
no active response templates. Exact templates must retain a distinctive real
server prefix if there is one. GameMessage packets can include server-relayed
player text. A full regex match alone does not authenticate a game result.

No known wager ID is available, so one pending wager is the maximum that can be
tracked conservatively. Parallel manual bets, another automation module, duplicated
or delayed server results can defeat text-only attribution. The addon cannot
prove which wager such a result belongs to. Manual `/cf` invalidates correlation;
the user must also avoid accepting GUI bets or creating bets through other tools.

PacketEvent does not expose its source socket. Queued text is tagged with the
active play-handler identity observed at receipt and a world generation; switching
worlds discards the queue. A late packet from an old socket cannot be proved to
originate there using this event API.

The existing mixin hooks use `require=0` and require an in-game check. Highest
priority capture reduces observer suppression by cancelled events, but other
modules or listener exceptions may still prevent a callback. Missing expected
replies cause a timeout and no automatic retry.

The state file atomically updates unresolved status and confirmed totals.
The pending marker is forced to disk before sending; restart never resumes a
session. State replacement errors prevent a send. Journal or capture errors block
further automation. This guards ordinary process crashes; it is not a transaction
with the server and cannot prove whether an interrupted command was accepted.

Reported amounts and names are retained as text. No fee arithmetic or confidence
based wager sizing is inferred. Tests use fabricated messages and fake game types;
they must not be presented as recordings from RaidMine.
