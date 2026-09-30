# Agent login helper (Windows/PowerShell)
#
# Reads API_BASE from ../.env, then POSTs email+password to the API and
# prints the JSON response (JWT + user object) to stdout, followed by a final
# line "HTTP_STATUS:<code>" so the agent can branch on the status code
# (000 = the API could not be reached).
#
# WHY TEMP FILE: PowerShell mangles double-quotes when it passes a variable
# to curl.exe via -d "$body". Writing the body to a temp file and using
# --data-binary "@file" bypasses this entirely and always sends clean JSON.
#
# Usage: powershell -ExecutionPolicy Bypass -File agent-login.ps1 -Email "<email>" -Password "<password>"
param(
    [Parameter(Mandatory = $true)][string]$Email,
    [Parameter(Mandatory = $true)][string]$Password
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$utf8NoBom = New-Object System.Text.UTF8Encoding $false

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

$body = @{ email = $Email.Trim(); password = $Password } | ConvertTo-Json -Compress
$bodyFile = [System.IO.Path]::GetTempFileName()
$outFile = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($bodyFile, $body, $utf8NoBom)

try {
    $status = curl.exe -s --connect-timeout 10 --max-time 30 `
        -X POST "$apiBase/api/auth/login" `
        -H "Content-Type: application/json" `
        --data-binary "@$bodyFile" `
        -o $outFile -w "%{http_code}"
    if ($LASTEXITCODE -ne 0) { $status = '000' }
    Write-Output ([System.IO.File]::ReadAllText($outFile, [System.Text.Encoding]::UTF8))
    Write-Output "HTTP_STATUS:$status"
} finally {
    Remove-Item -Path $bodyFile, $outFile -Force -ErrorAction SilentlyContinue
}
