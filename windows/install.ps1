# Claude Radar — Windows setup for Claude Code.
# Connects this PC to your Radar account and adds background hooks to Claude Code.
#
# Install:    irm https://raw.githubusercontent.com/Podcast-Tech/claude-radar/main/windows/install.ps1 | iex
# Uninstall:  $env:RADAR_UNINSTALL=1; irm https://raw.githubusercontent.com/Podcast-Tech/claude-radar/main/windows/install.ps1 | iex

$Uninstall = ($args -contains "-Uninstall") -or ($env:RADAR_UNINSTALL -eq "1")

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$SupabaseUrl = "https://dkfiyqacjdsvvbrcsabw.supabase.co"
$PublishableKey = "sb_publishable_QYuYaLDjC6862ClbKyhO7w_gDTdHEct"
$RawBase = "https://raw.githubusercontent.com/Podcast-Tech/claude-radar/main/windows"

$RadarDir = Join-Path $env:USERPROFILE ".claude-radar"
$ClaudeDir = Join-Path $env:USERPROFILE ".claude"
$SettingsPath = Join-Path $ClaudeDir "settings.json"
$HookPath = Join-Path $RadarDir "radar-hook.ps1"
$Events = @("UserPromptSubmit", "PostToolUse", "Notification", "Stop", "StopFailure", "SessionEnd")

function Say($t, $c = "Gray") { Write-Host $t -ForegroundColor $c }

function Read-Settings {
    if (Test-Path $SettingsPath) {
        $txt = Get-Content $SettingsPath -Raw -Encoding UTF8
        if ($txt.Trim()) { return $txt | ConvertFrom-Json }
    }
    return New-Object PSObject
}

function Write-Settings($obj) {
    New-Item -ItemType Directory -Force -Path $ClaudeDir | Out-Null
    if (Test-Path $SettingsPath) {
        Copy-Item $SettingsPath "$SettingsPath.radar-backup" -Force
    }
    $json = ConvertTo-Json -InputObject $obj -Depth 64
    [IO.File]::WriteAllText($SettingsPath, $json, (New-Object Text.UTF8Encoding($false)))
}

# Remove every hook entry that points at radar-hook.ps1, keeping everything else.
function Remove-RadarHooks($settings) {
    if (-not $settings.PSObject.Properties["hooks"]) { return }
    $hooks = $settings.hooks
    foreach ($p in @($hooks.PSObject.Properties)) {
        $groups = @()
        foreach ($g in @($p.Value)) {
            $kept = @(@($g.hooks) | Where-Object {
                $all = "$($_.command) $(@($_.args) -join ' ')"
                $all -notmatch "radar-hook\.ps1"
            })
            if ($kept.Count -gt 0) {
                $g.hooks = $kept
                $groups += $g
            }
        }
        if ($groups.Count -gt 0) { $hooks.($p.Name) = $groups }
        else { $hooks.PSObject.Properties.Remove($p.Name) }
    }
}

