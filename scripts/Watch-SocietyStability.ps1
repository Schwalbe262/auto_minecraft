<#
.SYNOPSIS
Collects bounded, passive Society automation evidence from one exact game process.
.DESCRIPTION
Only creates the normal, empty config/autovalley/inspect.request marker in the game
instance. Never starts, stops, resumes or otherwise controls gameplay or edits a
profile. The caller must have permission to write that marker. Profile observations
are read from disk separately from the fresh client diagnostic, not an atomic pair.

Evidence is private: OutputDirectory must be a new directory beneath this repository's
ignored .local directory. latest.json is replaced atomically; observations.jsonl is
append-only. An existing output directory is rejected to keep runs independent.

A verified window requires fresh connected, running CONTINUOUS automation, no
recording, no new failures, and repeated work evidence. Time/position changes alone
are not work evidence. Initial failure history requires observed recovery before a
clean window starts. Sampling cannot prove what happened between observations.
.EXAMPLE
.\scripts\Watch-SocietyStability.ps1 -GameProcessId 12345 -InstancePath 'C:\Games\Society Sunlit Valley' -ProfileKey '0123456789abcdef01234567' -OutputDirectory '.local\stability-run' -DurationMinutes 120 -MaxDurationMinutes 240
.EXAMPLE
.\scripts\Watch-SocietyStability.ps1 -GameProcessId 12345 -InstancePath 'C:\Games\Society Sunlit Valley' -ProfileKey '0123456789abcdef01234567' -OutputDirectory '.local\inspection-run' -Once
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidateRange(1,2147483647)][int]$GameProcessId,
    [Parameter(Mandatory=$true)][string]$InstancePath,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [ValidatePattern('^[a-f0-9]{24}$')][string]$ProfileKey,
    [ValidateRange(1,1440)][double]$DurationMinutes = 120,
    [ValidateRange(1,2880)][double]$MaxDurationMinutes = 240,
    [ValidateRange(2,60)][int]$IntervalSeconds = 30,
    [ValidateRange(2,30)][int]$InspectionTimeoutSeconds = 10,
    [ValidateRange(1,120)][double]$MaxNoProgressMinutes = 10,
    [ValidatePattern('^[A-Z][A-Z0-9_]*$')][string[]]$RequiredFeature = @(),
    [switch]$Once
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2
$utf8 = [Text.UTF8Encoding]::new($false)

function Get-Field($Object, [string]$Name, $Default = $null) {
    if ($null -eq $Object) { return $Default }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $Default }
    return $property.Value
}

function Get-Digest([string]$Text) {
    $hash = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($hash.ComputeHash($utf8.GetBytes($Text)))).Replace('-','').ToLowerInvariant() }
    finally { $hash.Dispose() }
}

function Convert-Compact($Value) { return ConvertTo-Json -InputObject $Value -Depth 16 -Compress }

