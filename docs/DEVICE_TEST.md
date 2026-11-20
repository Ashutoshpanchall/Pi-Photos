# PiPhotos — Manual Device Smoke-Test Checklist

This checklist defines the manual verification procedure for **PiPhotos Android App** paired with a local **PiPhotos Storage Node** running on a PC or Raspberry Pi (Zero 2 W / 4 / 5).

---

## 0. Prerequisites & Setup

- [ ] **Node Server Running**:
  - Run the server locally on your PC or Raspberry Pi:
    ```bash
    # Linux / Raspberry Pi:
    ./server/run.sh

    # Windows (PowerShell):
    .\server\run.ps1
    ```
  - The server automatically sets up its `.venv`, installs requirements, generates TLS certificates, and prints the startup banner with the active 6-digit pairing code and certificate SHA-256 fingerprint:
    ```text
============================================================
  PiPhotos Storage Node — Home Vault
  Server ID: f0952fd6-9408-4d73-a611-e391bd844a06
  Listening: https://0.0.0.0:8443
  TLS fingerprint: FA:8F:3A:AB:AA:BE:03:CE:26:8F:59:99:1C:16:FF:29:E4:11:D6:4D:6F:F0:23:C4:9A:95:DE:3B:DA:67:F3:FC
  mDNS: ACTIVE (192.168.1.50)
  Pairing code: 533516  (expires in 10 minutes or 5 failed attempts)
============================================================
[SERVER_READY]
    ```
  - Note the printed **Pairing code** (e.g. `533516`) and **TLS fingerprint** from the console banner.

- [ ] **Android Device**:
  - Connected to the **same local Wi-Fi network** as the server.
  - Install debug build on device:
    ```bash
    ./gradlew installDebug
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
| **9** | **Owner Role & Users & Storage Overview** | 1. Open Profile tab on owner device.<br>2. Observe "Users &amp; Storage" entry.<br>3. Tap "Users &amp; Storage". | Owner sees "Users &amp; Storage" row; opens dashboard showing node total, free, reserved, and allocated quotas with color-coded bar; lists all registered users with role pills and storage usage. | [ ] PASS |
| **10**| **Create Member & 8-Char Invite Code** | 1. On "Users &amp; Storage" screen, tap "Add User".<br>2. Enter display name (e.g. "Bob").<br>3. Select quota preset (50 GB) or custom.<br>4. Tap "Generate One-Time Invite". | Server issues 8-character unambiguous invite code (e.g. `L3T5YYLF`) and QR code; 24-hour countdown displayed; "Share Invite" opens Android system share sheet with `piphotos://pair` link. | [ ] PASS |
| **11**| **Member Joining Flow & Storage Isolation** | 1. On a second device, open PiPhotos Backup -> Connect.<br>2. Enter server IP and the 8-character invite code.<br>3. Complete live verification.<br>4. Upload photos from member device. | Device pairs successfully to member account; Backup screen shows "Joined as Bob · X GB of Y GB used"; member profile does NOT show "Users &amp; Storage"; files are stored isolated under `users/<member-slug>/devices/`. | [ ] PASS |
| **12**| **Over-Allocation Banner** | 1. Set member quotas whose sum exceeds physical disk size.<br>2. Refresh Users &amp; Storage dashboard. | Amber warning banner appears at the top: "Quotas exceed disk space by X GB. Backups may stop early." | [ ] PASS |
| **13**| **User Detail, Rename & Quota Adjustment** | 1. Tap member card to open User Detail.<br>2. Tap "Rename User" and change display name.<br>3. Tap "Modify Storage Limit".<br>4. Attempt lowering quota below current used bytes. | Name updates in real-time; lowering quota below used bytes triggers warning dialog with "Force" option; server enforces `?force=true`. | [ ] PASS |
| **14**| **Device Revocation & Safe Deletion** | 1. In User Detail, observe Paired Devices list.<br>2. Tap "Revoke" on a device.<br>3. Tap "Remove User from Node".<br>4. Observe permanent delete checkbox. | Revoked device immediately receives 401 on next request; Delete dialog offers "Delete backed-up files permanently" checkbox (default unchecked: archives to `<storage_root>/_removed/`); last active owner cannot be deleted. | [ ] PASS |
| **15**| **Pair Another Device (Owner 10-Min Window)** | 1. On Users &amp; Storage screen, tap "Pair Device".<br>2. Observe dialog. | Server opens pairing window without restart; displays fresh 6-digit code with live 10-minute countdown timer; code expires or locks out after 5 failed attempts. | [ ] PASS |

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

## Phase U3: Member Experience & Quota Device Tests

### Scenario 9: Member Quota Warnings (80% Amber, 90% Red)
- Pre-requisites: Log in as a member with 100MB quota. Upload files until usage reaches 82MB.
- Steps:
  1. Open Backup screen. Observe usage ring.
  2. Verify usage ring is colored amber with "82 MB of 100 MB used" and "18 MB free".
  3. Upload further until 92MB used. Observe usage ring turns red.
  4. Verify warning banner appears: "You've used 90% of your space".
  5. Tap "Ask Owner": verify Android system share sheet opens with pre-composed request text.
  6. Verify low-importance system notification "Storage space alert (90%)" is posted.

### Scenario 10: Storage Full (507 Quota Exceeded) Clean Pause
- Pre-requisites: Member has 100MB quota and 98MB used. Attempt to upload a 5MB photo.
- Steps:
  1. Trigger backup sync.
  2. Verify pre-flight check blocks upload before pushing partial payload OR server returns 507 quota_exceeded.
  3. Verify backup stops cleanly without crashing or infinite retry loop.
  4. Verify ledger status marks item `PAUSED_QUOTA` and preserves chunk offset.
  5. Verify red banner displays: "Storage is full. Backups are paused." with "Retry now" and "Ask owner" buttons.

### Scenario 11: Auto-Resume Upon Quota Expansion
- Pre-requisites: Device is in PAUSED_QUOTA state from Scenario 10.
- Steps:
  1. On server or owner device, raise member quota to 200MB.
  2. On member device, either tap "Retry now" or allow app resume / 10-minute refresh to run.
  3. Verify status automatically transitions to OK (<80%).
  4. Verify `PAUSED_QUOTA` items move to `PENDING` and sync resumes automatically from previous offset.
  5. Verify notification "Storage available - Backups resumed" is dispatched.

### Scenario 12: Account Disabled (403) & Device Revoked (401)
- Steps:
  1. In admin panel, toggle member account status to disabled.
  2. On member device, attempt sync or status refresh.
  3. Verify banner: "Your account has been turned off by the owner. Backups are stopped."
  4. In admin panel, revoke member device.
  5. Verify banner: "This phone was removed from the server." with "Pair again" button.
  6. Tap "Pair again" and verify stored token and fingerprint for the node are cleared and app routes to pairing screen.

### Scenario 13: Browse Server Files & Download
- Steps:
  1. From Backup screen, tap "Browse Server Files".
  2. Verify list of own device folders and file list (paged, 100 per page) with thumbnail, name, size, date taken.
  3. Tap download icon on a photo.
  4. Verify file is downloaded and inserted into MediaStore `Pictures/PiPhotos`.
  5. Check gallery app to confirm photo is visible locally.

---

## Sign-Off Gate
Before proceeding:
- [ ] All manual test scenarios verified on physical device.
- [ ] No regression in zero-leak release build: `./gradlew assembleRelease verifyReleaseNoLeaks`.
- [ ] Unit tests pass: `./gradlew testDebugUnitTest`.
