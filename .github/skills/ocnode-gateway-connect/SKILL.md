---
name: ocnode-gateway-connect
description: 'Diagnose and fix OCHelper OC Node connection AND command-invocation failures with an OpenClaw Gateway (WebSocket protocol v4). USE WHEN: the OC Node will not connect, stays "未连接"/disconnected; gateway logs show "closed before connect" (1006), "device signature expired" (1008), "protocol mismatch", or "pairing required"; OR the node connects but every `openclaw nodes invoke`/`camera snap` TIMES OUT, declares no usable caps/commands, returns "node command not allowed", or "invalid camera.snap payload". Covers the v4 connect.challenge handshake, Ed25519 device signing, clock-skew handling, device pairing approval, the node.invoke.request/result protocol, canonical caps/commands vocabulary, dangerous-command allowlisting, and build/deploy/verify steps.'
argument-hint: 'e.g. "OC node cannot connect" or "node invoke times out"'
---

# OCHelper OC Node ↔ OpenClaw Gateway Connection

How to make the OCHelper Android app's OC Node connect to an OpenClaw Gateway using the **protocol v4 challenge-response handshake**.

## When to Use
- OC Node stays disconnected / UI shows "○ 未连接".
- Gateway logs show any of: `closed before connect` (code 1006), `device signature expired` (1008), `protocol mismatch`, `pairing required: device is not approved yet`.
- After changing gateway versions and the previously-working node breaks.

## Key Facts (verify against the RUNNING gateway, not local source)
- Trust the running Docker container's protocol version, NOT local source. Confirm with:
  - `docker exec <container> node -e "..."` / bundle grep for `minProtocol`, or gateway logs (`expected=4 probeMin=4`).
- The running gateway here is **PROTOCOL_VERSION=4**, `mode: local`, no auth token.
- Installed app package is **`com.ochelper.debug`** (debug suffix), NOT `com.ochelper`. Always `force-stop com.ochelper.debug`.
- Main activity: `com.ochelper.ui.MainActivity`.

## The v4 Handshake (what the client MUST do)
Implemented in [OCNodeClient.kt](../../../app/src/main/java/com/ochelper/ocnode/OCNodeClient.kt):

1. Open WebSocket to `ws://<host>:18789`.
   - **Do NOT send an `Authorization: Bearer` HTTP header.** A `mode:local` gateway rejects it at the upgrade layer → `code=1006` "closed before connect".
2. The gateway immediately sends `event: connect.challenge` with `payload: { nonce, ts }`. **Wait for it** before sending `connect`.
3. Send a `connect` request that includes an Ed25519-signed `device` block:
   - **Device identity** ([DeviceIdentityStore.kt](../../../app/src/main/java/com/ochelper/ocnode/DeviceIdentityStore.kt)): persistent Ed25519 keypair via BouncyCastle lightweight API; `deviceId = sha256hex(rawPublicKey)`; stored at `filesDir/openclaw/identity/device.json`; public key + signature are base64url **NO_PADDING**.
   - **Signature payload** ([DeviceAuthPayload.kt](../../../app/src/main/java/com/ochelper/ocnode/DeviceAuthPayload.kt)): pipe-joined V3 string
     `v3|deviceId|clientId|clientMode|role|scopes(csv)|signedAt|token|nonce|platform|deviceFamily`
     (metadata fields lowercased).
   - **device block**: `{ id, publicKey, signature, signedAt, nonce }`.
   - **`signedAt` MUST equal the challenge's server `ts`** (`payload.ts`), NOT `System.currentTimeMillis()`. A skewed device clock (was ~448s ahead) otherwise triggers `1008 "device signature expired"`.
   - **connect params**: `minProtocol=3`, `maxProtocol=4`, `client{ id="openclaw-android", instanceId, displayName, version, platform="android", mode="node", deviceFamily="Android", modelIdentifier }`, `caps`, `commands`, `role="node"`, `locale`, `userAgent`; `auth.token` optional.