if ($Uninstall) {
    $cfgPath = Join-Path $RadarDir "config.json"
    if (Test-Path $cfgPath) {
        try {
            $c = Get-Content $cfgPath -Raw | ConvertFrom-Json
            Invoke-RestMethod -Method Post -Uri "$($c.url)/functions/v1/report" -Headers @{ "x-radar-token" = $c.token } `
                -ContentType "application/json" -Body '{"revoke":true}' | Out-Null
        } catch { Say "Could not revoke this PC's token online (offline?)." Yellow }
    }
    $s = Read-Settings
    Remove-RadarHooks $s
    Write-Settings $s
    Remove-Item $RadarDir -Recurse -Force -ErrorAction SilentlyContinue
    Say "Claude Radar removed from Claude Code. (Backup: $SettingsPath.radar-backup)" Green
    return
}

Say ""
Say "Claude Radar setup" Cyan
Say "Use the account you created in the Claude Radar app on your phone." Gray
$email = if ($env:RADAR_EMAIL) { $env:RADAR_EMAIL } else { Read-Host "Email" }
if ($env:RADAR_PASSWORD) { $password = $env:RADAR_PASSWORD } else {
    $secure = Read-Host "Password" -AsSecureString
    $password = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
}

# 1. Sign in
try {
    $auth = Invoke-RestMethod -Method Post -Uri "$SupabaseUrl/auth/v1/token?grant_type=password" `
        -Headers @{ apikey = $PublishableKey } -ContentType "application/json" `
        -Body (@{ email = $email; password = $password } | ConvertTo-Json)
} catch {
    Say "Sign-in failed. Check your email/password, and that you confirmed your email." Red
    throw
}
$password = $null

# 2. Create a token for this PC (only its hash is stored online)
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$token = [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
$sha = [Security.Cryptography.SHA256]::Create()
$hash = -join ($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($token)) | ForEach-Object { $_.ToString("x2") })

Invoke-RestMethod -Method Post -Uri "$SupabaseUrl/rest/v1/radar_tokens" `
    -Headers @{ apikey = $PublishableKey; Authorization = "Bearer $($auth.access_token)"; Prefer = "return=minimal" } `
    -ContentType "application/json" `
    -Body (@{ token_hash = $hash; label = $env:COMPUTERNAME } | ConvertTo-Json) | Out-Null

# 3. Save config + hook script
New-Item -ItemType Directory -Force -Path $RadarDir | Out-Null
$cfg = @{ url = $SupabaseUrl; token = $token } | ConvertTo-Json
[IO.File]::WriteAllText((Join-Path $RadarDir "config.json"), $cfg, (New-Object Text.UTF8Encoding($false)))

$localHook = if ($PSScriptRoot) { Join-Path $PSScriptRoot "radar-hook.ps1" } else { $null }
if ($localHook -and (Test-Path $localHook)) {
    Copy-Item $localHook $HookPath -Force
} else {
    Invoke-WebRequest -UseBasicParsing -Uri "$RawBase/radar-hook.ps1" -OutFile $HookPath
}

# 4. Add background hooks to Claude Code's user settings
$settings = Read-Settings
Remove-RadarHooks $settings
if (-not $settings.PSObject.Properties["hooks"]) {
    $settings | Add-Member -NotePropertyName hooks -NotePropertyValue (New-Object PSObject)
}
foreach ($ev in $Events) {
    $hook = [ordered]@{
        type    = "command"
        command = "powershell.exe"
        args    = @("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                    "-File", $HookPath, $ev)
        async   = $true
        timeout = 15
    }
    $group = [ordered]@{ hooks = @([pscustomobject]$hook) }
    if ($ev -eq "Notification") {
        $group = [ordered]@{
            matcher = "permission_prompt|elicitation_dialog|elicitation_url_dialog|agent_needs_input"
            hooks = @([pscustomobject]$hook)
        }
    }
    $existing = @()
    if ($settings.hooks.PSObject.Properties[$ev]) { $existing = @($settings.hooks.$ev) }
    $all = @($existing) + @([pscustomobject]$group)
    if ($settings.hooks.PSObject.Properties[$ev]) { $settings.hooks.$ev = $all }
    else { $settings.hooks | Add-Member -NotePropertyName $ev -NotePropertyValue $all }
}
Write-Settings $settings

# 5. Send a test message to the phone
$test = @{
    session_id = "radar-setup-$($env:COMPUTERNAME)"
    status = "finished"; source = "code"; machine = $env:COMPUTERNAME
    title = "Radar connected"; message = "$($env:COMPUTERNAME) is now reporting Claude Code chats."
    at = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$SupabaseUrl/functions/v1/report" -Headers @{ "x-radar-token" = $token } `
    -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($test)) | Out-Null

Say ""
Say "Done. Your phone should show 'Radar connected' now." Green
Say "Restart any open Claude Code sessions so they pick up the new hooks." Yellow
Say "Settings backup: $SettingsPath.radar-backup" Gray
