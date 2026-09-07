param(
    [Parameter(Mandatory=$true)][string]$InstancePath,
    [string]$JarPath,
    [switch]$ValidateOnly
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $JarPath) { $JarPath = Join-Path $repoRoot 'build\libs\autovalley-0.1.0.jar' }
$instanceRoot = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $InstancePath).Path).TrimEnd('\')
$sourceJar = (Resolve-Path -LiteralPath $JarPath).Path
$modsDirectory = Join-Path $instanceRoot 'mods'
$manifestPath = Join-Path $instanceRoot 'manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath)) { throw 'Select the CurseForge Society instance containing manifest.json.' }
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.minecraft.version -ne '1.20.1' -or @($manifest.minecraft.modLoaders.id) -notcontains 'forge-47.4.0') { throw 'This build requires Minecraft 1.20.1 and Forge 47.4.0.' }
if (-not (Test-Path -LiteralPath $modsDirectory -PathType Container)) { throw 'Instance mods directory is missing.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Test-AutoValleyJar([string]$Path) {
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = $archive.GetEntry('META-INF/mods.toml')
        if (-not $entry) { return $false }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { return $reader.ReadToEnd() -match 'modId\s*=\s*"autovalley"' }
        finally { $reader.Dispose() }
    } finally { $archive.Dispose() }
}
if (-not (Test-AutoValleyJar $sourceJar)) { throw 'Source file is not an Auto Valley mod JAR.' }
$destination = Join-Path $modsDirectory 'autovalley-0.1.0.jar'
$sourceHash = (Get-FileHash -LiteralPath $sourceJar -Algorithm SHA256).Hash
if ($ValidateOnly) { Write-Output "Validated Minecraft/Forge compatibility and Auto Valley JAR. SHA256=$sourceHash"; return }
$oldJars = @(Get-ChildItem -LiteralPath $modsDirectory -Filter 'autovalley-*.jar' -File)
foreach ($oldJar in $oldJars) {
    $resolvedOld = [IO.Path]::GetFullPath($oldJar.FullName)
    if (-not $resolvedOld.StartsWith($modsDirectory + '\',[StringComparison]::OrdinalIgnoreCase) -or -not (Test-AutoValleyJar $resolvedOld)) { throw 'A similarly named JAR is not a verified Auto Valley artifact; no files were moved.' }
}
$pending = Join-Path $modsDirectory ('autovalley-' + [guid]::NewGuid().ToString('N') + '.pending')
$backups = @()
try {
    Copy-Item -LiteralPath $sourceJar -Destination $pending
    if ((Get-FileHash -LiteralPath $pending -Algorithm SHA256).Hash -ne $sourceHash) { throw 'Copied JAR checksum mismatch.' }
    if ($oldJars.Count -gt 0) {
        $backupDirectory = Join-Path $instanceRoot ('local\autovalley-backups\' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
        New-Item -ItemType Directory -Path $backupDirectory -Force | Out-Null
        foreach ($oldJar in $oldJars) {
            $backupPath = Join-Path $backupDirectory $oldJar.Name
            Move-Item -LiteralPath $oldJar.FullName -Destination $backupPath
            $backups += [pscustomobject]@{Original=$oldJar.FullName;Backup=$backupPath}
        }
    }
    Move-Item -LiteralPath $pending -Destination $destination
    Write-Output "Installed $destination"
    Write-Output "SHA256=$sourceHash"
    if ($backups.Count -gt 0) { Write-Output "Previous Auto Valley JARs are recoverable in $backupDirectory" }
    Write-Output 'Restart the game to load this version. No running game was stopped.'
} catch {
    foreach ($backup in $backups) { if (-not (Test-Path -LiteralPath $backup.Original)) { Move-Item -LiteralPath $backup.Backup -Destination $backup.Original } }
    throw
} finally {
    if (Test-Path -LiteralPath $pending) { Remove-Item -LiteralPath $pending }
}
