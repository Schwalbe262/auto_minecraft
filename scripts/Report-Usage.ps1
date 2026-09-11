param(
    [Parameter(Mandatory=$true)][string]$SessionPath,
    [string]$StartMessage = 'Implement the plan.',
    [string]$OutputPath
)
$ErrorActionPreference = 'Stop'
function Read-UsageEvents([string]$Path, [Nullable[DateTimeOffset]]$BaselineAt = $null) {
    $last = $null; $first = $null; $baseline = $null
    $start = if ($null -ne $BaselineAt) { ([DateTimeOffset]$BaselineAt).ToString('o') } else { $null }
    foreach ($line in (Get-Content -LiteralPath $Path -Encoding UTF8)) {
        if ($null -eq $BaselineAt -and $line -match '"role"\s*:\s*"user"' -and $line.Contains($StartMessage)) {
            $userRow = $line | ConvertFrom-Json
            if (($userRow.payload.content.text -join '') -eq $StartMessage) { $baseline = $last; $start = $userRow.timestamp }
        }
        if ($line -notmatch '"type"\s*:\s*"(token_count|user_msg)"') { continue }
        $row = $line | ConvertFrom-Json
        if ($row.type -ne 'event_msg') { continue }
        if ($null -eq $BaselineAt -and $row.payload.type -eq 'user_msg' -and $row.payload.message -eq $StartMessage) { $baseline = $last; $start = $row.timestamp }
        if ($row.payload.type -eq 'token_count' -and $null -ne $row.payload.info) {
            $last = $row
            if (-not $first) { $first = $row }
            if ($null -ne $BaselineAt -and [DateTimeOffset]$row.timestamp -le [DateTimeOffset]$BaselineAt) { $baseline = $row }
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
$taskSessionMetadata = @()
$rootSessionDay = Split-Path -Parent $rootPath
$sessionDirectories = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
[void]$sessionDirectories.Add($rootSessionDay)
# Reused root sessions may live in an earlier date folder than newly spawned children.
# Reused child sessions may predate this task too. Discover the root lifetime's
# UTC/host-local day range, then retain only descendants with usage after the task baseline.
$sessionsRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $rootSessionDay))
if ((Split-Path -Leaf $sessionsRoot) -eq 'sessions') {
    $firstTaskDay = ([DateTimeOffset]$rootUsage.Start).UtcDateTime.Date
    $rootCreatedDay = ([DateTimeOffset]$rootMeta.payload.timestamp).UtcDateTime.Date
    if ($rootCreatedDay -lt $firstTaskDay) { $firstTaskDay = $rootCreatedDay }
    $lastTaskDay = ([DateTimeOffset]$rootUsage.Last.timestamp).UtcDateTime.Date
    $firstLocalDay = ([DateTimeOffset]$rootUsage.Start).LocalDateTime.Date
    $lastLocalDay = ([DateTimeOffset]$rootUsage.Last.timestamp).LocalDateTime.Date
    if ($firstLocalDay -lt $firstTaskDay) { $firstTaskDay = $firstLocalDay }
    if ($lastLocalDay -gt $lastTaskDay) { $lastTaskDay = $lastLocalDay }
    for ($taskDay = $firstTaskDay; $taskDay -le $lastTaskDay; $taskDay = $taskDay.AddDays(1)) {
        $candidateDirectory = Join-Path $sessionsRoot $taskDay.ToString('yyyy/MM/dd',[Globalization.CultureInfo]::InvariantCulture)
        if (Test-Path -LiteralPath $candidateDirectory -PathType Container) { [void]$sessionDirectories.Add($candidateDirectory) }
    }
}
$taskSessionFiles = foreach ($directory in $sessionDirectories) { Get-ChildItem -LiteralPath $directory -Filter '*.jsonl' -File }
foreach ($file in $taskSessionFiles) {
    if ($file.FullName -eq $rootPath) { continue }
    $meta = Get-Content -LiteralPath $file.FullName -TotalCount 1 -Encoding UTF8 | ConvertFrom-Json
    $taskSessionMetadata += [pscustomobject]@{Path=$file.FullName;Meta=$meta}
}
$taskDescendantIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
[void]$taskDescendantIds.Add([string]$rootMeta.payload.id)
do {
    $taskAddedDescendant=$false
    foreach ($entry in $taskSessionMetadata) {
        $spawn=$entry.Meta.payload.source.subagent.thread_spawn
        if ($spawn -and $taskDescendantIds.Contains([string]$spawn.parent_thread_id)) {
            if ($taskDescendantIds.Add([string]$entry.Meta.payload.id)) { $taskAddedDescendant=$true }
        }
    }
} while ($taskAddedDescendant)
foreach ($entry in $taskSessionMetadata) {
    if (-not $taskDescendantIds.Contains([string]$entry.Meta.payload.id)) { continue }
    $spawn=$entry.Meta.payload.source.subagent.thread_spawn
    $usage = Read-UsageEvents $entry.Path ([DateTimeOffset]$rootUsage.Start)
    if (-not $usage.Last -or [DateTimeOffset]$usage.Last.timestamp -le [DateTimeOffset]$rootUsage.Start) { continue }
    $childBaselineTokens = if ($usage.Baseline) { [long]$usage.Baseline.payload.info.total_token_usage.total_tokens } else { 0 }
    $childTokens = [long]$usage.Last.payload.info.total_token_usage.total_tokens - $childBaselineTokens
    if ($childTokens -lt 0) { throw 'A child token counter moved backwards after the task baseline; no usage estimate produced.' }
    $rows += [pscustomobject]@{
        Workstream=$spawn.agent_path; Tokens=$childTokens
        LastRecorded=$usage.Last.timestamp; WeeklyStart=(Weekly $(if ($usage.Baseline) { $usage.Baseline } else { $usage.First })); WeeklyEnd=(Weekly $usage.Last)
    }
}
$report = [pscustomobject]@{
    RecordedAt=[DateTimeOffset]::UtcNow.ToString('o'); Start=$rootUsage.Start
    IncludesCachedInput=$true; TotalRecordedTokens=($rows | Measure-Object -Property Tokens -Sum).Sum
    WeeklyStart=(Weekly $rootUsage.Baseline); WeeklyEnd=(Weekly $rootUsage.Last)
    ExactPerWorkstreamWeeklyShare=$null
    Note='Token totals are recorded per agent workstream, including repeated/cached input and review. Descendant sessions from the root lifetime UTC/host-local day range are included; reused sessions subtract their last recorded total at or before the task start. Weekly percentages are shared-account snapshots, not attributable per-stream shares. Final response and unflushed events are excluded; workstream totals are not exact per-feature attribution when an agent handled multiple parts.'
    Workstreams=$rows
}
$json = $report | ConvertTo-Json -Depth 6
if ($OutputPath) { [IO.File]::WriteAllText([IO.Path]::GetFullPath($OutputPath),$json,[Text.UTF8Encoding]::new($false)) }
$json
