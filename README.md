# Torque Shipper

Torque Shipper is an Android companion for [Torque Pro](https://wiki.torque-bhp.com/view/PluginDocumentation). While shipping is running, it collects a fresh JSON snapshot at the configured cadence, queues it in app-private storage, and POSTs queued snapshots to your server. The app needs Torque running on the **same Android device**; it does not connect directly to an OBD adapter or provide a receiving server. Available readings depend on Torque, the adapter, and the vehicle/ECU.

## Build and install

1. Install a JDK compatible with Android Gradle Plugin 8.9.2 (JDK 17), Android SDK platform 35 and the Android SDK build tools. Set `ANDROID_HOME` to your SDK directory. This project includes a Gradle wrapper; an installed Gradle is not required.
2. From the repository root, build the debug APK:

   ```sh
   ANDROID_HOME=/path/to/Android/Sdk ./gradlew assembleDebug
   ```

3. Install `app/build/outputs/apk/debug/app-debug.apk` onto an Android 8.0 (API 26) or newer device, for example with `adb install app/build/outputs/apk/debug/app-debug.apk` while USB debugging is enabled. Install and open Torque Pro on that same device. Pair/configure your OBD adapter in Torque, connect it to the vehicle, and confirm Torque itself shows live readings before expecting them here. Torque must already be running: the shipper binds to its service but does not launch Torque.

An emulator without Torque and a connected vehicle cannot establish live sensor or DTC behavior. This README does not claim a live-vehicle test.

## Configure and run

1. Prepare an HTTP receiver reachable **from the phone's network**, not merely from your development computer. Configure it to accept a JSON `POST` at a URL such as `https://example.com/api/torque` and authenticate the key in your selected request header. Use HTTPS wherever possible.
2. In Torque Shipper enter the complete **POST endpoint URL** (`https://` preferred; `http://` permitted), a nonempty printable-ASCII **API key**, an **API key header name** (default `X-API-Key`; choose another valid, non-reserved HTTP header name if your server requires it), and the **sample interval** in whole seconds (`1`–`86400`; default `5`). Optionally enter a **JSON template** to wrap each snapshot as described below; leave it blank to send the existing snapshot body unchanged. URL credentials and fragments are not supported. Tap **Save settings** or **Start shipping** (which saves first).
3. If Torque prompts for plugin permissions, grant the permissions needed for its API and raw OBD commands. Raw DTC reads use Torque's `sendCommandGetResponse`; their availability remains subject to Torque's permissions and your ECU. On Android 13+, respond to the notification-permission prompt so the ongoing notification remains visible.
4. Tap **Start shipping** while the app is open. Collection runs as a `dataSync` foreground service with an ongoing notification; leaving the screen does not itself stop it. The screen/notification show whether Torque reports ECU connected, the queued-document count, and (on the screen) the last collection error class, if any. **Stop shipping** on the screen or **Stop** on the notification ends the collection run. A disconnected indication means live ECU data is unavailable; check Torque, its connection and plugin permissions. A growing queued count suggests delivery is not succeeding; check network, receiver, URL, header, key, and HTTP response. Network failures may not display as a last-tick error.
5. To change settings while running, save them, **stop and restart shipping**. The current run uses its start-time settings, including the JSON template; previously queued documents retain their already-rendered bodies, original destination, and credentials even after a settings change. Stopping does not delete queued documents; they are retried on a later run.

### Wi-Fi delivery

Enable **Wait for Wi-Fi before sending** to keep collecting and persisting each JSON document while Wi-Fi is unavailable; HTTP delivery resumes on an eligible connected Wi-Fi network. Leave the optional **Wi-Fi network name** empty for any Wi-Fi, or enter an exact, case-sensitive SSID to restrict delivery to that network. **Use connected Wi-Fi** fills the field from the phone's current network. An SSID can also be entered while disconnected. A Wi-Fi connection must be the phone's active network; cellular and VPN connections are not used for Wi-Fi-only delivery.

Selecting or matching an exact SSID needs precise location permission and enabled device Location services because Android restricts access to Wi-Fi names. If the name cannot be read, delivery waits rather than falling back to another network. Documents remain in the app-private queue across stops and are retried when shipping is started again; the service does not wake itself after stopping or an OS timeout. Changes to Wi-Fi settings during a run take effect after stopping and restarting shipping.

The cadence is a sampling configuration, **not** a guarantee of fresh ECU measurements or on-time network delivery. Network posting runs separately from collecting snapshots; slow Torque calls, OS scheduling, and receiver/network availability can affect observed timing. The service does not automatically restart after a process kill (`START_NOT_STICKY`); reopen the app and start shipping when appropriate.

**Android 15+ limit:** This app targets Android 15 and uses a `dataSync` foreground service. Android limits background execution of this foreground-service type to a total of **six hours per 24-hour period** for the app. At timeout the service stops collection; it cannot provide uninterrupted, unattended 24/7 collection on affected devices. Reopen the app to inspect status and restart when Android permits it; simply restarting cannot bypass an exhausted quota. See [Android's foreground-service timeout documentation](https://developer.android.com/develop/background-work/services/fgs/timeout).

## Receiver contract and snapshot format

Each collected tick creates its own JSON document (even when Torque/ECU is unavailable). Queued documents are sent individually, oldest first, as `POST` requests to the configured endpoint, with `Content-Type: application/json; charset=utf-8` and the selected API-key header, for example:

```http
POST /api/torque HTTP/1.1
Content-Type: application/json; charset=utf-8
X-API-Key: example-secret

{"timestampMs":1760000000000,"connected":true,"status":"connected","pids":[{"id":"010C","status":"available","active":true,"ecuSupported":true,"metadata":{"raw":"Engine RPM,RPM,rpm,8000,0,1","name":"Engine RPM","shortName":"RPM","unit":"rpm","max":8000,"min":0,"scale":1},"value":1250.0,"updateTimeMs":1760000000000}],"vehicleProfile":{"status":"available","raw":["Example car","2.0"],"fields":{"name":"Example car","engineDisplacementLiters":"2.0"}},"adapter":{"status":"available","dataErrorCount":{"status":"available","value":0},"pidReadSpeed":{"status":"available","value":12.5}},"dtcs":{"stored":{"status":"available","rawResponses":["43 01 33"],"codes":["P0133"]},"pending":{"status":"unavailable","rawResponses":[],"codes":[]},"permanent":{"status":"unavailable","rawResponses":[],"codes":[]}}}
```

This is **illustrative**, not a captured vehicle response or a promise that these readings will be present. `timestampMs` is snapshot time in Unix epoch milliseconds. `status` can be `connected`, `ecu_disconnected`, `torque_unavailable`, or `remote_error`; `connected` is the ECU connection boolean. `pids` contains Torque's discovered sensor IDs: available entries include finite `value` and `updateTimeMs` (Torque's last-update time); unavailable entries omit those fields and have a status such as `unavailable`, `remote_error`, `ecu_disconnected`, or `torque_unavailable`. `active`, `ecuSupported`, and `metadata` appear only if Torque supplied them. Metadata always retains Torque's CSV `raw` string when supplied; `name`, `shortName`, `unit`, `max`, `min`, and `scale` are extracted only when the CSV has the expected six fields and numeric fields parse. The catalog/profile may be cached between ticks; an available reading is Torque's last reported value, not necessarily a new bus read.

`vehicleProfile` has `status` (`available`, `unavailable`, `remote_error`, or `torque_unavailable`) and, if available, Torque's `raw` string array plus `fields` mapped by position: `name`, `engineDisplacementLiters`, `weightKilograms`, `fuelType`, `boostAdjustment`, `maxRpm`, `volumetricEfficiencyPercent`, `accumulatedDistance`. Those mapped values are **strings**, not normalized measurements. `adapter` has status and, when Torque is reachable, individual `{ "status": "available", "value": ... }` or `{ "status": "unavailable" }` metrics: `dataErrorCount`, `pidReadSpeed`, `configuredSpeed`, `protocolNumber`, `protocolName`, `fileLoggingEnabled`, `webLoggingEnabled`, `numberOfLoggedItems`. Values are numbers, strings or booleans as returned by Torque.

`dtcs.stored`, `dtcs.pending`, and `dtcs.permanent` correspond respectively to OBD modes **03, 07, and 0A**. Each has `status`, `rawResponses` (original Torque response strings), and `codes` (decoded DTC strings). The decoder only reports codes it can unambiguously parse: `available` means a recognized response, `partial` indicates a response with ambiguity, `unavailable` means no usable response, and `remote_error` means the call failed. When Torque is unavailable or the ECU disconnected, each category instead has `torque_unavailable` or `ecu_disconnected` status, respectively, with empty arrays. An empty `codes` array does **not** prove the vehicle has no faults: inspect `status` and `rawResponses`. ECU support, responses, plugin permission and Torque version determine what is returned.

### Optional JSON template

Leave the JSON template empty (or whitespace only) to POST the generated snapshot directly, as in the example above. Otherwise, enter a valid JSON **object** with exactly one string value `"{{document}}"` at any depth, including within nested objects or arrays. That value is replaced with the full generated snapshot as a JSON object, not an escaped JSON string. Other values remain JSON values of their original types; no other text interpolation occurs.

For example, configure:

```json
{"meta":{"fleet":"north","priority":2,"enabled":true},"payload":"{{document}}"}
```

For a generated snapshot `{"timestampMs":1760000000000,"connected":true}`, the resulting POST body is:

```json
{"meta":{"fleet":"north","priority":2,"enabled":true},"payload":{"timestampMs":1760000000000,"connected":true}}
```

Saving or starting rejects invalid JSON, a non-object root, or a template with a missing or duplicate placeholder. A changed template takes effect only after stopping and restarting shipping; documents already in the queue retain the body rendered when they were collected.

Your receiver should return **HTTP 200 OK** only after accepting the document. Only exactly 200 acknowledges delivery: redirects are not followed, and other responses (including other 2xx statuses) retain the document. Consider deduplicating by a server-side content fingerprint or application-specific ingest policy: the wire format has no separate event ID, and a replay can occur after an acknowledged POST if the client dies before deleting its queue entry.

## Delivery, retention, and security

Snapshots are persisted to an ordered app-private file queue before attempted delivery. The delivery worker sends up to eight oldest queued documents per flush, removing each only after an exact HTTP 200 response, and stops on the first unsuccessful entry; that retained head blocks all newer entries. During an active collection run, a failed drain with queued entries (or an unreadable queue) gets another delivery opportunity about every five seconds, independently of the sample interval; samples and eligible-network changes can also trigger earlier attempts. An empty queue does not trigger periodic retries. Wi-Fi-only delivery still waits for an eligible network, and these periodic attempts stop with the collection run, including explicit Stop and Android's foreground-service timeout; stopped runs do not wake themselves to retry. Queued entries include the snapshot body, the endpoint, the key, and the header name **as captured at enqueue time**. This is **at-least-once attempt/retry**, not exactly-once delivery: crashes, power loss, a lost response, or an acknowledged POST before durable deletion can cause duplicate POSTs. An unavailable service/network/receiver or a persistent bad entry can leave a growing queue. No automatic queue reset, expiration, or maximum retention policy is provided; manage receiver availability and device storage accordingly. Stop/start preserves pending entries, but uninstalling the app or clearing its data removes its private queue and settings.

Settings live in app-private SharedPreferences and the queue in app-private files; Android backup is disabled. These are **not encrypted at rest by this app**. HTTPS protects traffic in transit; plain HTTP is allowed but sends the API key and vehicle data **without encryption**, so do not use it on untrusted networks. Protect your endpoint and credentials; avoid placing secrets in the URL. The queued data and profiles may contain sensitive vehicle/location information depending on Torque's available PIDs.

## Torque API references

- [Torque's canonical plugin documentation](https://wiki.torque-bhp.com/view/PluginDocumentation), including TorqueScan. See also the author's [plugin receiver binding reference](https://github.com/piemmm/torque-theme-plugin/blob/main/app/src/main/java/org/prowl/themeexampleplugin/PluginReceiver.java).
- [Torque's published `ITorqueService.aidl`](https://torque-bhp.com/api/ITorqueService.aidl); this project's copy is at `app/src/main/aidl/org/prowl/torque/remote/ITorqueService.aidl`. Torque's published API and installed version govern available methods and data.
