# Agent create-meeting helper (Windows/PowerShell)
#
# Reads API_BASE from ../.env, then POSTs the meeting title + transcript with
# the JWT captured from a prior successful login. Sends the
# "X-Agent-Channel: whatsapp" header so the Spring Boot API enforces the
# Pro-only rule for WhatsApp-originated requests.
#
# Prints the JSON response followed by a final line "HTTP_STATUS:<code>"
# (000 = the API could not be reached or timed out).
#
# Long transcripts: pass -TranscriptFile <path> (UTF-8 text) instead of
# -Transcript. Command-line arguments break on embedded double quotes and are
# capped at ~32k characters on Windows.
#
# WHY TEMP FILE: PowerShell mangles double-quotes when it passes a variable
# to curl.exe via -d "$body". Writing the body to a temp file and using
# --data-binary "@file" bypasses this entirely and always sends clean JSON.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File agent-create-meeting.ps1 -Jwt "<JWT>" -Title "<title>" -Transcript "<transcript>"
#   powershell -ExecutionPolicy Bypass -File agent-create-meeting.ps1 -Jwt "<JWT>" -Title "<title>" -TranscriptFile "<path>"
param(
    [Parameter(Mandatory = $true)][string]$Jwt,
    [string]$Title = '',
    [string]$Transcript = '',
    [string]$TranscriptFile = ''
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$utf8NoBom = New-Object System.Text.UTF8Encoding $false

if ($TranscriptFile) {
    if (-not (Test-Path $TranscriptFile)) {
        Write-Output '{"error":"Transcript file not found"}'
        Write-Output 'HTTP_STATUS:000'
        exit 1
    }
    $Transcript = [System.IO.File]::ReadAllText((Resolve-Path $TranscriptFile), [System.Text.Encoding]::UTF8)
}
if (-not $Transcript.Trim()) {
    Write-Output '{"error":"Provide -Transcript or -TranscriptFile"}'
    Write-Output 'HTTP_STATUS:000'
    exit 1
}

# Load workspace .env values (API_BASE, FRONTEND_URL) into the process env
$envFile = Join-Path $PSScriptRoot '..\.env'
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
            Set-Item -Path ("Env:" + $matches[1]) -Value $matches[2]
        }
    }
}

$apiBase = if ($env:API_BASE) { $env:API_BASE.TrimEnd('/') } else { 'http://localhost:8080' }

# "skip" means untitled; the backend defaults empty titles to "Untitled Meeting"
if ($Title.Trim() -ieq 'skip') { $Title = '' }

$body = @{ title = $Title.Trim(); transcript = $Transcript.Trim() } | ConvertTo-Json -Compress
$bodyFile = [System.IO.Path]::GetTempFileName()
$outFile = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($bodyFile, $body, $utf8NoBom)

try {
    # Summaries of long transcripts can take 1-2 minutes (backend timeout is 120s)
    $status = curl.exe -s --connect-timeout 10 --max-time 180 `
        -X POST "$apiBase/api/meetings" `
        -H "Authorization: Bearer $Jwt" `
        -H "Content-Type: application/json; charset=utf-8" `
        -H "X-Agent-Channel: whatsapp" `
        --data-binary "@$bodyFile" `
        -o $outFile -w "%{http_code}"
    if ($LASTEXITCODE -ne 0) { $status = '000' }
    Write-Output ([System.IO.File]::ReadAllText($outFile, [System.Text.Encoding]::UTF8))
    Write-Output "HTTP_STATUS:$status"
} finally {
    Remove-Item -Path $bodyFile, $outFile -Force -ErrorAction SilentlyContinue
}
