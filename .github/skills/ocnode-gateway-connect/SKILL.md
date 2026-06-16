---
name: ocnode-gateway-connect
description: 'Diagnose and fix OCHelper OC Node connection failures to an OpenClaw Gateway (WebSocket protocol v4 handshake). USE WHEN: the OC Node will not connect, stays "未连接"/disconnected, or the gateway logs show "closed before connect" (1006), "device signature expired" (1008), "protocol mismatch", or "pairing required". Covers the v4 connect.challenge handshake, Ed25519 device signing, clock-skew handling, device pairing approval, and build/deploy/verify steps.'
argument-hint: 'e.g. "OC node cannot connect to gateway"'
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

## Reference Implementation (newer source; protocol numbers may differ)
- `/home/yaoxin/share/openclaw/apps/android/app/src/main/java/ai/openclaw/app/gateway/GatewaySession.kt` (handshake)
- `.../gateway/DeviceIdentityStore.kt`, `.../gateway/DeviceAuthPayload.kt`
- `.../node/ConnectionManager.kt` (connect options)
