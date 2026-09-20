# Wi-Fi Direct + Local Android Auto Dev-Server Bridge (Root)

## Status

Implementation branch: `codex/wifi-direct-dev-root-v2`.

- This refactor replaces the earlier direct-connect root transport; it does not add a second transport or automatic fallback.
- Only Wireless Helper changes. Use the head unit's ordinary **Wireless Helper / Wi-Fi Direct** mode, not Headunit Server / Auto. A special head-unit build is not required.
- Mode ID `5`, URI `wifi-direct-dev-root`, saved settings and application version are unchanged.
- This is an alternative to the earlier direct-connect proposal, not a follow-up requiring its merge. Maintainers can choose either architecture; the bridge does not need the companion head-unit changes.
- The author reports successful end-to-end projection with this bridge on the tested phone/head-unit pair. This is separate from the earlier direct-connect device test, not a claim of broad AA/OEM compatibility or completion of the entire acceptance checklist. Clean Gradle test/lint results for the final revision remain unconfirmed; the implementation machine has no Java/Android toolchain.

## Setup

1. Configure Android Auto on the phone, enable its developer settings and verify that its headunit server can be started manually.
2. Install the updated helper on the phone. Root is required only on the phone. Initially unlock it, grant helper permissions and grant persistent root access to the actual helper package you installed (debug and release are different packages).
3. On the head unit, select **Wireless Helper / Wi-Fi Direct** and save. Do not select Headunit Server / Auto for this bridge. No new head-unit settings or binary changes are required.
4. On the phone, select **Wi-Fi Direct / Dev server (root)**. Root and the expected Android Auto component are checked before saving the selection.
5. Select Bluetooth auto-start and the intended car/head-unit Bluetooth devices. Alternatively use Start, the tile, widget, or `wirelesshelper://start?mode=wifi-direct-dev-root`.
6. Start the head-unit app, which creates its usual OpenHU P2P group and listens on TCP 5288. Do not also enable an external hotspot manager for this radio.
7. The helper starts the Dev server, joins OpenHU, waits for a usable local P2P address, and connects to the head unit on 5288. It then connects to the local Dev server on 5277 and forwards the AA stream unchanged in both directions. There is no subnet scan or helper control protocol.

```text
Android Auto Dev server :5277 <-> helper bridge <-> head unit :5288
             phone                                Helper / Wi-Fi Direct
```

User-entered P2P target names are retained. Only the old default set containing exactly HURev is extended with OpenHU.

On head units running Android 10+, an ordinary non-root app may not be allowed to enable Wi-Fi. Enable Wi-Fi through the system UI in that case. The phone can request Wi-Fi through root. OEM P2P permission/location requirements still apply.

## Ownership and Recovery

This mode owns the developer server while running, including a server previously enabled manually. Do not run a second helper instance or another application managing the same server. It never force-stops all of Android Auto.

- Repeated Start or Bluetooth connect events do not restart an already requested root session.
- Peer discovery is retried every 10 seconds. An unanswered invitation is cancelled after 20 seconds.
- The last selected Bluetooth disconnect starts a 5-second grace period. A selected device returning cancels it. After the grace period the server and helper's P2P session stop.
- P2P loss closes both bridge sockets immediately. After 5 seconds without P2P the server stops while discovery continues; a return cancels the pending stop. Joining again starts the server.
- Both bridge sockets determine connection state. A healthy bridge does not trigger root polling or idle server restarts. Closing either side closes the other, including blocked IO.
- Connection failures back off by 2, 4, 8, 16, 30, then 60 seconds. Sixty continuous seconds with the bridge connected resets the backoff. A failed head-unit connection does not restart the Dev server; a failed local-server connection or an ended bridge requests server recovery before the next attempt.
- An already established Dev-server connection not owned by this bridge is left alone. Passive checks every 2 seconds wait for it to finish instead of competing for the server. Explicit helper Stop still shuts down the managed server.
- Head-unit connect timeout is 5 seconds; local Dev-server connect timeout is 2 seconds. The head-unit socket binds to the actual P2P source address; missing P2P addressing is not replaced with ordinary Wi-Fi or a fixed gateway.
- Root shell commands are limited to 10 seconds, off the UI thread. Readiness/shutdown polling is also bounded.
- Stop in helper cancels retries, closes and joins the bridge, confirms Dev-server shutdown, releases its P2P session, then stops the foreground service. A failed cleanup keeps the persistent recovery marker and shows an error.
- **Exit on the head unit may reconnect.** An unmodified head unit does not tell the transparent bridge whether TCP ended intentionally or accidentally. Use Stop in the helper or disconnect the last selected Bluetooth device to stop definitively.
- Persisted requested/managed flags support service recreation. An interrupted cleanup is retried when the app is next opened or started. Force Stop or power loss cannot execute cleanup at that instant; there is no separate root daemon.

