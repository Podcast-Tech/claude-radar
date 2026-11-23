# Claude Radar hook for Claude Code (Windows).
# Called by Claude Code with the event name as the first argument and the event JSON on stdin.
# Runs as an async hook, so it never slows Claude down. Any failure is silent.
param([string]$HookEvent = "")

$ErrorActionPreference = "Stop"
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $dir = Join-Path $env:USERPROFILE ".claude-radar"
    $cfg = Get-Content (Join-Path $dir "config.json") -Raw -Encoding UTF8 | ConvertFrom-Json

    # Read stdin as UTF-8 bytes (keeps Arabic, emoji etc. intact).
    $ms = New-Object System.IO.MemoryStream
    [Console]::OpenStandardInput().CopyTo($ms)
    $raw = [Text.Encoding]::UTF8.GetString($ms.ToArray())
    $in = $raw | ConvertFrom-Json
    $sid = [string]$in.session_id
    if (-not $sid) { exit 0 }
    if (-not $HookEvent) { $HookEvent = [string]$in.hook_event_name }

    $stateDir = Join-Path $dir "state"
    New-Item -ItemType Directory -Force -Path $stateDir | Out-Null
    $stateFile = Join-Path $stateDir (($sid -replace '[^\w\-]', '_') + ".json")
    $state = @{ status = ""; title = "" }
    if (Test-Path $stateFile) {
        $s = Get-Content $stateFile -Raw -Encoding UTF8 | ConvertFrom-Json
        $state.status = [string]$s.status; $state.title = [string]$s.title
    }

    function Short([string]$t, [int]$n) {
        if (-not $t) { return $null }
        $t = ($t -replace '\s+', ' ').Trim()
        if ($t.Length -gt $n) { return $t.Substring(0, $n - 1) + [char]0x2026 }
        return $t
    }

    $status = $null; $message = $null
    switch ($HookEvent) {
        "UserPromptSubmit" {
            $status = "working"
            if ($in.session_title) { $state.title = Short ([string]$in.session_title) 60 }
            elseif (-not $state.title) { $state.title = Short ([string]$in.prompt) 60 }
            $message = Short ([string]$in.prompt) 160
        }
        "PostToolUse" {
            # Only matters right after a permission prompt: you approved it, so it's working again.
            if ($state.status -ne "needs_you") { exit 0 }
            $status = "working"
        }
        "Notification" {
            $status = "needs_you"
            $message = Short ([string]$in.message) 200
        }
        "Stop" {
            $status = "finished"
            $message = Short ([string]$in.last_assistant_message) 280
        }
        "StopFailure" {
            $status = "failed"
            $message = Short ("Error: " + [string]$in.error + " " + [string]$in.error_details) 200
        }
        "SessionEnd" { $status = "ended" }
        default { exit 0 }
    }

    $project = if ($in.cwd) { Split-Path -Leaf ([string]$in.cwd) } else { $null }
    $body = [ordered]@{
        session_id = $sid
        status     = $status
        source     = "code"
        title      = $(if ($state.title) { $state.title } else { $project })
        project    = $project
        machine    = $env:COMPUTERNAME
        message    = $message
        at         = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    }
    if ($HookEvent -eq "SessionEnd" -and $in.reason) { $body["reason"] = [string]$in.reason }

    if ($status -eq "ended") { Remove-Item $stateFile -ErrorAction SilentlyContinue }
    else {
        $state.status = $status
        [IO.File]::WriteAllText($stateFile, ($state | ConvertTo-Json -Compress), [Text.Encoding]::UTF8)
    }

    $bytes = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress))
    Invoke-RestMethod -Uri "$($cfg.url)/functions/v1/report" -Method Post -TimeoutSec 8 `
        -Headers @{ "x-radar-token" = $cfg.token } -ContentType "application/json; charset=utf-8" -Body $bytes | Out-Null
} catch {
    try {
        $log = Join-Path $env:USERPROFILE ".claude-radar\hook-errors.log"
        Add-Content -Path $log -Value ("{0} {1} {2}" -f (Get-Date -Format s), $HookEvent, $_.Exception.Message)
    } catch {}
}
exit 0
