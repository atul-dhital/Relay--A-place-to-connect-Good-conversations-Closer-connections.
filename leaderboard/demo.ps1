param([string]$BaseUrl = 'http://localhost:8090')
$ErrorActionPreference = 'Stop'
$demoName = 'demo_' + [Guid]::NewGuid().ToString('N').Substring(0, 10)
$demoPassword = 'Demo-' + [Guid]::NewGuid().ToString('N')
$credentials = @{ username = $demoName; password = $demoPassword } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/register" -ContentType 'application/json' -Body $credentials | Out-Null
$session = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/login" -ContentType 'application/json' -Body $credentials
$headers = @{ Authorization = 'Bearer ' + $session.token }
try {
    $score = @{ submissionId = [Guid]::NewGuid().ToString(); game = 'chess'; score = 150 } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/scores" -Headers $headers -ContentType 'application/json' -Body $score | Out-Null
    $retry = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/scores" -Headers $headers -ContentType 'application/json' -Body $score
    if (-not $retry.replayed) { throw 'Retry incorrectly added points.' }
    Write-Output 'Score submitted; retry safely deduplicated.'
    Invoke-RestMethod -Uri "$BaseUrl/api/leaderboards" -Headers $headers | ConvertTo-Json -Depth 6
    Invoke-RestMethod -Uri "$BaseUrl/api/rankings/me" -Headers $headers | ConvertTo-Json
    Invoke-RestMethod -Uri "$BaseUrl/api/scores/history" -Headers $headers | ConvertTo-Json -Depth 6
    $from = [Uri]::EscapeDataString([DateTime]::UtcNow.Date.ToString("yyyy-MM-ddTHH:mm:ssZ"))
    $to = [Uri]::EscapeDataString([DateTime]::UtcNow.Date.AddDays(1).ToString("yyyy-MM-ddTHH:mm:ssZ"))
    Invoke-RestMethod -Uri "$BaseUrl/api/reports/top-players?from=$from&to=$to&game=chess" -Headers $headers | ConvertTo-Json -Depth 6
} finally {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/logout" -Headers $headers | Out-Null
}
