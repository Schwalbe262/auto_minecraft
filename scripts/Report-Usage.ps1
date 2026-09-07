param(
    [Parameter(Mandatory=$true)][string]$SessionPath,
    [string]$StartMessage = 'Implement the plan.',
    [string]$OutputPath
)
$ErrorActionPreference = 'Stop'
function Read-UsageEvents([string]$Path) {
    $last = $null; $first = $null; $baseline = $null; $start = $null
    foreach ($line in (Get-Content -LiteralPath $Path -Encoding UTF8)) {
        if ($line -match '"role"\s*:\s*"user"' -and $line.Contains($StartMessage)) {
            $userRow = $line | ConvertFrom-Json
            if (($userRow.payload.content.text -join '') -eq $StartMessage) { $baseline = $last; $start = $userRow.timestamp }
        }
        if ($line -notmatch '"type"\s*:\s*"(token_count|user_msg)"') { continue }
        $row = $line | ConvertFrom-Json
        if ($row.type -ne 'event_msg') { continue }
        if ($row.payload.type -eq 'user_msg' -and $row.payload.message -eq $StartMessage) { $baseline = $last; $start = $row.timestamp }
        if ($row.payload.type -eq 'token_count' -and $null -ne $row.payload.info) {
            $last = $row
            if (-not $first) { $first = $row }
        }
    }
    return [pscustomobject]@{First=$first;Last=$last;Baseline=$baseline;Start=$start}
}
function Weekly($Event) {
    if (-not $Event) { return $null }
    foreach ($window in @($Event.payload.rate_limits.primary,$Event.payload.rate_limits.secondary)) {
        if ($window.window_minutes -eq 10080) { return $window.used_percent }
    }
    return $null
}
$rootPath = (Resolve-Path -LiteralPath $SessionPath).Path
$rootMeta = Get-Content -LiteralPath $rootPath -TotalCount 1 -Encoding UTF8 | ConvertFrom-Json
$rootUsage = Read-UsageEvents $rootPath
if (-not $rootUsage.Start -or -not $rootUsage.Last) { throw 'Task start or token telemetry was not found; no usage estimate produced.' }
$baselineTokens = if ($rootUsage.Baseline) { [long]$rootUsage.Baseline.payload.info.total_token_usage.total_tokens } else { 0 }
$rows = @([pscustomobject]@{
    Workstream='core-and-integration'; Tokens=([long]$rootUsage.Last.payload.info.total_token_usage.total_tokens-$baselineTokens)
    LastRecorded=$rootUsage.Last.timestamp; WeeklyStart=(Weekly $rootUsage.Baseline); WeeklyEnd=(Weekly $rootUsage.Last)
})
foreach ($file in Get-ChildItem -LiteralPath (Split-Path -Parent $rootPath) -Filter '*.jsonl' -File) {
    if ($file.FullName -eq $rootPath) { continue }
    $meta = Get-Content -LiteralPath $file.FullName -TotalCount 1 -Encoding UTF8 | ConvertFrom-Json
    $spawn = $meta.payload.source.subagent.thread_spawn
    if ($spawn.parent_thread_id -ne $rootMeta.payload.id -or [DateTimeOffset]$meta.payload.timestamp -lt [DateTimeOffset]$rootUsage.Start) { continue }
    $usage = Read-UsageEvents $file.FullName
    if (-not $usage.Last) { continue }
    $rows += [pscustomobject]@{
        Workstream=$spawn.agent_path; Tokens=[long]$usage.Last.payload.info.total_token_usage.total_tokens
        LastRecorded=$usage.Last.timestamp; WeeklyStart=(Weekly $usage.First); WeeklyEnd=(Weekly $usage.Last)
    }
}
$report = [pscustomobject]@{
    RecordedAt=[DateTimeOffset]::UtcNow.ToString('o'); Start=$rootUsage.Start
    IncludesCachedInput=$true; TotalRecordedTokens=($rows | Measure-Object -Property Tokens -Sum).Sum
    WeeklyStart=(Weekly $rootUsage.Baseline); WeeklyEnd=(Weekly $rootUsage.Last)
    ExactPerWorkstreamWeeklyShare=$null
    Note='Token totals are recorded per agent workstream, including repeated/cached input and review. Weekly percentages are shared-account snapshots, not attributable per-stream shares. Final response and unflushed events are excluded.'
    Workstreams=$rows
}
$json = $report | ConvertTo-Json -Depth 6
if ($OutputPath) { [IO.File]::WriteAllText([IO.Path]::GetFullPath($OutputPath),$json,[Text.UTF8Encoding]::new($false)) }
$json
