<# Sends one explicit request through Auto Valley's normal checked runtime controls.
   Never edits profiles, restores inventory, injects keys, or retries game actions. #>
param(
    [Parameter(Mandatory=$true)][int]$GameProcessId,
    [Parameter(Mandatory=$true)][ValidateSet('start','pause','confirm_logging_hotbar')][string]$Command,
    [string]$ConfirmationKey
)
$ErrorActionPreference='Stop'
if($Command -eq 'confirm_logging_hotbar') {
    if($ConfirmationKey -cnotmatch '^[a-f0-9]{64}$'){throw 'Explicit manual cleanup needs the displayed exact logging confirmation key.'}
} elseif($ConfirmationKey){throw 'ConfirmationKey is only valid for explicit logging cleanup confirmation.'}
$instance='C:\Users\NEC\curseforge\minecraft\Instances\Society Sunlit Valley'
$gameProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$GameProcessId"
if(-not $gameProcess -or $gameProcess.Name -ne 'javaw.exe' -or -not $gameProcess.CommandLine.Contains($instance) -or -not $gameProcess.CommandLine.Contains('cpw.mods.bootstraplauncher.BootstrapLauncher')) {
    throw 'Expected existing Society game process is not present.'
}
$creation=$gameProcess.CreationDate
$directory=Join-Path $instance 'config\autovalley'
if($Command -eq 'start') {
    & (Join-Path $PSScriptRoot 'Request-SocietyInspection.ps1') -GameProcessId $GameProcessId | Out-Null
    if($LASTEXITCODE -ne 0){throw 'A fresh inspection is required before starting.'}
    $snapshot=Get-Content -LiteralPath (Join-Path $directory 'diagnostics.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if(-not $snapshot.connected -or $snapshot.recordingActive -or $snapshot.screenClass -or $snapshot.menu.carried.count -gt 0) {
        throw 'Start requires a connected, idle-screen game without recording or cursor items.'
    }
}
$current=Get-CimInstance Win32_Process -Filter "ProcessId=$GameProcessId"
if(-not $current -or $current.CreationDate -ne $creation){throw 'Game process changed; request cancelled.'}
$requestedAt=[DateTime]::UtcNow
$requestPath=Join-Path $directory 'control.request.json'
$request=@{command=$Command}
if($Command -eq 'confirm_logging_hotbar'){$request.name=$ConfirmationKey}
$bytes=[Text.UTF8Encoding]::new($false).GetBytes(($request | ConvertTo-Json -Compress))
$stream=[IO.File]::Open($requestPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
try{$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
$resultPath=Join-Path $directory 'control.result.json'
for($attempt=0;$attempt -lt 40;$attempt++) {
    Start-Sleep -Milliseconds 250
    if(-not (Test-Path -LiteralPath $resultPath)){continue}
    $result=Get-Content -LiteralPath $resultPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if([DateTime]::Parse($result.timestamp).ToUniversalTime() -ge $requestedAt -and $result.command -eq $Command) {
        $result | ConvertTo-Json -Depth 6 -Compress
        if(-not $result.accepted -or -not $result.ack){exit 2}
        exit 0
    }
}
throw 'No acknowledgement observed in ten seconds. Request is not retried and may still be pending.'