4. First connection returns `1008 "pairing required: device is not approved yet"` until the device is approved (see below).

## Device Pairing Approval (one-time per device key)
```bash
docker exec <container> openclaw devices list            # find pending requestId
docker exec <container> openclaw devices approve <requestId>
# or: openclaw devices approve --latest
```
After approval, the node connects on the next reconnect (and on first attempt thereafter).

## Robustness
`runLoop` closes its WebSocket in a `finally { ws.cancel() }` so a stale socket doesn't linger and flap with `1006` until the 30s ping timeout.

---

# Part 2 — Command Invocation (node connects but invokes fail)

A node can connect cleanly yet still fail to do any work. The three independent
bugs below were each diagnosed and fixed; check them in order.

## 2a. The invoke protocol — node MUST answer `node.invoke.request`
**Symptom:** `openclaw nodes invoke ...` → `GatewayClientRequestError: TIMEOUT: node invoke timed out`.
Node logcat shows `Gateway event: node.invoke.request` but `handleInvoke` never runs.

**Cause:** the gateway delivers an invoke as an **event**, not a `req` frame, and
expects the reply as a **request** named `node.invoke.result`. Listening only for
`type=="req"` silently drops every invoke.

Wire format (verified against the gateway's own node host `/app/dist/node-cli-*.js`:
`coerceNodeInvokePayload`, `buildNodeInvokeResultParams`, `sendInvokeResult`):

- **Incoming** (gateway → node):
  ```json
  { "type":"event", "event":"node.invoke.request",
    "payload":{ "id":"...", "nodeId":"...", "command":"device.info",
                "paramsJSON":"{...}" /* or params:{} */,
                "timeoutMs":..., "idempotencyKey":"..." } }
  ```
  (Some gateways may instead send `type:"req", method:"node.invoke.request"` — handle both.)
- **Reply** (node → gateway), a **request** frame:
  ```json
  { "type":"req", "id":"<new-uuid>", "method":"node.invoke.result",
    "params":{ "id":"<payload.id>", "nodeId":"<payload.nodeId>", "ok":true,
               "payloadJSON":"<JSON.stringify(result)>" } }
  ```
  On failure: `"ok":false, "error":{ "code":"INVOKE_FAILED", "message":"..." }`.
  - **`params.nodeId` MUST echo `payload.nodeId`** — the server checks
    `callerNodeId === p.nodeId` (callerNodeId = the connect `device.id`) and rejects mismatches.
  - `params` may be a nested object or a JSON-encoded `paramsJSON` string — accept either.

Fixed in [OCNodeClient.kt](../../../app/src/main/java/com/ochelper/ocnode/OCNodeClient.kt)
`handleMessage`: route `node.invoke.request` (event and req method) → `handleInvoke(payload)`
→ send a `node.invoke.result` request.

## 2b. Canonical caps/commands vocabulary (else commands are dropped)
The gateway has **two** vocabularies; declaring non-canonical names makes them vanish:
- **caps** = SHORT names: `device, camera, location, notifications, photos, system, contacts, calendar, motion, canvas, sms, voiceWake`.
- **commands** = DOTTED names validated per-platform. **Android default allowlist:**
  `camera.list, location.get, notifications.list, notifications.actions, system.notify,
  device.info, device.status, device.permissions, device.health, contacts.search,
  calendar.events, callLog.search, reminders.list, photos.latest, motion.activity, motion.pedometer`.
- `normalizeDeclaredNodeCommands` silently filters declared commands to the allowlist;
  `isNodeCommandAllowed` requires the command to be BOTH in the platform allowlist AND in the node's declared list.

