<# Isolated synthetic-observation checks. Never inspects or writes a game instance. #>
$ErrorActionPreference='Stop'
Set-StrictMode -Version 2
$scriptPath=Join-Path $PSScriptRoot 'Watch-SocietyStability.ps1'
$parseErrors=$null; $tokens=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($scriptPath,[ref]$tokens,[ref]$parseErrors)
if ($parseErrors.Count -gt 0) { throw 'Monitor PowerShell parse failed.' }
foreach ($definition in $ast.EndBlock.Statements | Where-Object { $_ -is [Management.Automation.Language.FunctionDefinitionAst] }) {
    . ([scriptblock]::Create($definition.Extent.Text))
}
$utf8=[Text.UTF8Encoding]::new($false)
$checks=0
function Assert-Fixture([bool]$Condition,[string]$Message) {
    if (-not $Condition) { throw ('Synthetic fixture failed: ' + $Message) }
    $script:checks++
}

# Check exact identity without invoking WMI/CIM or exposing any actual command line.
$GameProcessId=1234; $instanceRoot='C:\Fixture\Society Sunlit Valley'; $script:expectedStartUtc=$null
$script:fixtureProcess=[pscustomobject]@{ Name='javaw.exe'; CreationDate=[DateTime]::Parse('2026-01-01T00:00:00Z').ToUniversalTime(); CommandLine='javaw.exe --gameDir "C:\Fixture\Society Sunlit Valley" --accessToken fixture-only' }
$script:fixtureCimFails=$false
function Get-CimInstance { param($ClassName,$Filter,$ErrorAction) if ($script:fixtureCimFails) { throw 'fixture unavailable' }; return $script:fixtureProcess }
Assert-Fixture ((Get-GameIdentity).state -eq 'ALIVE') 'Correct javaw and exact quoted gameDir must pass.'
$script:expectedStartUtc=(Get-GameIdentity).startUtc
$script:fixtureProcess.CommandLine='javaw.exe --gameDir "C:\Fixture\Society Sunlit Valley-copy"'
Assert-Fixture ((Get-GameIdentity).state -eq 'GAME_DIRECTORY_MISMATCH') 'Substring directory matches must fail.'
$script:fixtureProcess.CommandLine='javaw.exe --gameDir "C:\Fixture\Society Sunlit Valley"'
$script:fixtureProcess.CreationDate=$script:fixtureProcess.CreationDate.AddMinutes(1)
Assert-Fixture ((Get-GameIdentity).state -eq 'PROCESS_REPLACED') 'PID reuse must fail.'
$script:fixtureCimFails=$true
Assert-Fixture ((Get-GameIdentity).state -eq 'PROCESS_CHECK_UNAVAILABLE') 'CIM failure is not process termination.'
$script:fixtureCimFails=$false; $script:fixtureProcess=$null
Assert-Fixture ((Get-GameIdentity).state -eq 'PROCESS_EXITED') 'Only absent exact PID reports process exit.'
$sampleInventory=[pscustomobject]@{ inventory=@([pscustomobject]@{item=[pscustomobject]@{id='fixture:log';count=3}},[pscustomobject]@{item=[pscustomobject]@{id='fixture:log';count=4}},[pscustomobject]@{item=[pscustomobject]@{id='minecraft:air';count=0}}) }
Assert-Fixture ((Get-InventoryEvidence $sampleInventory).counts['fixture:log'] -eq 7) 'Inventory counts aggregate occupied stacks.'