function Assert-NoReparsePath([string]$Path) {
    $part = [IO.Path]::GetFullPath($Path)
    while ($part) {
        if (Test-Path -LiteralPath $part) {
            if (((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'A monitored or output path contains a reparse point; select a direct local path.'
            }
        }
        $parent = [IO.Directory]::GetParent($part)
        $part = if ($null -eq $parent) { $null } else { $parent.FullName }
    }
}

function Get-GameIdentity {
    try { $process = Get-CimInstance Win32_Process -Filter "ProcessId=$GameProcessId" -ErrorAction Stop }
    catch { return [pscustomobject]@{ state='PROCESS_CHECK_UNAVAILABLE'; startUtc=$null } }
    if ($null -eq $process) { return [pscustomobject]@{ state='PROCESS_EXITED'; startUtc=$null } }
    $creation = ([DateTime]$process.CreationDate).ToUniversalTime().ToString('o')
    if ($process.Name -ne 'javaw.exe') { return [pscustomobject]@{ state='PROCESS_IDENTITY_CHANGED'; startUtc=$creation } }
    # Inspect only this PID; never emit its command line (which can include credentials).
    $arguments = [regex]::Matches([string]$process.CommandLine, '(?:^|\s)--gameDir(?:=|\s+)(?:"([^"]+)"|([^\s"]+))')
    if ($arguments.Count -ne 1) { return [pscustomobject]@{ state='GAME_DIRECTORY_UNVERIFIED'; startUtc=$creation } }
    $gameDirectory = if ($arguments[0].Groups[1].Success) { $arguments[0].Groups[1].Value } else { $arguments[0].Groups[2].Value }
    try { $gameDirectory = [IO.Path]::GetFullPath($gameDirectory).TrimEnd('\','/') }
    catch { return [pscustomobject]@{ state='GAME_DIRECTORY_UNVERIFIED'; startUtc=$creation } }
    if (-not $gameDirectory.Equals($instanceRoot,[StringComparison]::OrdinalIgnoreCase)) {
        return [pscustomobject]@{ state='GAME_DIRECTORY_MISMATCH'; startUtc=$creation }
    }
    if ($null -ne $script:expectedStartUtc -and $creation -ne $script:expectedStartUtc) {
        return [pscustomobject]@{ state='PROCESS_REPLACED'; startUtc=$creation }
    }
    return [pscustomobject]@{ state='ALIVE'; startUtc=$creation }
}

function Read-BoundedJson([string]$Path, [long]$Limit = 8000000) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw 'Observation file is missing.' }
    $file = Get-Item -LiteralPath $Path -Force
    if (($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or $file.Length -gt $Limit) {
        throw 'Observation file is linked or exceeds the size limit.'
    }
    return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Request-FreshInspection {
    $identity = Get-GameIdentity
    if ($identity.state -ne 'ALIVE') { return [pscustomobject]@{ identity=$identity; fresh=$false; reason=$identity.state; snapshot=$null; requestedAt=$null } }
    $priorCapture = $null
    try { $priorCapture = [string](Get-Field (Read-BoundedJson $diagnosticPath) 'capturedAt') } catch { }
    $requestedAt = [DateTimeOffset]::UtcNow
    Assert-NoReparsePath $requestPath
    if (-not (Test-Path -LiteralPath $requestPath)) {
        try {
            $request = [IO.File]::Open($requestPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
            $request.Dispose()
        } catch [IO.IOException] {
            if (-not (Test-Path -LiteralPath $requestPath -PathType Leaf)) { throw }
        }
    } elseif (-not (Test-Path -LiteralPath $requestPath -PathType Leaf)) { throw 'Inspection request path is not a regular file.' }
    $wait = [Diagnostics.Stopwatch]::StartNew()
    while ($wait.Elapsed.TotalSeconds -lt $InspectionTimeoutSeconds -and ($Once -or $clock.Elapsed.TotalMinutes -lt $MaxDurationMinutes)) {
        Start-Sleep -Milliseconds 250
        try {
            $snapshot = Read-BoundedJson $diagnosticPath
            $captured = [DateTimeOffset]::Parse([string](Get-Field $snapshot 'capturedAt'),[Globalization.CultureInfo]::InvariantCulture)
            if ($captured -ge $requestedAt -and $captured -le [DateTimeOffset]::UtcNow.AddSeconds(2) -and $captured.ToString('o') -ne $script:lastCapture -and [string]$snapshot.capturedAt -ne $priorCapture) {
                $identity = Get-GameIdentity
                if ($identity.state -ne 'ALIVE') { return [pscustomobject]@{ identity=$identity; fresh=$false; reason=$identity.state; snapshot=$null; requestedAt=$requestedAt.ToString('o') } }
                $script:lastCapture = $captured.ToString('o')
                return [pscustomobject]@{ identity=$identity; fresh=$true; reason='FRESH'; snapshot=$snapshot; requestedAt=$requestedAt.ToString('o') }
            }
        } catch { }
    }
    $identity = Get-GameIdentity
    $reason = if ($identity.state -eq 'ALIVE') { 'STALE_DIAGNOSTIC_PROCESS_ALIVE' } else { $identity.state }
    return [pscustomobject]@{ identity=$identity; fresh=$false; reason=$reason; snapshot=$null; requestedAt=$requestedAt.ToString('o') }
}

function Get-ProfileEvidence {
    $profile = Read-BoundedJson $profilePath 2000000
    $schedule = Get-Field $profile 'nextEligibleDay'
    if ($null -eq $schedule) { throw 'Profile schedule is missing.' }
    $scheduleText = @($schedule.PSObject.Properties | Sort-Object Name | ForEach-Object { $_.Name + '=' + [string]$_.Value }) -join "`n"
    $families = @($schedule.PSObject.Properties | Group-Object { ($_.Name -split ':',2)[0] } | Sort-Object Name | ForEach-Object {
        $values = @($_.Group | ForEach-Object { [long]$_.Value })
        $measure = $values | Measure-Object -Minimum -Maximum
        [pscustomobject]@{ family=$_.Name; count=$_.Count; minimumDay=$measure.Minimum; maximumDay=$measure.Maximum }
    })
    $logging = [ordered]@{
        enabled=(Get-Field (Get-Field $profile 'enabled') 'LOGGING' $false)
        active=(Get-Field $profile 'loggingRunActive' $false)
        remaining=@(Get-Field $profile 'loggingRemainingPlots' @())
        replanting=@(Get-Field $profile 'loggingReplantingPlots' @())
        lease=(Get-Field $profile 'loggingHotbarLease')
    }
    $refills = Get-Field $profile 'crystalRefills'
    $refillEntries = @()
    if ($null -ne $refills) { $refillEntries = @($refills.PSObject.Properties | Sort-Object Name | ForEach-Object { $_.Value }) }
    $pending = Get-Field $profile 'pendingMachineOutputs'
    $pendingCount = if ($null -eq $pending) { 0 } else { @($pending.PSObject.Properties).Count }
    return [pscustomobject]@{
        readAtUtc=[DateTimeOffset]::UtcNow.ToString('o'); fileLastWriteUtc=(Get-Item -LiteralPath $profilePath).LastWriteTimeUtc.ToString('o')
        schedule=[pscustomobject]@{ count=@($schedule.PSObject.Properties).Count; digest=(Get-Digest $scheduleText); families=$families }
        logging=[pscustomobject]@{ enabled=$logging.enabled; active=$logging.active; dueDay=(Get-Field $schedule 'logging:batch'); remainingCount=$logging.remaining.Count; replantingCount=$logging.replanting.Count; pendingDigest=(Get-Digest (Convert-Compact @($logging.remaining,$logging.replanting))); lease=$logging.lease }
        crystalRefillCount=$refillEntries.Count; crystalRefillDigest=(Get-Digest (Convert-Compact $refillEntries)); crystalRefills=@($refillEntries | Select-Object -First 32)
        crystalRefillsTruncated=($refillEntries.Count -gt 32); workHotbarLease=(Get-Field $profile 'workHotbarLease'); pendingMachineOutputCount=$pendingCount
        lastSeenDay=(Get-Field $profile 'lastSeenDay'); enabled=(Get-Field $profile 'enabled')
    }
}

function Get-InventoryEvidence($Snapshot) {
    $counts = [ordered]@{}
    foreach ($slot in @(Get-Field $Snapshot 'inventory' @())) {
        $item = Get-Field $slot 'item'
        $id = [string](Get-Field $item 'id' '')
        $count = [long](Get-Field $item 'count' 0)
        if ($id -and $id -ne 'minecraft:air' -and $count -gt 0) {
            if (-not $counts.Contains($id)) { $counts[$id] = [long]0 }
            $counts[$id] += $count
        }
    }
    $orderedCounts = [ordered]@{}
    foreach ($key in @($counts.Keys | Sort-Object)) { $orderedCounts[$key] = $counts[$key] }
    return [pscustomobject]@{ counts=$orderedCounts; digest=(Get-Digest (Convert-Compact $orderedCounts)) }
}

function Write-Evidence($Row, $Summary) {
    [IO.File]::AppendAllText($observationsPath,(Convert-Compact $Row) + [Environment]::NewLine,$utf8)
    $temporary = Join-Path $outputRoot ('latest-' + [guid]::NewGuid().ToString('N') + '.tmp')
    [IO.File]::WriteAllText($temporary,(Convert-Compact $Summary),$utf8)
    if (Test-Path -LiteralPath $latestPath) { [IO.File]::Replace($temporary,$latestPath,[NullString]::Value) }
    else { [IO.File]::Move($temporary,$latestPath) }
}

if ($MaxDurationMinutes -lt $DurationMinutes -and -not $Once) { throw 'MaxDurationMinutes must be at least DurationMinutes.' }
$repoRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\','/')
$localRoot = Join-Path $repoRoot '.local'
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory).TrimEnd('\','/')
if (-not $outputRoot.StartsWith($localRoot + '\',[StringComparison]::OrdinalIgnoreCase)) { throw 'OutputDirectory must be a new directory beneath this repository .local directory.' }
Assert-NoReparsePath $outputRoot
if (Test-Path -LiteralPath $outputRoot) { throw 'OutputDirectory already exists; select a new run directory.' }
$instanceRoot = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $InstancePath).Path).TrimEnd('\','/')
Assert-NoReparsePath $instanceRoot
$manifest = Read-BoundedJson (Join-Path $instanceRoot 'manifest.json')
if ((Get-Field $manifest 'name') -notmatch '(?i)^Society(?:\s|$)' -or $manifest.minecraft.version -ne '1.20.1' -or @($manifest.minecraft.modLoaders.id) -notcontains 'forge-47.4.0') {
    throw 'Select a verified Society Minecraft 1.20.1 / Forge 47.4.0 instance.'
}
$directory = Join-Path $instanceRoot 'config\autovalley'
if (-not (Test-Path -LiteralPath $directory -PathType Container) -or -not (Test-Path -LiteralPath (Join-Path $instanceRoot 'mods') -PathType Container)) { throw 'Instance mods or Auto Valley config directory is missing.' }
Assert-NoReparsePath $directory
if (-not $ProfileKey) {
    $profiles = @(Get-ChildItem -LiteralPath $directory -File | Where-Object { $_.Name -match '^[a-f0-9]{24}\.json$' })
    if ($profiles.Count -ne 1) { throw 'Specify ProfileKey explicitly when the instance has zero or multiple profiles.' }
    $ProfileKey = [IO.Path]::GetFileNameWithoutExtension($profiles[0].Name)
}
$profilePath = Join-Path $directory ($ProfileKey + '.json')
Assert-NoReparsePath $profilePath
$null = Read-BoundedJson $profilePath 2000000
$requestPath = Join-Path $directory 'inspect.request'
$diagnosticPath = Join-Path $directory 'diagnostics.json'
$script:expectedStartUtc = $null
$identity = Get-GameIdentity
if ($identity.state -ne 'ALIVE') { throw ('Exact Society javaw.exe process guard failed: ' + $identity.state) }
$script:expectedStartUtc = $identity.startUtc
[IO.Directory]::CreateDirectory($outputRoot) | Out-Null
$observationsPath = Join-Path $outputRoot 'observations.jsonl'
$latestPath = Join-Path $outputRoot 'latest.json'
$startedUtc = [DateTimeOffset]::UtcNow.ToString('o')
$clock = [Diagnostics.Stopwatch]::StartNew()
$script:lastCapture = $null
$previous = $null
$seenFailures = @{}
$initialFailureCount = 0
$recoveryRequired = $false
$initialLoggingLease = $false
$initialWorkLease = $false
$cleanStart = $null
$cleanStartUtc = $null
$cleanProgressCount = 0
$lastProgressAt = $null
$longestClean = [double]0
$samples = 0
$freshSamples = 0
$interruptions = 0
$newFailureEvents = [long]0
$progressEvents = 0
$freshRunningStart = $null
$freshRunningProgressCount = 0
$freshRunningFirstDay = $null
$freshRunningLatestDay = $null
$noNewFailureStart = $null
$failureCountsByFeature = @{}
$initialEnabled = $null
$loggingActiveStart = $null
$loggingPendingStart = $null
$loggingProgressSamples = 0
$loggingObservedCompletions = 0
$previousElapsed = $null
$outcome = 'COLLECTING'

do {
    $inspection = Request-FreshInspection
    $snapshot = $inspection.snapshot
    $elapsed = $clock.Elapsed.TotalSeconds
    $samples++
    $reasons = [Collections.Generic.List[string]]::new()
    $progressSources = [Collections.Generic.List[string]]::new()
    $newFailures = [Collections.Generic.List[object]]::new()
    $history = @()
    $profileEvidence = $null
    $inventory = $null
    $recovered = $false
    $requiredFeaturesSatisfied = $false
    $gap = $null -ne $previousElapsed -and ($elapsed - $previousElapsed) -gt ($IntervalSeconds + $InspectionTimeoutSeconds + 5)
    if ($gap) { $reasons.Add('OBSERVATION_GAP') }
    if (-not $inspection.fresh) { $reasons.Add($inspection.reason) }
    else {
        $freshSamples++
        if ((Get-Field $snapshot 'connected') -ne $true) { $reasons.Add('DISCONNECTED') }
        if ((Get-Field $snapshot 'running') -ne $true) { $reasons.Add('AUTOMATION_NOT_RUNNING') }
        if ((Get-Field $snapshot 'executionMode') -ne 'CONTINUOUS') { $reasons.Add('NOT_CONTINUOUS_AUTOMATION') }
        if ((Get-Field $snapshot 'recording') -ne $false -or (Get-Field $snapshot 'recordingActive') -ne $false) { $reasons.Add('RECORDING_OR_MANUAL_MODE') }
        if ([string](Get-Field $snapshot 'status' '') -match '(?i)\b(error|blocked|paused|failed|failure|stuck)\b|\uC2E4\uD328|\uC624\uB958|\uC911\uC9C0|\uC77C\uC2DC.?\uC815\uC9C0') { $reasons.Add('FAILURE_STATUS') }
        if ($null -eq $snapshot.PSObject.Properties['failureHistory']) { $reasons.Add('FAILURE_HISTORY_UNAVAILABLE') }
        $history = @(Get-Field $snapshot 'failureHistory' @() | Select-Object -Last 16 | ForEach-Object {
            $message = [string](Get-Field $_ 'message' '')
            if ($message.Length -gt 512) { $message = $message.Substring(0,512) }
            [pscustomobject]@{ tick=(Get-Field $_ 'tick'); lastTick=(Get-Field $_ 'lastTick'); occurrences=(Get-Field $_ 'occurrences' 1); feature=(Get-Field $_ 'feature'); state=(Get-Field $_ 'state'); message=$message }
        })
        $currentFailures = @{}
        foreach ($failure in $history) {
            $key = Get-Digest (Convert-Compact @($failure.tick,$failure.feature,$failure.state,$failure.message))
            $currentFailures[$key] = $failure
            if ($freshSamples -gt 1 -and (-not $seenFailures.ContainsKey($key) -or $failure.occurrences -ne $seenFailures[$key].occurrences -or $failure.lastTick -ne $seenFailures[$key].lastTick)) {
                $delta = if ($seenFailures.ContainsKey($key)) { [Math]::Max(1,([long]$failure.occurrences - [long]$seenFailures[$key].occurrences)) } else { [long]$failure.occurrences }
                $newFailureEvents += $delta
                $featureKey = [string]$failure.feature
                if (-not $featureKey) { $featureKey='UNASSIGNED' }
                if (-not $failureCountsByFeature.ContainsKey($featureKey)) { $failureCountsByFeature[$featureKey]=[long]0 }
                $failureCountsByFeature[$featureKey] += $delta
                $newFailures.Add($failure)
            }
        }
        if ($freshSamples -eq 1) { $initialFailureCount=$history.Count; $recoveryRequired=($history.Count -gt 0) }
        if ($newFailures.Count -gt 0) { $reasons.Add('NEW_FAILURE_HISTORY_EVENT'); $recoveryRequired=$true }
        if ($freshSamples -gt 1 -and $seenFailures.Count -gt 0 -and $history.Count -eq 0) { $reasons.Add('FAILURE_HISTORY_RESET'); $recoveryRequired=$true }
        $seenFailures = $currentFailures
        try { $profileEvidence = Get-ProfileEvidence } catch { $reasons.Add('PROFILE_OBSERVATION_UNAVAILABLE') }
        if ($null -ne $profileEvidence) {
            if ($null -eq $initialEnabled) { $initialEnabled=$profileEvidence.enabled }
            $requiredFeaturesSatisfied=$true
            foreach ($feature in $RequiredFeature) {
                if ((Get-Field $profileEvidence.enabled $feature) -ne $true) {
                    $reasons.Add('REQUIRED_FEATURE_DISABLED:' + $feature); $requiredFeaturesSatisfied=$false
                }
            }
            if ($profileEvidence.logging.active) {
                if ($null -eq $loggingActiveStart) { $loggingActiveStart=$elapsed }
            } else { $loggingActiveStart=$null }
            if ($profileEvidence.logging.remainingCount -gt 0 -or $profileEvidence.logging.replantingCount -gt 0 -or $null -ne $profileEvidence.logging.lease) {
                if ($null -eq $loggingPendingStart) { $loggingPendingStart=$elapsed }
            } else { $loggingPendingStart=$null }
        }
        $inventory = Get-InventoryEvidence $snapshot
        if ($freshSamples -eq 1 -and $null -ne $profileEvidence) {
            $initialLoggingLease = $null -ne $profileEvidence.logging.lease
            $initialWorkLease = $null -ne $profileEvidence.workHotbarLease
            if ($initialLoggingLease -or $initialWorkLease) { $recoveryRequired=$true }
        }
        if ($newFailures.Count -gt 0 -and $null -ne $profileEvidence) {
            $initialLoggingLease = $initialLoggingLease -or $null -ne $profileEvidence.logging.lease
            $initialWorkLease = $initialWorkLease -or $null -ne $profileEvidence.workHotbarLease
        }
        if ($null -ne $previous -and $null -ne $profileEvidence) {
            if ($profileEvidence.schedule.digest -ne $previous.schedule) { $progressSources.Add('SCHEDULE_CHANGED') }
            if ($inventory.digest -ne $previous.inventory) { $progressSources.Add('INVENTORY_COUNTS_CHANGED') }
            if ($profileEvidence.logging.pendingDigest -ne $previous.logging) { $progressSources.Add('LOGGING_PENDING_CHANGED') }
            if ($profileEvidence.crystalRefillDigest -ne $previous.crystals) { $progressSources.Add('CRYSTAL_REFILL_CHANGED') }
            if ($profileEvidence.logging.pendingDigest -ne $previous.logging -or $profileEvidence.logging.dueDay -ne $previous.loggingDue) { $loggingProgressSamples++ }
            if ($null -ne $profileEvidence.logging.dueDay -and $null -ne $previous.loggingDue -and $profileEvidence.logging.dueDay -gt $previous.loggingDue -and -not $profileEvidence.logging.active -and $profileEvidence.logging.remainingCount -eq 0 -and $profileEvidence.logging.replantingCount -eq 0 -and $null -eq $profileEvidence.logging.lease) { $loggingObservedCompletions++ }
        }
        if ($reasons.Count -eq 0 -and $progressSources.Count -gt 0) {
            $lastProgressAt=$elapsed; $progressEvents++
            if ($recoveryRequired -and (-not $initialLoggingLease -or $null -eq $profileEvidence.logging.lease) -and (-not $initialWorkLease -or $null -eq $profileEvidence.workHotbarLease)) {
                $recoveryRequired=$false; $recovered=$true
            }
        }
        if ($recoveryRequired) { $reasons.Add('RECOVERY_NOT_YET_OBSERVED') }
        if ($null -eq $lastProgressAt) { $reasons.Add('WORK_PROGRESS_NOT_YET_OBSERVED') }
        elseif (($elapsed - $lastProgressAt) -gt ($MaxNoProgressMinutes * 60)) { $reasons.Add('NO_RECENT_WORK_PROGRESS') }
        if ($null -ne $profileEvidence) {
            $previous = [pscustomobject]@{ schedule=$profileEvidence.schedule.digest; inventory=$inventory.digest; logging=$profileEvidence.logging.pendingDigest; loggingDue=$profileEvidence.logging.dueDay; crystals=$profileEvidence.crystalRefillDigest }
        }
    }
    if (-not $inspection.fresh -or $null -eq $profileEvidence -or $gap) { $loggingActiveStart=$null; $loggingPendingStart=$null }
    $observedDayTime = Get-Field (Get-Field $snapshot 'player') 'dayTime'
    $observedDay = if ($null -eq $observedDayTime) { $null } else { [Math]::Floor([double]$observedDayTime/24000) }
    $freshRunning = $inspection.fresh -and -not $gap -and $requiredFeaturesSatisfied -and (Get-Field $snapshot 'connected') -eq $true -and (Get-Field $snapshot 'running') -eq $true -and (Get-Field $snapshot 'executionMode') -eq 'CONTINUOUS' -and (Get-Field $snapshot 'recording') -eq $false -and (Get-Field $snapshot 'recordingActive') -eq $false
    if ($freshRunning) {
        if ($null -eq $freshRunningStart) { $freshRunningStart=$elapsed; $freshRunningProgressCount=0; $freshRunningFirstDay=$observedDay }
        $freshRunningLatestDay=$observedDay
        if ($progressSources.Count -gt 0) { $freshRunningProgressCount++ }
    } else { $freshRunningStart=$null; $freshRunningProgressCount=0; $freshRunningFirstDay=$null; $freshRunningLatestDay=$null }
    $freshRunningSeconds = if ($null -eq $freshRunningStart) { [double]0 } else { $elapsed-$freshRunningStart }
    $daysAdvanced = if ($null -eq $freshRunningFirstDay -or $null -eq $freshRunningLatestDay) { 0 } else { $freshRunningLatestDay-$freshRunningFirstDay }
    if ($inspection.fresh -and -not $gap -and $newFailures.Count -eq 0 -and -not $reasons.Contains('FAILURE_HISTORY_RESET')) {
        if ($null -eq $noNewFailureStart) { $noNewFailureStart=$elapsed }
    } else { $noNewFailureStart=$null }
    $noNewFailureSeconds = if ($null -eq $noNewFailureStart) { [double]0 } else { $elapsed-$noNewFailureStart }
    $clean = $reasons.Count -eq 0
    if ($clean) {
        if ($null -eq $cleanStart) { $cleanStart=$elapsed; $cleanStartUtc=[DateTimeOffset]::UtcNow.ToString('o'); $cleanProgressCount=0 }
        if ($progressSources.Count -gt 0) { $cleanProgressCount++ }
    } else {
        if ($null -ne $cleanStart) { $interruptions++ }
        $cleanStart=$null; $cleanStartUtc=$null; $cleanProgressCount=0
    }
    $cleanSeconds = if ($null -eq $cleanStart) { [double]0 } else { $elapsed - $cleanStart }
    $longestClean = [Math]::Max($longestClean,$cleanSeconds)
    $requiredFeatureWorkObserved = $RequiredFeature -notcontains 'LOGGING' -or $loggingObservedCompletions -ge 1
    $verified = -not $Once -and $clean -and $cleanSeconds -ge ($DurationMinutes * 60) -and $elapsed -ge ($DurationMinutes * 60) -and $cleanProgressCount -ge 2 -and $requiredFeatureWorkObserved
    # A running candidate can include recoverable retries. It is evidence for a human
    # audit, never an automatic declaration that those failures were harmless.
    $candidate = -not $Once -and $freshRunningSeconds -ge ($DurationMinutes * 60) -and $freshRunningProgressCount -ge 2 -and $daysAdvanced -ge 1 -and -not $recoveryRequired -and $null -ne $lastProgressAt -and ($elapsed-$lastProgressAt) -le ($MaxNoProgressMinutes*60) -and $requiredFeatureWorkObserved
    $terminal = $inspection.identity.state -in @('PROCESS_EXITED','PROCESS_REPLACED','PROCESS_IDENTITY_CHANGED','GAME_DIRECTORY_MISMATCH','GAME_DIRECTORY_UNVERIFIED')
    if ($verified) { $outcome='VERIFIED_SAMPLED_STABILITY' }
    elseif ($terminal) { $outcome=$inspection.identity.state }
    elseif ($Once) { $outcome=if ($inspection.fresh) { 'ONCE_FRESH_OBSERVATION' } else { 'ONCE_OBSERVATION_UNAVAILABLE' } }
    elseif ($clock.Elapsed.TotalMinutes -ge $MaxDurationMinutes) { $outcome='MAX_DURATION_WITHOUT_VERIFIED_WINDOW' }
    $player = Get-Field $snapshot 'player'
    $dayTime = Get-Field $player 'dayTime'
    $crystals = Get-Field $snapshot 'crystals'
    $row = [pscustomobject][ordered]@{
        observedAtUtc=[DateTimeOffset]::UtcNow.ToString('o'); sample=$samples; elapsedSeconds=[Math]::Round($elapsed,3)
        processId=$GameProcessId; processStartUtc=$inspection.identity.startUtc; processState=$inspection.identity.state
        requestedAtUtc=$inspection.requestedAt; fresh=$inspection.fresh; capturedAt=(Get-Field $snapshot 'capturedAt')
        connected=(Get-Field $snapshot 'connected'); running=(Get-Field $snapshot 'running'); executionMode=(Get-Field $snapshot 'executionMode'); status=(Get-Field $snapshot 'status')
        recording=(Get-Field $snapshot 'recording'); recordingActive=(Get-Field $snapshot 'recordingActive'); recordingStatus=(Get-Field $snapshot 'recordingStatus')
        screenClass=(Get-Field $snapshot 'screenClass'); player=$player; gameDay=$(if ($null -eq $dayTime) { $null } else { [Math]::Floor([double]$dayTime/24000) })
        navigation=(Get-Field $snapshot 'navigation'); inventory=$inventory; profile=$profileEvidence
        runtimeLogging=(Get-Field $snapshot 'logging')
        crystalPendingRefills=@(Get-Field $crystals 'pendingRefills' @() | Select-Object -First 32)
        crystalRecentServerReplies=@(Get-Field $crystals 'recentServerReplies' @() | Select-Object -Last 8)
        workHotbar=(Get-Field $snapshot 'workHotbar'); failureHistory=$history; newlyObservedFailures=@($newFailures.ToArray())
        recoveryObserved=$recovered; progressSources=@($progressSources.ToArray()); clean=$clean; reasons=@($reasons.ToArray()); cleanSeconds=[Math]::Round($cleanSeconds,3)
        uninterruptedFreshRunningSeconds=[Math]::Round($freshRunningSeconds,3); noNewFailureSeconds=[Math]::Round($noNewFailureSeconds,3)
        requiredFeaturesSatisfied=$requiredFeaturesSatisfied
        loggingActiveSeconds=$(if ($null -eq $loggingActiveStart) { 0 } else { [Math]::Round($elapsed-$loggingActiveStart,3) })
        loggingPendingSeconds=$(if ($null -eq $loggingPendingStart) { 0 } else { [Math]::Round($elapsed-$loggingPendingStart,3) })
    }
    $summary = [pscustomobject][ordered]@{
        startedAtUtc=$startedUtc; updatedAtUtc=$row.observedAtUtc; processId=$GameProcessId; processStartUtc=$script:expectedStartUtc
        outcome=$outcome; verified=$verified; requiredCleanMinutes=$DurationMinutes; maximumRunMinutes=$MaxDurationMinutes
        intervalSeconds=$IntervalSeconds; maxNoProgressMinutes=$MaxNoProgressMinutes; elapsedMinutes=[Math]::Round($elapsed/60,3)
        cleanStartedAtUtc=$cleanStartUtc; cleanMinutes=[Math]::Round($cleanSeconds/60,3); longestCleanMinutes=[Math]::Round($longestClean/60,3)
        samples=$samples; freshSamples=$freshSamples; cleanWindowInterruptions=$interruptions; initialFailureCount=$initialFailureCount
        newFailureOccurrences=$newFailureEvents; recoveryRequired=$recoveryRequired; workProgressSamples=$progressEvents; cleanWindowWorkProgressSamples=$cleanProgressCount
        uninterruptedFreshRunningSeconds=[Math]::Round($freshRunningSeconds,3); noNewFailureSeconds=[Math]::Round($noNewFailureSeconds,3)
        freshRunningWindowWorkProgressSamples=$freshRunningProgressCount; freshRunningWindowDaysAdvanced=$daysAdvanced
        candidateVerifiedRunningWindow=$candidate; needsAudit=($candidate -and -not $verified); newFailureOccurrencesByFeature=$failureCountsByFeature
        requiredFeatures=$RequiredFeature; initialEnabled=$initialEnabled; loggingProgressSamples=$loggingProgressSamples; loggingObservedCompletions=$loggingObservedCompletions
        requiredFeatureWorkObserved=$requiredFeatureWorkObserved
        limitation='Fresh sampled evidence, not proof of all events between samples. Disk profile is observed separately; inventory or schedule changes are progress evidence, not individual action receipts.'
        latest=$row
    }
    Write-Evidence $row $summary
    if ($Once -or $outcome -ne 'COLLECTING') { break }
    $previousElapsed=$elapsed
    # Short waits allow immediate process cancellation; no hidden game-control loop.
    $nextSample = [Math]::Min($elapsed + $IntervalSeconds,$MaxDurationMinutes * 60)
    while ($clock.Elapsed.TotalSeconds -lt $nextSample) { Start-Sleep -Milliseconds 250 }
} while ($true)

[pscustomobject]@{ outcome=$outcome; verified=$verified; elapsedMinutes=$summary.elapsedMinutes; cleanMinutes=$summary.cleanMinutes; latestPath=$latestPath; observationsPath=$observationsPath } | ConvertTo-Json -Compress
if ($Once -and $inspection.fresh) { exit 0 }
if ($verified) { exit 0 }
exit 2
