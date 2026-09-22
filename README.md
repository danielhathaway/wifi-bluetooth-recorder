# Wi-Fi & Bluetooth Recorder

An Android application that records nearby Wi-Fi scan results and discoverable/classic Bluetooth devices approximately every five seconds, together with the phone's most recent GPS location and an ISO-8601 timestamp.

## Important Android limitations

The application requests a scan every five seconds, but Android and individual phone vendors can throttle or reject scans. The five-second interval is therefore a requested sampling interval, not a guarantee that a new radio scan will occur every five seconds.

Wi-Fi scan results can also be cached by the operating system. Bluetooth classic discovery only reports devices that are discoverable through that discovery mechanism. This application does not attempt to bypass Android radio, privacy, or permission controls.

On Android 12 and later, Bluetooth scan/connect permissions are runtime permissions. Location permission is required for Wi-Fi scan results. The application uses a foreground service while recording so recording can continue while the app is not visible.

## What is recorded

Each output line is one JSON object. The file is JSON Lines (JSONL), which is convenient for long-running recordings because records can be appended without rewriting the entire file.

Example Wi-Fi record:

```json
{"type":"wifi","location":"34.0000000,-118.0000000","time":"2026-09-22T17:00:00Z","data":{"bssid":"aa:bb:cc:dd:ee:ff","ssid":"Example","mode":"unknown","chan":36,"rate":"80","signal":-55,"security":"WPA2"}}
```

Example classic Bluetooth record:

```json
{"type":"bluetooth","location":"34.0000000,-118.0000000","time":"2026-09-22T17:00:00Z","data":{"name":"Example Device","mac":"11:22:33:44:55:66","class":"1032"}}
```

Example BLE advertisement record:

```json
{"type":"bluetooth_le","location":"34.0000000,-118.0000000","time":"2026-09-22T17:00:00Z","data":{"name":"Example BLE Device","mac":"11:22:33:44:55:66","rssi":-62,"tx_power":-4,"manufacturer_data":{"76":"0215..."},"service_uuids":["0000180d-0000-1000-8000-00805f9b34fb"],"service_data":{"00001816-0000-1000-8000-00805f9b34fb":"..."}}}
```

### Fields

- `type`: `wifi`, `bluetooth`, or `bluetooth_le`
- `location`: latitude,longitude in decimal degrees
- `time`: UTC ISO-8601 timestamp
- Wi-Fi:
  - `bssid`: access point BSSID, when supplied by Android
  - `ssid`: network name
  - `mode`: currently `unknown` because Android's public `ScanResult` API does not expose the AP mode in a portable way
  - `chan`: channel derived from the scan frequency
  - `rate`: channel-width value exposed by the Android API
  - `signal`: RSSI in dBm
  - `security`: basic classification from the scan capabilities
- Bluetooth:
  - `name`: device name, when Android makes it available
  - `mac`: Bluetooth address, subject to Android permissions and device behavior
  - `class`: Bluetooth device class integer, when available
- Bluetooth Low Energy:
  - `name`: BLE advertisement/scan-record name, when present
  - `mac`: BLE advertiser address
  - `rssi`: received signal strength in dBm
  - `tx_power`: transmitted power when supplied, otherwise `null`
  - `manufacturer_data`: manufacturer/company ID mapped to hexadecimal advertisement bytes
  - `service_uuids`: advertised service UUIDs
  - `service_data`: service UUID mapped to hexadecimal service-data bytes

## Output file

The application writes:

```text
network-records.jsonl
```

to public shared storage accessible by user file managers:

```text
/storage/emulated/0/Documents/WifiBluetoothRecorder/network-records.jsonl
```
*(or `/storage/emulated/0/Download/WifiBluetoothRecorder/network-records.jsonl` depending on storage permissions)*.

The main screen displays the exact output file path on your device. You can also tap **Export recording file** at any time to export a copy of the recording to any location (Downloads, SD card, Drive, etc.) using the Android Storage Access Framework system file picker.

