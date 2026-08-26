# Google Drive backup sync — setup guide

StreamVault can sync its backup file to a private folder of your Google Drive
(scope `drive.appdata`). The folder is owned by the app, **invisible** in the
standard Drive UI, and **wiped automatically** when the user uninstalls the app.

This page targets **maintainers and contributors** who want to build/debug the
feature locally. End users don't need to do anything — they just sign in from
*Settings → Backup & Restore → Google Drive sync*.

## Why per-build setup is required

The Google Sign-In Android SDK authenticates a running app by matching the
pair `(packageName, signing certificate SHA-1)` against the **OAuth client IDs**
registered in a Google Cloud Console project. There is **no client secret in
the code** — every fork/maintainer/release channel must register its own pair.

That means:

| Build flavour | Needs its own OAuth client | Why |
|---|---|---|
| `master` debug, local on dev machine | yes | each developer signs with their own debug keystore (different SHA-1) |
| Official release on Play Store | yes | Davidona's release key SHA-1 |
| CI build | yes (or share the dev one) | depending on signing strategy |

The code in `data/manager/GoogleDriveBackupSyncManager.kt` never references an
OAuth client ID — it just asks Play Services for the locally-installed client
matching its `(packageName, SHA-1)`. If no client is registered, sign-in fails
silently with `ApiException` status `DEVELOPER_ERROR (10)`.

## One-time setup (Google Cloud Console)

### 1. Create a Cloud project

Open <https://console.cloud.google.com/projectcreate> and create a project of
your choice (e.g. `streamvault-dev-<your-handle>`). Select it.

### 2. Enable the Google Drive API

<https://console.cloud.google.com/apis/library/drive.googleapis.com> →
**Enable**.

### 3. Configure the OAuth consent screen

<https://console.cloud.google.com/apis/credentials/consent>

- **User type** → External
- **App name** → `StreamVault` (or your fork name)
- **User support email** + **Developer contact** → your email
- **Scopes** → add `https://www.googleapis.com/auth/drive.appdata`
- **Test users** → add the Google account(s) you'll sign in with on the device

While the project stays in *Testing* state, only the listed test users can
sign in (hard cap: 100 accounts). That's fine for development. To open the
flow to all users, see the publishing section below.

### 4. Create the OAuth Android client(s)

<https://console.cloud.google.com/apis/credentials> → **Create credentials** →
**OAuth client ID** → **Android**.

| Field | Value |
|---|---|
| Name | `StreamVault — debug (<your handle>)` |
| Package name | `com.streamvault.app` |
| SHA-1 certificate fingerprint | from `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android` |

Repeat the step for every signing certificate that will run the feature
(release key, CI key, …). You don't need to copy any returned ID anywhere —
the runtime resolves it transparently.

### 5. Add a Google account to the test device

The device or emulator that runs the build must have a Google account added
in **Settings → Accounts**, and that account must be in the OAuth consent
screen's *Test users* list.

### 6. Publish the app (open the flow to all users)

This step is **optional** if you only need maintainer access. It is **required**
to let arbitrary end users sign in (Play Store distribution, public release APK,
etc.).