OCHelper's internal ids (`camera.take_photo`, `gallery.list`, `system.settings`, …) are
NON-canonical and were all dropped except `device.info`/`location.get`. Bridged in
[OCNodeCommandMap.kt](../../../app/src/main/java/com/ochelper/ocnode/OCNodeCommandMap.kt):
internal id → canonical caps + commands; `OCNodeClient` sends the mapped caps/commands and
`handleInvoke` translates the canonical command back via `resolveCapabilityId` before `execute`.
Result advertised: `caps=[device,camera,photos,location] commands=[device.info,device.status,camera.snap,photos.latest,location.get]`.

## 2c. Dangerous commands (e.g. `camera.snap`) — operator opt-in + RE-PAIR
**Symptom:** `node command not allowed: "camera.snap" is not in the allowlist for platform "android"`.
`camera.snap, camera.clip, screen.snapshot, screen.record, contacts.add, calendar.add,
reminders.add, sms.send` are NOT in the Android default allowlist; they need operator opt-in
via `gateway.nodes.allowCommands`.

**Persistence gotcha (this Docker demo):** the entrypoint copies a *template*
`/opt/openclaw-env/openclaw.json` over `/home/node/.openclaw/openclaw.json` on **every start**
(`docker-entrypoint.sh` ~lines 56-57, comment "每次从模板覆盖"). Editing the live config or using
`openclaw config set/patch` is reverted on restart. **Fix = edit the template**, then restart:
```bash
docker exec <container> python3 - <<'PY'
import json; p="/opt/openclaw-env/openclaw.json"; d=json.load(open(p))
d.setdefault("gateway",{}).setdefault("nodes",{})["allowCommands"]=["camera.snap"]
json.dump(d,open(p,"w"),indent=2)
PY
docker restart <container>
```
The paired record **freezes** caps/commands at approval time, so after enabling the command you
must **re-pair** so the node's declared list includes it:
```bash
docker exec <container> openclaw nodes remove --node "OCHelper Android"
# reconnect the node from the app (see Part 1 UI taps), then:
docker exec <container> openclaw nodes pending
docker exec <container> openclaw nodes approve <requestId>
```
> Node pairing (`nodes/paired.json`, `nodes/pending.json`) is SEPARATE from device pairing
> (`devices/paired.json`). Approve nodes with `openclaw nodes approve`, devices with `openclaw devices approve`.

## 2d. `camera.snap` result schema
**Symptom:** invoke reaches the node but `nodes camera snap` fails with `invalid camera.snap payload`.
The gateway parser (`parseCameraSnapPayload`) requires `format` (e.g. `"jpg"`), `base64` (or `url`),
`width`, and `height`. Incoming params use `facing` (not `camera`). Fixed in
[CameraCapability.kt](../../../app/src/main/java/com/ochelper/capability/CameraCapability.kt).
Grant the runtime permission: `adb -s <device> shell pm grant com.ochelper.debug android.permission.CAMERA`.

## 2e. Battery reads `Integer.MIN_VALUE`
On some devices/emulators `BatteryManager.BATTERY_PROPERTY_CAPACITY` returns `-2147483648`.
[DeviceInfoCapability.kt](../../../app/src/main/java/com/ochelper/capability/DeviceInfoCapability.kt)
falls back to the sticky `ACTION_BATTERY_CHANGED` broadcast (`EXTRA_LEVEL*100/EXTRA_SCALE`).
Emulator override: `adb shell dumpsys battery set level N`; restore with `adb shell dumpsys battery reset`.

## Verify invocation end-to-end
```bash
docker exec <container> openclaw nodes describe --node "OCHelper Android"   # lists caps + commands
docker exec <container> openclaw nodes invoke  --node "OCHelper Android" --command device.info   # battery_level
docker exec <container> openclaw nodes camera  snap --node "OCHelper Android"   # -> MEDIA:*.jpg
```
> CLI-operator note: a stock CLI operator may have only `operator.pairing` and hit a scope
> deadlock for `invoke`. The WebUI uses the admin operator (clientId `openclaw-control-ui`). For
> CLI-only testing, the operator's scopes live in `devices/paired.json` (not config-health protected).