## Requirements

- Android Studio with an Android SDK that includes API 35.
- JDK 17.
- A physical Android device is strongly recommended. Wi-Fi and Bluetooth scanning are generally not useful on an emulator.
- Location services enabled on the device.
- Wi-Fi and Bluetooth hardware enabled when their respective data is required.

## Build

1. Open this directory in Android Studio.
2. Allow Android Studio to download the Gradle and Android dependencies.
3. Make sure JDK 17 is selected for Gradle.
4. Connect an Android device with USB debugging enabled, or create an appropriate physical-device run configuration.
5. Build with **Build > Make Project**, or from a terminal using the included Gradle wrapper:

```bash
./gradlew assembleDebug
```

> **Note:** If both `ANDROID_PREFS_ROOT` and `ANDROID_USER_HOME` environment variables are set in your environment, Android Gradle Plugin may fail with an `AndroidLocationsException`. You can run `./gradlew` with `ANDROID_PREFS_ROOT` unset:
> ```bash
> env -u ANDROID_PREFS_ROOT ./gradlew assembleDebug
> ```

The debug APK will be under:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install

From Android Studio, press **Run** and select the connected device.

Or with ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Use

1. Launch **Wi-Fi & Bluetooth Recorder**.
2. Press **Start recording**.
3. Grant the requested location and Bluetooth permissions.
4. Grant notification permission on Android versions that request it.
5. Leave the recording service running. A persistent notification indicates that recording is active.
6. While recording, the **Nearby BLE devices** section shows the latest RSSI observed for each BLE advertiser.
7. Press **Stop recording** when finished.
7. The collected records remain in `network-records.jsonl`.

The file is appended to between recording sessions. Starting a new session does not automatically erase the previous data.

## Permissions

The application declares:

- Fine/coarse location: required for location and Wi-Fi scan results.
- Bluetooth scan/connect: required on Android 12+ for classic Bluetooth discovery and BLE advertisement scanning/device information. The BLE scan permission is intentionally not marked `neverForLocation` because BLE observations are recorded together with GPS coordinates.
- Legacy Bluetooth permissions: used on Android 11 and earlier.
- Foreground service/location: allows the recording service to continue while the UI is not visible.
- Notifications: used for the foreground-service notification on Android 13+.

## Privacy

The recorded file can contain identifiers such as Wi-Fi BSSIDs, SSIDs, Bluetooth addresses, and GPS coordinates. Treat the output as sensitive location/network-observation data and protect or delete it as appropriate.

The application does not upload the recording to a server. The data remains on the device unless the user copies it elsewhere.

## Troubleshooting

### No Wi-Fi records

Check that:

- Location permission is granted.
- Device location services are enabled.
- Wi-Fi is enabled.
- The device permits Wi-Fi scans.
- Android has not throttled scans.

Android may return previously cached results rather than a fresh scan.

### No BLE records

Check that:

- Bluetooth is enabled.
- Bluetooth scan permission is granted.
- The device is transmitting BLE advertisements.
- The phone permits BLE scanning while the foreground service is running.

BLE scanning reports advertisements rather than a complete inventory of nearby devices. A device may randomize its address, omit a name, or advertise intermittently.

### No Bluetooth records

Check that:

- Bluetooth is enabled.
- Bluetooth scan/connect permissions are granted.
- Nearby devices are discoverable through classic Bluetooth discovery.

Many modern Bluetooth Low Energy devices are not reported by classic discovery. Supporting BLE advertisements requires a separate `BluetoothLeScanner` implementation.

### Location is missing

The recorder waits for a usable location fix. Indoor devices may take time to obtain a fix. The recording cycle is not written until a location is available.

## Extending the project

Potential next additions include:

- BLE advertisement scanning with service UUIDs and manufacturer data.
- An in-app file browser/export action using the Android Storage Access Framework.
- CSV export.
- A map view of recorded observations.
- Per-session output files.
- A setting for the scan interval.
- A database-backed storage layer for very large recordings.
