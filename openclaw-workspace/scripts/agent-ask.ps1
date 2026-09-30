# Agent RAG-chat helper (Windows/PowerShell)
#
# Reads API_BASE from ../.env, then POSTs a natural-language question about the
# user's past meetings, using the JWT captured from a prior successful login.
# The backend embeds the question, retrieves the most relevant stored meetings,
# and answers using only that context (see RagService on the backend).
#
# Prints the JSON response ({"answer":"...","sources":[...]}) followed by a
# final line "HTTP_STATUS:<code>" (000 = the API could not be reached or timed out).
#
# WHY TEMP FILE: PowerShell mangles double-quotes when it passes a variable
# to curl.exe via -d "$body". Writing the body to a temp file and using
# --data-binary "@file" bypasses this entirely and always sends clean JSON.
#
# Usage: powershell -ExecutionPolicy Bypass -File agent-ask.ps1 -Jwt "<JWT>" -Question "<question>"
param(
    [Parameter(Mandatory = $true)][string]$Jwt,
    [Parameter(Mandatory = $true)][string]$Question
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

$body = @{ question = $Question.Trim() } | ConvertTo-Json -Compress
$bodyFile = [System.IO.Path]::GetTempFileName()
$outFile = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($bodyFile, $body, $utf8NoBom)

try {
    # Embedding the question + retrieval + answer generation is 2 OpenRouter
    # round-trips; give it real headroom (backend timeout is 120s per call).
    $status = curl.exe -s --connect-timeout 10 --max-time 150 `
        -X POST "$apiBase/api/chat" `
        -H "Authorization: Bearer $Jwt" `
        -H "Content-Type: application/json; charset=utf-8" `
        --data-binary "@$bodyFile" `
        -o $outFile -w "%{http_code}"
    if ($LASTEXITCODE -ne 0) { $status = '000' }
    Write-Output ([System.IO.File]::ReadAllText($outFile, [System.Text.Encoding]::UTF8))
    Write-Output "HTTP_STATUS:$status"
} finally {
    Remove-Item -Path $bodyFile, $outFile -Force -ErrorAction SilentlyContinue
}
