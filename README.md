# Claude Radar

A desk display for your Claude chats. An old Android phone (built for the Xiaomi Mi A3) shows whether each Claude Code chat is **working**, **needs you**, or **finished**, and vibrates or plays a sound when one changes. Recording mode keeps it silent.

```
Claude Code on Windows / Mac ──(background hooks)──► Supabase ──(live)──► Android app
```

| Screen colour | Meaning |
|---|---|
| Pulsing orange | A chat is waiting for your answer or permission |
| Red | A chat failed (API error, rate limit…) |
| Green | A chat finished and you haven't looked yet |
| Dim blue | Claude is working |
| Black | Nothing running |

Tap a chat to mark it seen. Long-press to remove it.

## Setup

### 1. Phone
1. On the Mi A3, open the [latest release](https://github.com/Podcast-Tech/claude-radar/releases/latest) and download the `.apk`.
2. Open it. Android asks you to allow installs from your browser. Allow it, then install.
3. Open **Claude Radar**, tap **Create account**, and confirm the email Supabase sends you. The link opens a page that may not load, but the confirmation still works.
4. Sign in.

### 2. Windows PC
Open **PowerShell** (not as admin) and run:

```powershell
irm https://raw.githubusercontent.com/Podcast-Tech/claude-radar/main/windows/install.ps1 | iex
```

Sign in with the same email and password. The phone should show **Radar connected**. Restart any open Claude Code sessions.

The installer:
- creates a token for this PC (only its hash is stored online) in `%USERPROFILE%\.claude-radar\`
- adds background (`async`) hooks to `%USERPROFILE%\.claude\settings.json`, keeping your existing settings (backup: `settings.json.radar-backup`)

To uninstall:

```powershell
$env:RADAR_UNINSTALL=1; irm https://raw.githubusercontent.com/Podcast-Tech/claude-radar/main/windows/install.ps1 | iex
```

### 3. Mac (optional, e.g. the Mac mini at work)
Open **Terminal** and run:

```bash
curl -fsSL https://raw.githubusercontent.com/Podcast-Tech/claude-radar/main/mac/install.sh | bash
```

Sign in with the same account. Chats from every connected computer show on the phone, labelled with the computer's name. It uses the built-in `perl` and `curl`, so there's nothing to install.

To uninstall:

```bash
curl -fsSL https://raw.githubusercontent.com/Podcast-Tech/claude-radar/main/mac/install.sh | RADAR_UNINSTALL=1 bash
```

## Updates
The app checks GitHub Releases at launch and every 6 hours. Tap **Install** on the banner. Every release is signed with the same key, so it installs over the old version and keeps you signed in.

To publish a new version, push a tag:

```bash
git tag v1.0.1 && git push origin v1.0.1
```

GitHub Actions builds the APK, checks it's signed with the right key, and publishes the release.

Repository secrets required (Settings → Secrets and variables → Actions):
- `RADAR_KEYSTORE_BASE64`: the signing keystore, base64-encoded
- `RADAR_KEYSTORE_PASSWORD`: its password

**Keep an offline backup of the keystore.** If it's lost, future versions can't update the installed app. You'd have to uninstall and reinstall.

## What reports what
| Claude Code event | Radar status |
|---|---|
| You send a prompt | working |
| Permission / question prompt | needs you |
| A tool runs after you approved | working |
| Claude finishes its turn | finished (with Claude's last message) |
| API error | failed |
| Session closed | removed, unless there's an unseen result |

Hook errors are logged to `%USERPROFILE%\.claude-radar\hook-errors.log`.

## Repo layout
- `app/`: Android app (Kotlin, Jetpack Compose, supabase-kt)
- `windows/`: PowerShell installer and hook
- `mac/`: macOS installer and hook (bash + perl)
- `supabase/`: database schema and the `report` edge function
- `.github/workflows/release.yml`: signed release builds
