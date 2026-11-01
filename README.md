# PocketDriver

PocketDriver records Wi-Fi observations, classic Bluetooth discovery, BLE advertisements, and location fixes in an encrypted SQLite database on the device. A foreground service continues recording while the screen is off or another app is open.

## Recording and history

1. Start a recording and grant precise location and Bluetooth permissions. Enable device location services and the radios you want to scan.
2. The scan summary shows elapsed time, committed Bluetooth records (classic plus BLE), fresh Wi-Fi observations, and estimated distance in meters. Wi-Fi observations retain every distinct timestamp for each BSSID, including results arriving out of order; only identical BSSID/timestamp results are counted once. Summary values remain visible after stopping until another recording begins.
3. Stop recording to finish the session. Observations commit continuously on a background writer, not in one save at the end. Ready radio observations are committed in small batches with their summary counters; location updates commit immediately. Signals can wait up to six seconds for a matching GPS update, with a one-second flush timer and a bounded queue. Stopping flushes only that short pending window.
4. Open **Previous recordings & maps**, select a recording, and see its geographic observation points. Zero coordinates are valid; observations without a usable location remain stored and exported but have no map point. The map includes location fixes collected between radio sightings.
5. Use **Export entire database as CSV**, or **Export this recording as CSV** from the map screen. The Android document picker chooses the destination. Exports include session metadata, observation rows, and empty sessions. A consistent prefix of each recording is streamed without blocking subsequent recording commits.

Location updates are requested every second. Fixes with accuracy worse than 50 meters or invalid coordinates are ignored. Wi-Fi and BLE observations are matched to GPS history using the signal's **measurement time**, not callback arrival time. Both radio timestamps and GPS fix timestamps use elapsed realtime since boot, so delayed/batched or out-of-order callbacks and wall-clock adjustments do not move a signal onto the current position.

A matching fix is used directly. Otherwise, coordinates are estimated by interpolation between fixes no more than five seconds either side of the measurement, or by the nearest fix within one second. These are labelled `exact`, `interpolated`, or `nearest` in `data_json`; even an exact timestamp match retains the GPS sensor's spatial uncertainty. Without a suitable fix, the observation is still saved with blank coordinates and `location_match=no_matching_fix`. Fix history is retained for the current recording so delayed signals can match earlier positions.

Classic Bluetooth `ACTION_FOUND` broadcasts do not expose a hardware measurement timestamp. Those records retain their callback time, have blank coordinates, and are explicitly labelled `time_source=callback` and `location_match=measurement_time_unavailable`. They must not be treated as measurement-time location samples.

Distance is the sum of great-circle distances between accepted chronological fixes, so GPS noise can overestimate travel and gaps can underestimate it. It is an estimate, not an odometer.

Android can throttle/reject Wi-Fi and Bluetooth scans. A Wi-Fi/classic discovery request is attempted every five seconds; BLE uses a continuous scan to avoid repeated-start throttling. Classic discovery is allowed to finish rather than being cancelled every five seconds. Hardware validation requires a physical device. If a save fails, recording stops and the main screen reports the error.

## Storage and encryption

The app-private `databases/pocketdriver.db` uses SQLCipher 4.10.0, including encrypted journal/WAL pages. A random database password is wrapped using AES-GCM with an Android Keystore key; only the wrapped value is stored in preferences. Backup and device transfer exclude the database and key material because the Keystore key cannot be restored on a different installation. Uninstalling the app removes access to its recordings, so export data you want to keep.

The `recordings` table stores session timestamps, committed counts, and distance. Every session has its own `recording_<id>` table, with columns `id`, `time`, `type`, `transport`, `latitude`, `longitude`, and `data`. Inserts and counter updates occur in the same transaction. On process restart, interrupted sessions end at their last commit. Previously committed records survive a crash; queued observations that have not committed can be lost.

The old public `network-records.jsonl` files are not altered or imported. New recordings no longer write to shared storage, and storage permissions are no longer needed.

## Bluetooth data model

Classic discovery and BLE scan callbacks represent different observations, even when addresses match. Classic discovery provides a device class; BLE provides advertisement RSSI, transmit power, manufacturer bytes, service UUIDs, and service data. BLE addresses can rotate, and a dual-mode device can appear in both scan mechanisms. Merging sightings by address would lose information and can incorrectly associate devices.

Both now use `type=bluetooth`, with `transport=classic` or `transport=le`. Shared fields are `name`, `mac`, and `rssi` (classic RSSI when supplied). Classic retains `class`; BLE retains `tx_power`, `manufacturer_data`, `service_uuids`, and `service_data`. Observations remain separate rows. JSON payloads are constructed with Android's JSON encoder to preserve arbitrary names and control characters.