# Exercise request freshness and atomic output only in an ignored fixture directory.
$fixtureRoot=Join-Path (Split-Path -Parent $PSScriptRoot) ('.local\stability-script-fixture-' + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($fixtureRoot) | Out-Null
$profilePath=Join-Path $fixtureRoot 'fixture-profile.json'
[IO.File]::WriteAllText($profilePath,'{"nextEligibleDay":{"logging:batch":12,"wine:1:2:3":14},"loggingRemainingPlots":[],"loggingReplantingPlots":[],"loggingHotbarLease":null,"crystalRefills":{},"enabled":{"LOGGING":true}}',$utf8)
$diskProfile=Get-ProfileEvidence
Assert-Fixture ($diskProfile.schedule.count -eq 2 -and $diskProfile.logging.dueDay -eq 12 -and $diskProfile.logging.remainingCount -eq 0) 'Disk evidence captures exact logging due day and schedule counts.'
$diagnosticPath=Join-Path $fixtureRoot 'diagnostics.json'
$requestPath=Join-Path $fixtureRoot 'inspect.request'
$InspectionTimeoutSeconds=2; $Once=$true; $script:lastCapture=$null
$script:expectedStartUtc=$null
$script:fixtureProcess=[pscustomobject]@{ Name='javaw.exe'; CreationDate=[DateTime]::Parse('2026-01-01T00:00:00Z').ToUniversalTime(); CommandLine='javaw.exe --gameDir "C:\Fixture\Society Sunlit Valley"' }
$script:productionReadBoundedJson=${function:Read-BoundedJson}
$script:diagnosticBodyReads=0; $script:fixtureAfterRead=$null
function Read-BoundedJson {
    param([string]$Path,[long]$Limit=8000000)
    if ($Path -eq $diagnosticPath) { $script:diagnosticBodyReads++ }
    $value=& $script:productionReadBoundedJson $Path $Limit
    if ($Path -eq $diagnosticPath -and $null -ne $script:fixtureAfterRead) { & $script:fixtureAfterRead }
    return $value
}
function Start-Sleep {
    param($Milliseconds)
    [IO.File]::WriteAllText($diagnosticPath,('{"capturedAt":"' + [DateTimeOffset]::UtcNow.ToString('o') + '","connected":true}'),$utf8)
}
$freshFixture=Request-FreshInspection
Assert-Fixture ($freshFixture.fresh -and (Test-Path -LiteralPath $requestPath)) 'Once creates only the inspection marker and accepts a newly captured diagnostic.'
Assert-Fixture ($script:diagnosticBodyReads -eq 1) 'A missing baseline followed by a fresh candidate must read the body exactly once.'
Remove-Item Function:\Start-Sleep
$script:diagnosticBodyReads=0
$staleFixture=Request-FreshInspection
Assert-Fixture (-not $staleFixture.fresh -and $staleFixture.reason -eq 'STALE_DIAGNOSTIC_PROCESS_ALIVE') 'An old capture with live PID times out without reporting termination.'
Assert-Fixture ($script:diagnosticBodyReads -eq 0) 'Unmodified diagnostics must not be opened during initial baseline or stale polling.'

# Publish only to this synthetic directory. Per-candidate reads are counted at the
# real bounded reader boundary; metadata polling never opens the JSON body.
function Write-InspectionFixture([DateTimeOffset]$CapturedAt) {
    [IO.File]::WriteAllText($diagnosticPath,('{"capturedAt":"'+$CapturedAt.ToString('o')+'","connected":true}'),$utf8)
    [IO.File]::SetLastWriteTimeUtc($diagnosticPath,[DateTime]::UtcNow)
}
$script:fixturePoll=0; $script:fixturePublisher=$null
function Start-Sleep {
    param($Milliseconds)
    $script:fixturePoll++
    if ($null -ne $script:fixturePublisher) { & $script:fixturePublisher }
    Microsoft.PowerShell.Utility\Start-Sleep -Milliseconds $Milliseconds
}
function Invoke-InspectionFixture($Publisher) {
    $script:fixturePoll=0; $script:diagnosticBodyReads=0; $script:fixturePublisher=$Publisher
    return Request-FreshInspection
}
$requestWriteBefore=[IO.File]::GetLastWriteTimeUtc($requestPath)
$oldCapture=[DateTimeOffset]::Parse('2000-01-01T00:00:00Z')
Write-InspectionFixture $oldCapture
$sameLength=(Get-Item -LiteralPath $diagnosticPath).Length
$candidateFixture=Invoke-InspectionFixture { if ($script:fixturePoll -eq 1) { Write-InspectionFixture ([DateTimeOffset]::UtcNow) } }
Assert-Fixture ($candidateFixture.fresh -and $script:diagnosticBodyReads -eq 1 -and (Get-Item -LiteralPath $diagnosticPath).Length -eq $sameLength) 'A changed mtime must admit a fresh same-length replacement exactly once.'
Write-InspectionFixture ([DateTimeOffset]::UtcNow.AddDays(1))
$candidateFixture=Invoke-InspectionFixture $null
Assert-Fixture (-not $candidateFixture.fresh -and $script:diagnosticBodyReads -eq 0) 'Even a preexisting future capture must remain unread when metadata is unchanged.'
Write-InspectionFixture $oldCapture
$candidateFixture=Invoke-InspectionFixture { if ($script:fixturePoll -eq 1) { [IO.File]::SetLastWriteTimeUtc($diagnosticPath,[DateTime]::UtcNow) } }
Assert-Fixture (-not $candidateFixture.fresh -and $script:diagnosticBodyReads -eq 1) 'Mtime-only touch with an old capturedAt must fail and must not reopen the same candidate.'
$candidateFixture=Invoke-InspectionFixture { if ($script:fixturePoll -eq 1) { Write-InspectionFixture ([DateTimeOffset]::UtcNow.AddDays(1)) } }
Assert-Fixture (-not $candidateFixture.fresh -and $script:diagnosticBodyReads -eq 1) 'A newly published future capture must fail the existing future-time guard.'
$candidateFixture=Invoke-InspectionFixture { if ($script:fixturePoll -eq 1) { Write-InspectionFixture ([DateTimeOffset]::UtcNow); [IO.File]::SetLastWriteTimeUtc($diagnosticPath,[DateTime]::UtcNow.AddMinutes(-5)) } }
Assert-Fixture (-not $candidateFixture.fresh -and $script:diagnosticBodyReads -eq 0) 'Changed metadata predating the request must not open even a fresh-looking body.'
$candidateFixture=Invoke-InspectionFixture { if ($script:fixturePoll -eq 1) { $duplicate=[DateTimeOffset]::UtcNow; Write-InspectionFixture $duplicate; $script:lastCapture=$duplicate.ToString('o') } }
Assert-Fixture (-not $candidateFixture.fresh -and $script:diagnosticBodyReads -eq 1) 'The previous accepted capture cannot be accepted again after metadata changes.'
$script:lastCapture=$null
$candidateFixture=Invoke-InspectionFixture { if ($script:fixturePoll -eq 1) { [IO.File]::WriteAllText($diagnosticPath,'{',$utf8) } }
Assert-Fixture (-not $candidateFixture.fresh -and $script:diagnosticBodyReads -eq 1) 'A malformed candidate is rejected and read at most once for its metadata version.'
$candidateFixture=Invoke-InspectionFixture {
    if ($script:fixturePoll -eq 1) { [IO.File]::WriteAllText($diagnosticPath,'{bad',$utf8) }
    if ($script:fixturePoll -eq 3) { Write-InspectionFixture ([DateTimeOffset]::UtcNow) }
}
Assert-Fixture ($candidateFixture.fresh -and $script:diagnosticBodyReads -eq 2) 'Rejecting one metadata version must not suppress a later valid replacement in the same bounded request.'
$candidateFixture=Invoke-InspectionFixture { if ($script:fixturePoll -eq 1) { $large=[IO.File]::Open($diagnosticPath,[IO.FileMode]::Create,[IO.FileAccess]::Write); try { $large.SetLength(8000001) } finally { $large.Dispose() } } }
Assert-Fixture (-not $candidateFixture.fresh -and $script:diagnosticBodyReads -eq 0) 'Oversized metadata must be rejected before any diagnostic body read.'
Write-InspectionFixture $oldCapture
$boundedRejected=$false; try { $null=Read-BoundedJson $diagnosticPath 1 } catch { $boundedRejected=$true }
Assert-Fixture $boundedRejected 'Direct JSON reads retain the caller-supplied byte-size bound.'
$script:fixtureReparse=$true
function Get-Item {
    param([string]$LiteralPath,[switch]$Force)
    if ($LiteralPath -eq $diagnosticPath -and $script:fixtureReparse) {
        return [pscustomobject]@{Attributes=[IO.FileAttributes]::ReparsePoint;Length=1;LastWriteTimeUtc=[DateTime]::UtcNow}
    }
    return Microsoft.PowerShell.Management\Get-Item -LiteralPath $LiteralPath -Force:$Force
}
$candidateFixture=Invoke-InspectionFixture $null
Assert-Fixture (-not $candidateFixture.fresh -and $script:diagnosticBodyReads -eq 0) 'Reparse-point metadata must be rejected before any diagnostic body read.'
$reparseRejected=$false; try { $null=Read-BoundedJson $diagnosticPath } catch { $reparseRejected=$true }
Assert-Fixture $reparseRejected 'The body reader must independently retain the reparse-point guard.'
Remove-Item Function:\Get-Item
$directoryRejected=$false; try { $null=Get-BoundedFileMetadata $fixtureRoot } catch { $directoryRejected=$true }
Assert-Fixture $directoryRejected 'Metadata must describe a regular file, not a directory.'
$script:expectedStartUtc=(Get-GameIdentity).startUtc
$script:fixtureAfterRead={ $script:fixtureProcess.CreationDate=$script:fixtureProcess.CreationDate.AddMinutes(1) }
$candidateFixture=Invoke-InspectionFixture { if ($script:fixturePoll -eq 1) { Write-InspectionFixture ([DateTimeOffset]::UtcNow) } }
Assert-Fixture (-not $candidateFixture.fresh -and $candidateFixture.reason -eq 'PROCESS_REPLACED' -and $script:diagnosticBodyReads -eq 1) 'A fresh candidate must still recheck exact PID/start identity after reading.'
$script:fixtureAfterRead=$null; $script:expectedStartUtc=$null
Assert-Fixture ([IO.File]::GetLastWriteTimeUtc($requestPath) -eq $requestWriteBefore) 'Candidate rejection must not rewrite or reissue the single existing inspection request.'
Remove-Item Function:\Start-Sleep
Set-Item Function:\Read-BoundedJson -Value $script:productionReadBoundedJson
$outputRoot=$fixtureRoot; $observationsPath=Join-Path $fixtureRoot 'observations.jsonl'; $latestPath=Join-Path $fixtureRoot 'latest.json'
Write-Evidence ([pscustomobject]@{sample=1}) ([pscustomobject]@{sample=1})
Write-Evidence ([pscustomobject]@{sample=2}) ([pscustomobject]@{sample=2})
Assert-Fixture ((Read-BoundedJson $latestPath).sample -eq 2 -and @(Get-Content -LiteralPath $observationsPath).Count -eq 2) 'Latest is atomically replaceable while both JSONL observations are retained.'

# Exercise the production loop body with a synthetic monotonic clock. This checks
# decisions only and is explicitly not elapsed-time evidence from a real game.
$loop=@($ast.EndBlock.Statements | Where-Object { $_ -is [Management.Automation.Language.DoWhileStatementAst] })[0]
$initialization=@(); $collect=$false
foreach ($statement in $ast.EndBlock.Statements) {
    if ($statement -eq $loop) { break }
    if ($statement -is [Management.Automation.Language.AssignmentStatementAst] -and $statement.Left.Extent.Text -eq '$startedUtc') { $collect=$true }
    if ($collect) { $initialization += $statement.Extent.Text }
}
$initialize=[scriptblock]::Create($initialization -join [Environment]::NewLine)
$body=@()
foreach ($statement in $loop.Body.Statements) {
    if ($statement.Extent.Text.StartsWith('if ($Once -or $outcome')) { break }
    $body += $statement.Extent.Text
}
$sampleBody=[scriptblock]::Create(($body -join [Environment]::NewLine) + [Environment]::NewLine + '$previousElapsed=$elapsed')
$Once=$false; $DurationMinutes=120; $MaxDurationMinutes=240; $IntervalSeconds=30; $InspectionTimeoutSeconds=10; $MaxNoProgressMinutes=10; $RequiredFeature=@('LOGGING')
$script:fixtureFresh=$true
function Request-FreshInspection {
    return [pscustomobject]@{ identity=[pscustomobject]@{state='ALIVE';startUtc='2026-01-01T00:00:00Z'}; fresh=$script:fixtureFresh; reason='STALE_DIAGNOSTIC_PROCESS_ALIVE'; snapshot=$(if ($script:fixtureFresh) {$script:fixtureSnapshot} else {$null}); requestedAt='2026-01-01T00:00:00Z' }
}
function Get-ProfileEvidence { return $script:fixtureProfile }
function Write-Evidence($Row,$Summary) { $script:fixtureRow=$Row; $script:fixtureSummary=$Summary }
function New-Fixtures {
    $script:fixtureSnapshot=[pscustomobject]@{connected=$true;running=$true;executionMode='CONTINUOUS';recording=$false;recordingActive=$false;status='Working';failureHistory=@();inventory=@();player=[pscustomobject]@{dayTime=24000;x=1;y=64;z=1};capturedAt='2026-01-01T00:00:00Z'}
    $script:fixtureProfile=[pscustomobject]@{enabled=[pscustomobject]@{LOGGING=$true};schedule=[pscustomobject]@{digest='first'};logging=[pscustomobject]@{enabled=$true;active=$false;remainingCount=0;replantingCount=0;pendingDigest='first';lease=$null;dueDay=1};workHotbarLease=$null;crystalRefillDigest='first'}
    $script:fixtureFresh=$true
}
. $initialize
New-Fixtures
$script:fixtureSnapshot.failureHistory=@([pscustomobject]@{tick=10;lastTick=10;occurrences=1;feature='LOGGING';state='BLOCKED';message='Known lease failure'})
$script:fixtureProfile.logging.lease=[pscustomobject]@{stage='PARKED'}
$clock=[pscustomobject]@{Elapsed=[TimeSpan]::FromSeconds(0)}
. $sampleBody
Assert-Fixture (-not $fixtureRow.clean -and $fixtureSummary.initialFailureCount -eq 1 -and $fixtureSummary.recoveryRequired) 'Initial failure must not become clean just because running is true.'
$clock.Elapsed=[TimeSpan]::FromSeconds(30); $fixtureProfile.schedule.digest='second'
. $sampleBody
Assert-Fixture ($fixtureSummary.recoveryRequired) 'Unresolved original lease must block recovery despite other progress.'
$clock.Elapsed=[TimeSpan]::FromSeconds(60); $fixtureProfile.logging.lease=$null; $fixtureProfile.schedule.digest='third'
. $sampleBody
Assert-Fixture ($fixtureRow.clean -and $fixtureRow.recoveryObserved) 'Lease resolution plus fresh work progress can begin a new clean interval.'
$clock.Elapsed=[TimeSpan]::FromSeconds(90); $fixtureSnapshot.failureHistory[0].lastTick=20; $fixtureSnapshot.failureHistory[0].occurrences=2
. $sampleBody
Assert-Fixture (-not $fixtureRow.clean -and $fixtureSummary.newFailureOccurrences -eq 1 -and $fixtureSummary.uninterruptedFreshRunningSeconds -eq 90) 'Repeated failure resets clean time while fresh-running time remains separate.'
$clock.Elapsed=[TimeSpan]::FromSeconds(120); $fixtureProfile.schedule.digest='fourth'
. $sampleBody
Assert-Fixture ($fixtureRow.recoveryObserved -and $fixtureSummary.newFailureOccurrencesByFeature.LOGGING -eq 1) 'Per-feature recurrence and recovery evidence must survive.'
$clock.Elapsed=[TimeSpan]::FromSeconds(150); $fixtureProfile.enabled.LOGGING=$false
. $sampleBody
Assert-Fixture (-not $fixtureRow.clean -and $fixtureSummary.uninterruptedFreshRunningSeconds -eq 0 -and $fixtureRow.reasons -contains 'REQUIRED_FEATURE_DISABLED:LOGGING') 'Disabling a required feature cannot count as stable.'
$clock.Elapsed=[TimeSpan]::FromSeconds(180); $script:fixtureFresh=$false
. $sampleBody
Assert-Fixture ($fixtureRow.processState -eq 'ALIVE' -and $fixtureRow.reasons -contains 'STALE_DIAGNOSTIC_PROCESS_ALIVE' -and -not $fixtureRow.fresh) 'Stale observation must preserve the separate alive process finding.'

. $initialize
New-Fixtures
for ($sample=0; $sample -le 241; $sample++) {
    $clock=[pscustomobject]@{Elapsed=[TimeSpan]::FromSeconds($sample*30)}
    $fixtureProfile.schedule.digest=[string]$sample
    if ($sample -eq 2) { $fixtureProfile.logging.dueDay=2 }
    $fixtureSnapshot.player.dayTime=24000+($sample*600)
    . $sampleBody
    if ($sample -eq 240) { Assert-Fixture (-not $fixtureSummary.verified) '119.5 clean minutes cannot satisfy 120 minutes.' }
}
Assert-Fixture ($fixtureSummary.verified -and $fixtureSummary.cleanMinutes -eq 120 -and $fixtureSummary.candidateVerifiedRunningWindow) '120 clean synthetic minutes with repeated progress satisfies the decision boundary.'

. $initialize
New-Fixtures
for ($sample=0; $sample -le 241; $sample++) {
    $clock=[pscustomobject]@{Elapsed=[TimeSpan]::FromSeconds($sample*30)}
    $fixtureProfile.schedule.digest=[string]$sample
    $fixtureSnapshot.player.dayTime=24000+($sample*600)
    . $sampleBody
}
Assert-Fixture (-not $fixtureSummary.verified -and -not $fixtureSummary.candidateVerifiedRunningWindow -and -not $fixtureSummary.requiredFeatureWorkObserved) 'Enabled but unobserved logging cannot verify even with two hours of other work progress.'

. $initialize
New-Fixtures
for ($sample=0; $sample -le 241; $sample++) {
    $clock=[pscustomobject]@{Elapsed=[TimeSpan]::FromSeconds($sample*30)}
    $fixtureSnapshot.player.dayTime=24000+($sample*600)
    $fixtureSnapshot.player.x=$sample
    . $sampleBody
}
Assert-Fixture (-not $fixtureSummary.verified -and -not $fixtureSummary.candidateVerifiedRunningWindow) 'Alive, moving, advancing game time without work evidence cannot pass.'

foreach ($stopState in @('PAUSED','ERROR','OFF')) {
    . $initialize
    New-Fixtures
    for ($sample=0; $sample -le 3; $sample++) {
        $clock=[pscustomobject]@{Elapsed=[TimeSpan]::FromSeconds($sample*30)}
        $fixtureProfile.schedule.digest=[string]$sample
        if ($sample -eq 2) { $fixtureProfile.logging.dueDay=2 }
        if ($sample -eq 3) {
            $fixtureSnapshot.failureHistory=@([pscustomobject]@{tick=15;lastTick=15;occurrences=1;feature='LOGGING';state=$stopState;message='Screen or manual-input stop between samples'})
        }
        . $sampleBody
    }
    Assert-Fixture ($fixtureRow.running -and $fixtureRow.interveningStopObserved -and $fixtureRow.reasons -contains 'INTERVENING_STOP_EVENT' -and $fixtureSummary.uninterruptedFreshRunningSeconds -eq 0) ($stopState + ' between running samples must interrupt the running window.')
    Assert-Fixture ($fixtureSummary.loggingObservedCompletions -eq 1 -and $fixtureSummary.newFailureOccurrences -eq 1 -and $fixtureSummary.workProgressSamples -eq 2 -and $fixtureRow.failureHistory.Count -eq 1) ($stopState + ' must preserve overall completion, progress, and failure evidence.')
    Assert-Fixture ($fixtureSummary.cleanWindowLoggingCompletions -eq 0 -and $fixtureSummary.freshRunningWindowLoggingCompletions -eq 0) ($stopState + ' must discard completion eligibility from both interrupted windows.')
    for ($sample=4; $sample -le 244; $sample++) {
        $clock=[pscustomobject]@{Elapsed=[TimeSpan]::FromSeconds($sample*30)}
        $fixtureProfile.schedule.digest=[string]$sample
        $fixtureSnapshot.player.dayTime=24000+($sample*600)
        # This completion spans the stop/recovery boundary, so it is global
        # evidence only; it is not enclosed by either new claimed window.
        if ($sample -eq 4) { $fixtureProfile.logging.dueDay=3 }
        . $sampleBody
    }
    Assert-Fixture ($fixtureSummary.cleanMinutes -eq 120 -and $fixtureSummary.uninterruptedFreshRunningSeconds -eq 7200 -and $fixtureSummary.loggingObservedCompletions -eq 2 -and -not $fixtureSummary.verified -and -not $fixtureSummary.candidateVerifiedRunningWindow) ($stopState + ': two hours after recovery cannot reuse pre-window or boundary-spanning completions.')
    $clock.Elapsed=[TimeSpan]::FromSeconds(245*30); $fixtureProfile.schedule.digest='245'; $fixtureProfile.logging.dueDay=4
    . $sampleBody
    Assert-Fixture ($fixtureSummary.verified -and $fixtureSummary.candidateVerifiedRunningWindow -and $fixtureSummary.cleanWindowLoggingCompletions -eq 1 -and $fixtureSummary.freshRunningWindowLoggingCompletions -eq 1) ($stopState + ': a subsequent completion inside both mature windows can qualify them.')
}

# An ordinary BLOCKED capacity signal resets only the stricter clean window. Its
# running candidate may retain a completion enclosed by the still-running window.
. $initialize
New-Fixtures
for ($sample=0; $sample -le 244; $sample++) {
    $clock=[pscustomobject]@{Elapsed=[TimeSpan]::FromSeconds($sample*30)}
    $fixtureProfile.schedule.digest=[string]$sample
    $fixtureSnapshot.player.dayTime=24000+($sample*600)
    if ($sample -eq 2) { $fixtureProfile.logging.dueDay=2 }
    if ($sample -eq 3) {
        $fixtureSnapshot.failureHistory=@([pscustomobject]@{tick=15;lastTick=15;occurrences=1;feature='WINE_STORAGE';state='BLOCKED';message='Storage full; other work can continue'})
    }
    . $sampleBody
}
Assert-Fixture (-not $fixtureSummary.verified -and $fixtureSummary.candidateVerifiedRunningWindow -and $fixtureSummary.needsAudit -and $fixtureSummary.cleanWindowLoggingCompletions -eq 0 -and $fixtureSummary.freshRunningWindowLoggingCompletions -eq 1) 'Each claim must use its own window; a recoverable capacity signal cannot borrow a completion for the stricter clean window.'
[pscustomobject]@{result='PASS';checks=$checks;evidence='Synthetic fixtures only; no game process was inspected, requested, controlled, or verified.'} | ConvertTo-Json -Compress
