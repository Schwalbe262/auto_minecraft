param(
    [Parameter(Mandatory=$true)][string]$InstancePath,
    [string]$JarPath,
    [switch]$ValidateOnly
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$versionMatches = [regex]::Matches((Get-Content -LiteralPath (Join-Path $repoRoot 'gradle.properties') -Raw -Encoding UTF8), '(?m)^mod_version\s*=\s*(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)\s*$')
if ($versionMatches.Count -ne 1) { throw 'gradle.properties must contain exactly one valid mod_version.' }
$modVersion = $versionMatches[0].Groups[1].Value
$artifactName = "autovalley-$modVersion.jar"
if (-not $JarPath) { $JarPath = Join-Path $repoRoot "build\libs\$artifactName" }
$instanceRoot = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $InstancePath).Path).TrimEnd('\')
$sourceJar = (Resolve-Path -LiteralPath $JarPath).Path
$modsDirectory = Join-Path $instanceRoot 'mods'
$manifestPath = Join-Path $instanceRoot 'manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath)) { throw 'Select the CurseForge Society instance containing manifest.json.' }
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.minecraft.version -ne '1.20.1' -or @($manifest.minecraft.modLoaders.id) -notcontains 'forge-47.4.0') { throw 'This build requires Minecraft 1.20.1 and Forge 47.4.0.' }
if (-not (Test-Path -LiteralPath $modsDirectory -PathType Container)) { throw 'Instance mods directory is missing.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Get-AutoValleyJarVersion([string]$Path) {
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = $archive.GetEntry('META-INF/mods.toml')
        if (-not $entry -or -not $archive.GetEntry('dev/schwalbe/autovalley/AutoValley.class')) { return $null }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { $metadata = $reader.ReadToEnd() }
        finally { $reader.Dispose() }
        $modBlocks = [regex]::Matches($metadata, '(?ms)^\s*\[\[mods\]\]\s*(.*?)(?=^\s*\[\[|\z)')
        $ownBlocks = @($modBlocks | Where-Object { $_.Groups[1].Value -match '(?m)^modId\s*=\s*"autovalley"\s*$' })
        if ($ownBlocks.Count -ne 1) { return $null }
        $versions = [regex]::Matches($ownBlocks[0].Groups[1].Value, '(?m)^version\s*=\s*"(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)"\s*$')
        if ($versions.Count -ne 1) { return $null }
        return $versions[0].Groups[1].Value
    } finally { $archive.Dispose() }
}
$sourceVersion = Get-AutoValleyJarVersion $sourceJar
if ($sourceVersion -ne $modVersion) { throw "Source must be an Auto Valley $modVersion JAR with matching mod metadata and compiled entrypoint." }
$destination = Join-Path $modsDirectory $artifactName
$sourceHash = (Get-FileHash -LiteralPath $sourceJar -Algorithm SHA256).Hash
$oldJars = @(Get-ChildItem -LiteralPath $modsDirectory -Filter 'autovalley-*.jar' -File)
foreach ($oldJar in $oldJars) {
    $resolvedOld = [IO.Path]::GetFullPath($oldJar.FullName)
    if (-not $resolvedOld.StartsWith($modsDirectory + '\',[StringComparison]::OrdinalIgnoreCase) -or -not (Get-AutoValleyJarVersion $resolvedOld)) { throw 'A similarly named JAR is not a verified Auto Valley artifact; no files were moved.' }
}
if ($ValidateOnly) { Write-Output "Validated Minecraft/Forge compatibility, Auto Valley $modVersion JAR and existing Auto Valley backups. SHA256=$sourceHash"; return }
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