Wi-Fi retains BSSID, SSID, channel, signal, security, unknown mode, Android channel width (`channel_width`, not a bitrate), and the original `scan_timestamp_us`. BLE also retains `scan_timestamp_ns`. Location rows contain accuracy in meters and `elapsed_realtime_ns`.

New radio payloads include `measurement_alignment_version=1`, `measurement_elapsed_ns`, `received_time_ms`, `received_elapsed_ns`, `time_source`, and `location_match`. Located signals also retain `location_before` and `location_after`, containing the original GPS coordinates, fix times, elapsed timestamps and accuracy estimates used in the match. This evidence is exported in the JSON column for later filtering or source-location analysis. Missing or invalid sensor timestamps never receive an invented measurement-time location.

Existing records are preserved as recorded. Older rows without `measurement_alignment_version` used processing-time GPS and cannot be corrected automatically.

## CSV format

UTF-8, quoted fields, escaped double quotes, CRLF record separators. Columns:

```text
recording_id,started_ms,ended_ms,distance_m,record_id,time_ms,type,transport,latitude,longitude,data_json
```

Timestamps are Unix milliseconds. For new Wi-Fi/BLE rows, `time_ms` is the measurement time derived from a fixed wall/elapsed clock anchor captured at service creation. Callback time is kept separately in `data_json.received_time_ms`. Location rows retain the fix's own UTC time. If a measurement timestamp is unavailable or invalid, the record uses callback time and is explicitly marked. Blank coordinates mean no reliable measurement-time location match. The final column contains the complete type-specific JSON payload, preserving nested BLE advertisement data. Empty sessions have a metadata-only row. CSV files are unencrypted and are written only to a destination chosen by the user.

## Maps and privacy

The app does not upload observation records. Viewing maps downloads OpenStreetMap tiles, which disclose the requested map area and network address to that tile service. OpenStreetMap attribution is shown on the map. Recorded points can be drawn without connectivity; background tiles need internet or an existing cache. Map tiles are cached privately in the app cache directory.

## Branding

The launcher label is **PocketDriver**. Its vector adaptive icon has separate full-bleed background and foreground layers on a 108dp canvas, with a pocket/road symbol inside the central 66dp safe zone and a monochrome layer for themed icons. The application ID remains `com.example.wifibluetoothrecorder` so this can update an existing installation.

## Build and test

Requirements: JDK 17, Android SDK API 35, Gradle 8.11.1 (wrapper JAR and configuration included). Point `ANDROID_HOME` to the SDK or set `sdk.dir` in an untracked `local.properties`.

```bash
gradle :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
# With a connected device/emulator:
gradle :app:connectedDebugAndroidTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

There were no tests in the original repository. JVM tests now cover CSV escaping, elapsed time, session table validation, distance/date-line math, Wi-Fi cache deduplication, channel mapping, security classification, and measurement-time GPS correlation (delayed/out-of-order signals and GPS fixes, interpolation, date-line handling, timing gaps, invalid timestamps, wall-clock changes, bounded buffering and stop-time flushing). Android instrumentation tests cover actual encrypted database transactions, session isolation, both Bluetooth transports, coordinate handling, full/single/empty exports, concurrent export/writes, persisted observations after reopen, interrupted-session recovery, wrong-key rejection, Keystore wrapping, matched-location/timing metadata surviving CSV export, and explicit handling of missing measurement timestamps.

For physical-device QA: start and stop multiple scans, reopen the app, confirm history and points, export one recording and all recordings via the document picker, deny/regrant permissions, disable radios, and confirm scanning resumes when they are enabled. Check background recording and compare observed signals with known nearby devices; an emulator cannot validate the radio data.

Dependencies: [SQLCipher for Android](https://github.com/sqlcipher/sqlcipher-android), [osmdroid](https://github.com/osmdroid/osmdroid), AndroidX, and Google Play Services Location. Adaptive icon design follows [Android's icon guidance](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive).

Measurement timestamp references: [Wi-Fi ScanResult.timestamp](https://developer.android.com/reference/android/net/wifi/ScanResult#timestamp), [BLE ScanResult.getTimestampNanos](https://developer.android.com/reference/android/bluetooth/le/ScanResult#getTimestampNanos()), and [Location.getElapsedRealtimeNanos](https://developer.android.com/reference/android/location/Location#getElapsedRealtimeNanos()).