The good news: `drive.appdata` belongs to Google's **non-sensitive scope**
category — [official list](https://developers.google.com/identity/protocols/oauth2/scopes#drive)
shows it without the *(Sensitive)* or *(Restricted)* tag. Concrete consequence:
**no verification process, no security assessment, no waiting period**. You can
flip the project from *Testing* to *In production* immediately and unknown users
will be able to sign in right after.

Walkthrough (matches what I went through yesterday on the real project):

1. Open the OAuth consent screen page
   (<https://console.cloud.google.com/apis/credentials/consent>).
2. Under the project status pill (currently *Testing*), click **Publish app**.
3. A confirmation dialog appears titled *Push to production?* — confirm.
4. The pill flips to *In production*. The page now states
   *"This OAuth client is now ready to be used by any user with a Google
   account. Verification is not required for this app."*
5. Done. New users (outside the *Test users* list) can sign in immediately.

Notes:

- The *Test users* list becomes irrelevant in production — sign-ins are no
  longer gated by it.
- Refresh tokens issued by *Testing* projects expire after 7 days; in
  production they don't (matches our `GoogleAuthUtil.getToken` per-call
  strategy, where this is mostly invisible to the user).
- If you ever add a *sensitive* or *restricted* scope later (e.g. full
  `drive`), then verification kicks in — but as long as the only scope is
  `drive.appdata`, you stay on the fast path.

## Verifying the setup

1. Install the debug APK.
2. *Settings → Backup & Restore → Google Drive sync → Connect to Google Drive*.
3. Pick the test user account in the system picker. The consent screen must
   ask only for the `drive.appdata` scope — if it asks for more (or fails
   with "Developer error"), you have a SHA-1 / package name mismatch.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Sign-in dialog closes immediately, `ApiException(10)` in logcat | OAuth Android client missing or wrong SHA-1 / package name |
| Sign-in succeeds but push/pull errors with `drive_auth_failed` | Drive API not enabled, or scope not granted in consent screen |
| `Google Play Services required` snackbar | Emulator built with AOSP images (no GMS). Use a "Google APIs"/"Google Play" emulator image |
| Push works, pull says `drive_no_remote_backup` | Drive `appDataFolder` is empty for this account — push first |

## Privacy notes

- New pushes use timestamped v2 artifacts named like
  `streamvault_backup_bundle_20260814_120000_123_a1b2c3d4.json`. Each contains the
  exact current `BackupManager` JSON export text and the matching
  provider-credentials snapshot. The app keeps the ten newest timestamped
  bundles and lets the user choose a snapshot before preview/import. Pull also
  accepts the original fixed-name v2 bundle, existing v1 bundles, and older
  standalone backup/credentials files as read-only migration fallbacks.
  Preserving the exported text keeps checksum verification compatible with
  backups produced by older release builds. New local and Drive exports carry
  the matching credentials field so a restored provider can sync immediately;
  older backups without that field still require password re-entry.
- Auth tokens fetched via `GoogleAuthUtil.getToken` are never logged.
- The scope `drive.appdata` cannot read files outside the app's private folder.

## Managing saved Drive backups

Open **Settings → Backup & Restore → Manage Drive backups** to see the saved
snapshots in newest-first order. The app asks for confirmation before deleting
one. It validates the selected snapshot against the current `appDataFolder`
listing, then refreshes the list after deletion. This prevents a stale screen
from deleting an unrelated Drive file.

For older installations that have a legacy standalone
`streamvault_backup.json`, deleting that backup also attempts to remove its
legacy `streamvault_credentials.json` companion. Current timestamped v2 bundles
are self-contained, so only the selected bundle is deleted.

## Credentials storage (fork extension, M3)

Each timestamped `streamvault_backup_bundle_*.json` carries the configuration plus
its matching credentials snapshot in one versioned artifact. The current bundle
stores the backup JSON text unchanged so the main backup checksum remains
verifiable after Drive round-tripping. Older installations may still
have a **second sibling file** in the same `appDataFolder`:

- **`streamvault_credentials.json`** — `[{serverUrl, username, password}]`,
  cleartext.

Why cleartext in the bundle on Drive:

- The file is invisible to the OS and to the standard Drive UI (the
  `drive.appdata` scope is the only path that can read it).
- Access is gated by the user's Google account + OAuth consent.
- It is purged automatically when the app is uninstalled.
- Cross-device-by-design — a per-device Keystore key would defeat the
  purpose (the file would be unreadable on a fresh install, which is
  precisely the use case it solves).
- A passphrase-protected variant was considered and rejected: it adds
  significant UX friction (user must remember a passphrase across
  devices) for a small additional security margin given the threat
  model above.

The local DB still stores credentials encrypted via
`AndroidKeystoreCredentialCrypto`. Cleartext also exists in new local backup
files and in the Drive `appDataFolder`; during import it is applied to the
restored provider and then kept only in the encrypted local DB.

Matching is by `(serverUrl, username)` so provider IDs reshuffled by the SAF
import do not break the restore. Providers present locally but absent from a
credential snapshot are left untouched.

Pre-bundle backups (no v2 bundle in `appDataFolder`) are handled gracefully: the
pull path lists and falls back to the legacy standalone backup file;
`pullCredentials` returns an empty list when no legacy credentials file exists, and
the import path restores providers without passwords so the user can re-enter them
manually.
