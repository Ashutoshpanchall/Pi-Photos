# PiPhotos — Manual Device Smoke-Test Checklist

This checklist defines the manual verification procedure for **PiPhotos Android App** paired with a local **PiPhotos Storage Node** running on a PC or Raspberry Pi (Zero 2 W / 4 / 5).

---

## 0. Prerequisites & Setup

- [ ] **Node Server Running**:
  - Run the server locally on your PC or Raspberry Pi:
    ```bash
    cd server
    python -m venv venv
    venv\Scripts\activate  # Windows: venv\Scripts\activate, Linux: source venv/bin/activate
    pip install -r requirements.txt
    python run.py
    ```
  - Verify mDNS announcement in startup banner:
    `[INFO] mDNS advertised: _piphotos._tcp. local port 8443`
  - Open a pairing window on the server:
    ```bash
    python -m server.cli pair open
    ```
    Note the printed **6-digit pairing code** and certificate **SHA-256 fingerprint**.

- [ ] **Android Device**:
  - Connected to the **same local Wi-Fi network** as the server.
  - Install debug build on device:
    ```bash
    ./gradlew installNasSyncDebug
    ```
  - Monitor logcat during testing:
    ```bash
    adb logcat -v time -s PiPhotos:D PiHttpClient:D SyncRepository:D MediaSyncWorker:D PiDeviceDiscovery:D
    ```

---

## Smoke Test Matrix

| # | Test Scenario | Steps | Expected Result | Status |
|---|---|---|---|---|
| **1** | **Fresh Install & Permissions** | 1. Clear app storage or install fresh APK.<br>2. Open app.<br>3. Grant photo/video permissions. | App boots cleanly without crash; local photo grid renders with 120Hz smooth scrolling; no media is marked as backed up yet. | [ ] PASS |
| **2** | **Node Discovery (mDNS & Subnet Probe)** | 1. Navigate to **Backup** tab.<br>2. Observe discovery UI state. | Scanning indicator shows 10-second countdown; resolves the storage node via mDNS or subnet `/24` probe; shows node card with name and LAN IP. Never an infinite spinner. | [ ] PASS |
| **3** | **Live 6-Step Pairing & Fingerprint Pinning** | 1. Tap the discovered node card.<br>2. Enter the active 6-digit pairing code.<br>3. Tap "Pair Device". | Step-by-step progress ticks: (1) Resolve, (2) TCP connect, (3) Identity check, (4) Certificate fingerprint pin (TOFU), (5) Code auth, (6) Free space check.<br>Pairing completes; Persistent connection chip shows **Connected**. | [ ] PASS |
| **4** | **Backup 20 Photos + 1 Video (Real Names & Extensions)** | 1. Go to "Manage Folders" and ensure Camera / DCIM is checked.<br>2. Tap "Back Up Now" (20 photos + 1 video).<br>3. Observe UI progress and inspect server disk. | 1. Upload starts within 2–3 seconds (lazy hash pipelining).<br>2. Circular progress ring shows transfer speed (e.g. 12 MB/s) and ETA.<br>3. Server stores files in human-readable layout:<br>`<storage_root>/users/<user>/devices/<device>/<folder>/<real_name>.<ext>`<br>4. Verified real DISPLAY_NAME and MIME-derived extensions (`.heic`, `.jpg`, `.mp4`).<br>5. Photo grid thumbnails show backed-up cloud checkmark badges. | [ ] PASS |
| **5** | **Force Kill Mid-Upload & Resumable Upload** | 1. Queue a large video upload (e.g. 50 MB - 100 MB).<br>2. Mid-transfer (at ~50%), force stop the app:<br>`adb shell am force-stop com.example.piphotos`<br>3. Confirm server has `.part` file with partial bytes.<br>4. Re-open app and tap "Back Up Now". | Client sends `Upload-Offset: <bytes>` matching the `.part` size; server resumes streaming from that exact offset without 409 mismatch; file finishes and is moved atomically to storage root. | [ ] PASS |
| **6** | **Server Offline & Error Handling** | 1. Stop server process (`Ctrl+C` or `systemctl stop piphotos`).<br>2. Open app or tap "Back Up Now". | 1. Connection chip updates to **Unreachable (last seen X min ago)**.<br>2. Readable error banner appears ("Server unreachable" or "Connection refused").<br>3. UI shows "Retry" button without crashing or hanging. | [ ] PASS |
| **7A**| **Negative: Wrong IP Address** | 1. In manual node entry, enter non-existent IP (e.g. `192.168.1.254:8443`).<br>2. Tap "Test Connection". | TCP connect step fails with red cross and clear message ("Connection timed out" or "Host unreachable"); node is **not** saved. | [ ] PASS |
| **7B**| **Negative: Wrong Pairing Code** | 1. In pairing dialog, enter wrong code (e.g. `000000`).<br>2. Tap "Pair". | Validation fails at step 5 with "Invalid pairing code"; server logs failed attempt; code locks out after 5 failures. | [ ] PASS |
| **8** | **Wi-Fi With Cellular Mobile Data Active** | 1. Turn ON cellular mobile data (4G/5G).<br>2. Keep Wi-Fi connected to the LAN node.<br>3. Run backup or ping status. | Requests bind via `getActiveWifiNetwork()` socket factory; transfer completes over Wi-Fi without attempting to route private LAN IP over mobile data. | [ ] PASS |

---

## Detailed Step-by-Step Instructions

### Step 4: Verification of Upload Naming on Server
After running Test 4, inspect the server filesystem:
```bash
# On the server host:
ls -la server/storage/users/*/devices/*/*/
```
Verify:
1. File names match original phone camera names (e.g., `IMG_20240101_120000.jpg`, `VID_20240101_120500.mp4`, `PXL_20240101.heic`).
2. Slashes, colons, or illegal characters were safely sanitized.
3. Extensions match MIME types.
4. Duplicate files with identical hashes are stored once without duplicate disk bloat.

### Step 5: Verification of Chunked Resume
Run logcat to observe HTTP headers:
```bash
adb logcat | grep -E "Upload-Offset|uploadStreaming"
```
You should see:
- Initial attempt: `Upload-Offset: 0`
- Resumed attempt: `Upload-Offset: <partial_byte_offset>`
- Server response: `HTTP 200 OK` on completion.

---

## Sign-Off Gate
Before proceeding to Phase U1 (Multi-User Manager UI):
- [ ] All 8 manual test scenarios verified on physical device.
- [ ] No regression in zero-leak release build: `./gradlew assembleRelease verifyReleaseNoLeaks`.
- [ ] Unit tests pass: `./gradlew testDebugUnitTest`.