## Build / Deploy / Verify
Build (JAVA_HOME and ANDROID_HOME are required):
```bash
cd /home/yaoxin/share/ochelper
JAVA_HOME=/home/yaoxin/Android/android-studio/jbr \
ANDROID_HOME=/home/yaoxin/Android/Sdk \
./gradlew assembleDebug
adb -s <device> install -r app/build/outputs/apk/debug/app-debug.apk
```
Dependency required in [app/build.gradle.kts](../../../app/build.gradle.kts): `implementation("org.bouncycastle:bcprov-jdk18on:1.83")` plus packaging excludes for bouncycastle `picnic`/`x509` `.properties`.

Trigger a connection via UI (the foreground service is non-exported and cannot be started via adb):
```bash
adb -s <device> shell am force-stop com.ochelper.debug
adb -s <device> logcat -c
adb -s <device> shell am start -n com.ochelper.debug/com.ochelper.ui.MainActivity
adb -s <device> shell input tap 479 1016   # "Node" bottom-nav tab
adb -s <device> shell input tap 492 370    # "连接" connect button
```

Verify success:
```bash
adb -s <device> logcat -d -s OCNodeClient | grep -iE "Connected\. sessionId|onClosing|pairing|tick"
docker exec <container> openclaw devices list      # device listed as role "node"
docker logs --since 12s <container> | grep -c "closed before connect"   # expect 0
```
Success looks like: `Connected. sessionId=...` with caps registered, periodic `Gateway event: tick`, UI shows "● 已连接 (session: ...)", and zero gateway closures.

## Troubleshooting Map
| Symptom (gateway / device log) | Cause | Fix |
|---|---|---|
| `closed before connect` code=1006, instant | `Authorization` header sent / stale socket flap | Remove auth HTTP header; ensure `ws.cancel()` in finally |
| `protocol mismatch` | min/max protocol outside server's version | `minProtocol=3`, `maxProtocol=4` (match running gateway) |
| `1008 device signature expired` | `signedAt` from skewed device clock | Use challenge `payload.ts` as `signedAt` |
| `1008 pairing required` | device not approved | `openclaw devices approve <requestId>` |
| `/client/id must be equal to one of allowed values` | wrong client id | `client.id="openclaw-android"`, UUID goes in `client.instanceId` |
| nothing happens after `am force-stop com.ochelper` | wrong package | use `com.ochelper.debug` |
| `TIMEOUT: node invoke timed out` (node logs `event: node.invoke.request`) | node ignores invoke events / replies wrong frame | Handle `node.invoke.request`; reply `req method node.invoke.result` echoing `nodeId` (see 2a) |
| command missing from `nodes describe` despite being declared | non-canonical command name dropped | Map to canonical caps/commands (see 2b) |
| `node command not allowed: "camera.snap" ... allowlist` | dangerous command not opted-in / not re-paired | Add to template `gateway.nodes.allowCommands`, restart, **re-pair** (see 2c) |
| `invalid camera.snap payload` | wrong result schema | Return `format/base64/width/height` (see 2d) |
| `battery_level: -2147483648` | `BATTERY_PROPERTY_CAPACITY` unsupported | Fall back to `ACTION_BATTERY_CHANGED` (see 2e) |
| `gateway.nodes` config reverts on restart | entrypoint copies template over live config | Edit `/opt/openclaw-env/openclaw.json`, then `docker restart` (see 2c) |

## Reference Implementation (newer source; protocol numbers may differ)
- `/home/yaoxin/share/openclaw/apps/android/app/src/main/java/ai/openclaw/app/gateway/GatewaySession.kt` (handshake)
- `.../gateway/DeviceIdentityStore.kt`, `.../gateway/DeviceAuthPayload.kt`
- `.../node/ConnectionManager.kt` (connect options)