The helper reports discovery, P2P, Dev-server readiness, waiting for another session, head-unit connection and bridge connection separately. **Bridge connected means both TCP sockets are open, not proof of a working AA projection**; check projection on the head unit.

## Developer Server Commands

Confirmed user startup command on Android 8+:

```sh
su -c 'am start-foreground-service -W com.google.android.projection.gearhead/com.google.android.projection.gearhead.companion.DeveloperHeadUnitNetworkService'
su -c 'am startservice -a shutdown -n com.google.android.projection.gearhead/com.google.android.projection.gearhead.companion.DeveloperHeadUnitNetworkService'
```

Below Android 8 the start command uses `am startservice`.

Readiness is NOT inferred from `-W` or command exit status. During start, stop and recovery the controller reads `dumpsys activity services` and `/proc/net/tcp` / `tcp6` through root, filters by the installed Android Auto UID and local port 5277, and distinguishes LISTEN from ESTABLISHED. Loopback listeners are usable by this bridge. Wildcard IPv4/IPv6 listeners map to 127.0.0.1/::1; a specific address is used only if it belongs to the phone. A listener with no usable local endpoint produces an explicit error. **Do not use nc/telnet or a test TCP connection to check readiness**: every successful connection must become the actual AA transport, not be closed and re-opened as a probe.

Shutdown uses the notification Stop action observed on the test phone: a `startService` PendingIntent with `action=shutdown` targeting the same component. Do not substitute `am stopservice`: on that phone it removed the service and notification but left AA's TCP 5277 listener alive, preventing subsequent startup. The shell command requests shutdown; passive service and socket state still confirm completion. The equivalent root shell command must also be verified on each supported AA version.

This is an internal Android Auto component, not a supported public API. Component renames, root restrictions, SELinux restrictions on /proc or changes in the dumpsys format must produce an explicit error rather than a claimed connection.

## Build and JVM Tests

Run in the corresponding checkout on a machine with that project's Android/JDK/Gradle prerequisites:

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
```

Use a JDK supported by this Gradle wrapper (for example Android Studio's Java 21 JBR). No head-unit build is needed. Tests cover root commands and passive shutdown, cancellation, mode reuse, local IPv4/IPv6 endpoint selection, exact socket ownership, P2P source selection, bridge byte integrity and backpressure, socket teardown, retries and Bluetooth grace periods. Existing unrelated lint issues are outside this refactor.

These tests do not replace framework/device tests of broadcast delivery, foreground-service restrictions, hotspot teardown, P2P negotiation or the actual Android Auto service.

## Device Acceptance Checklist

First verify the complete server start/stop cycle, including passive absence of its listener after Stop. Then record the following on the actual phone/head-unit pair:

| Scenario | Expected result |
| --- | --- |
| Head unit first, phone/BT second | OpenHU appears; one TCP dial becomes an AA session |
| Phone/BT first, head unit second | Helper waits and connects without Stop/Start |
| Phone Wi-Fi off at startup | Root requests Wi-Fi; connection proceeds or a clear Wi-Fi error appears |
| Head Wi-Fi off | Enable where the platform allows; otherwise system UI is required |
| Phone locked after initial grants | BT starts helper without another root/permission prompt |
| Ordinary Wi-Fi connected in parallel | Head-unit socket uses P2P local IP, not the station gateway |
| Server already enabled manually | Adopt it; helper Stop also stops it |
| Duplicate Start, tile/BT events | Healthy bridge/session is not restarted |
| One of two selected BT devices leaves | Keep session while the other remains connected |
| Last BT leaves for less than 5 seconds | Do not stop if it returns |
| Last BT stays away | Stop server and release P2P after grace |
| P2P interrupted | Close bridge immediately, stop server after grace; rediscover and resume |
| Either bridge socket interrupted with P2P retained | Close the other socket, recover server and reconnect with backoff |
| Head unit not listening on 5288 | Retry without repeatedly restarting the Dev server |
| P2P group disappears on head | Helper resumes when the ordinary head-unit P2P group returns |
| Head Stop/Exit | Reconnection is allowed; helper Stop is the definitive stop |
| Helper Stop during connect or blocked transfer | Both sockets close; no late callback or retry starts another bridge |
| Server busy with another client | Wait without taking over or restarting that connection |
| Kill helper process (not Force Stop) | Requested service can recover without overlapping sessions |
| Force Stop helper, then open it again | Stale managed server is cleaned up before another session |

Acceptance target: **10 full connect/disconnect cycles without manual Stop/Start recovery**, successful recovery from random P2P/TCP loss, no remaining Dev server after helper Stop, and no timer-driven resets of a healthy projection. Also run at least **30 minutes of projection**, checking audio, controls, latency and phone temperature. Initial end-to-end device testing was reported successful; completion of this entire matrix has not been confirmed.

Manual non-root Android Auto menu automation is intentionally out of scope for v1.
