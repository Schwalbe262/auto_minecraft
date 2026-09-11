param([Parameter(Mandatory=$true)][int]$GameProcessId)
$ErrorActionPreference='Stop'
$instance='C:\Users\NEC\curseforge\minecraft\Instances\Society Sunlit Valley'
$gameProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$GameProcessId"
if(-not $gameProcess -or $gameProcess.Name -ne 'javaw.exe' -or -not $gameProcess.CommandLine.Contains($instance)) {
    throw 'Expected existing Society game process is not present.'
}
$directory=Join-Path $instance 'config\autovalley'
$requestedAt=[DateTime]::UtcNow
$requestPath=Join-Path $directory 'inspect.request'
if(-not (Test-Path -LiteralPath $requestPath)) {
    $requestStream=[IO.File]::Open($requestPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
    $requestStream.Dispose()
}
$diagnosticPath=Join-Path $directory 'diagnostics.json'
for($attempt=0;$attempt -lt 30;$attempt++) {
    Start-Sleep -Milliseconds 250
    if(-not (Test-Path -LiteralPath $diagnosticPath)){continue}
    $snapshot=Get-Content -LiteralPath $diagnosticPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if([DateTime]::Parse($snapshot.capturedAt).ToUniversalTime() -ge $requestedAt) {
        $snapshot | Select-Object capturedAt,connected,running,status,executionMode,recordingActive,screenClass,workHotbar,logging,failureHistory,player,
            @{n='menu';e={$_.menu | Select-Object id,revision,container,carried}},
            @{n='inventory';e={@($_.inventory | Where-Object {$_.item.count -gt 0})}},navigation | ConvertTo-Json -Depth 12 -Compress
        exit 0
    }
}
throw 'Fresh diagnostic not observed within 7.5 seconds; game termination is not inferred.'
