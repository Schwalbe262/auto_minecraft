param([switch]$CreateRepository, [switch]$Push, [switch]$Release, [string]$ReleaseNotesPath)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoRoot
$owner = 'Schwalbe262'
$repository = 'auto_minecraft'
$versionMatches = [regex]::Matches((Get-Content -LiteralPath (Join-Path $repoRoot 'gradle.properties') -Raw -Encoding UTF8), '(?m)^mod_version\s*=\s*(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)\s*$')
if ($versionMatches.Count -ne 1) { throw 'gradle.properties must contain exactly one valid mod_version.' }
$modVersion = $versionMatches[0].Groups[1].Value
$tag = "v$modVersion"
$assetName = "autovalley-$modVersion.jar"
$assetPath = Join-Path $repoRoot "build\libs\$assetName"
$releaseNotes = "Client-only Forge 1.20.1 farming assistant for Society 4.1.4. This is a prerelease; see README for setup and current limitations.`n`nInstall the attached $assetName into the Society instance's mods directory, replacing the previous Auto Valley JAR after keeping a backup. Restart Minecraft to load the update. Ctrl+F8 configures; F8 toggles; Pause stops."
if ($ReleaseNotesPath) { $releaseNotes = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $ReleaseNotesPath).Path,[Text.Encoding]::UTF8) }

# Fail locally before any publication if the artifact or checkout is not the requested version.
if ($Release) {
    if (-not (Test-Path -LiteralPath $assetPath -PathType Leaf)) { throw "Build $assetName before creating the prerelease." }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($assetPath)
    try {
        $entry = $archive.GetEntry('META-INF/mods.toml')
        if (-not $entry -or -not $archive.GetEntry('dev/schwalbe/autovalley/AutoValley.class')) { throw 'Release JAR is missing Auto Valley metadata or its compiled entrypoint.' }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { $metadata = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $modBlocks = [regex]::Matches($metadata, '(?ms)^\s*\[\[mods\]\]\s*(.*?)(?=^\s*\[\[|\z)')
        $ownBlocks = @($modBlocks | Where-Object { $_.Groups[1].Value -match '(?m)^modId\s*=\s*"autovalley"\s*$' })
        if ($ownBlocks.Count -ne 1) { throw 'Release JAR does not declare exactly one Auto Valley mod.' }
        $versions = [regex]::Matches($ownBlocks[0].Groups[1].Value, '(?m)^version\s*=\s*"([^"]+)"\s*$')
        if ($versions.Count -ne 1 -or $versions[0].Groups[1].Value -ne $modVersion) { throw "Release JAR mod metadata does not match $modVersion." }
    } finally { $archive.Dispose() }
    $dirtyFiles = @(git status --porcelain)
    if ($LASTEXITCODE -ne 0 -or $dirtyFiles.Count -ne 0) { throw 'Commit the intended release changes before publishing; the working tree must be clean.' }
    $branch = (git branch --show-current).Trim()
    if ($LASTEXITCODE -ne 0 -or $branch -ne 'main') { throw 'Publish releases from the main branch.' }
    $head = (git rev-parse --verify HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Could not resolve the release commit.' }
}

# Git Credential Manager supplies the existing GitHub login. Secrets stay in memory.
$credentialRequest = "protocol=https`nhost=github.com`nusername=$owner`n`n"
$credentialLines = $credentialRequest | git credential fill
if ($LASTEXITCODE -ne 0) { throw 'GitHub credentials are unavailable in Git Credential Manager.' }
$passwordLine = $credentialLines | Where-Object { $_.StartsWith('password=') } | Select-Object -First 1
if (-not $passwordLine) { throw 'No GitHub credential was returned.' }
$githubToken = $passwordLine.Substring(9)
$credentialLines = $null
$passwordLine = $null
function Invoke-GitHub([string]$Path, [string]$Method = 'GET', [string]$Body = '', [string]$UploadFile = '') {
    # curl receives the token through stdin, never command-line arguments or disk.
    if ($githubToken -match '[\r\n"]') { throw 'Unsupported credential format.' }
    $curlConfig = @(
        ('url = "https://' + $(if ($UploadFile) {'uploads.github.com'} else {'api.github.com'}) + $Path + '"')
        ('header = "Authorization: Bearer ' + $githubToken + '"')
        'header = "Accept: application/vnd.github+json"'
        'header = "X-GitHub-Api-Version: 2022-11-28"'
        'user-agent = "AutoValley-Release"'
        ('request = "' + $Method + '"')
    )
    if ($Body) { $escapedBody = $Body.Replace('\','\\').Replace('"','\"').Replace("`r",'').Replace("`n",'\n'); $curlConfig += 'header = "Content-Type: application/json"'; $curlConfig += 'data = "' + $escapedBody + '"' }
    if ($UploadFile) { $uploadPath = (Resolve-Path -LiteralPath $UploadFile).Path.Replace('\','/'); $curlConfig += 'header = "Content-Type: application/java-archive"'; $curlConfig += 'data-binary = "@' + $uploadPath + '"' }
    $responseLines = ($curlConfig -join "`n") | curl.exe --silent --show-error --config - --write-out "`n%{http_code}"
    if ($LASTEXITCODE -ne 0) { throw 'GitHub network request failed.' }
    $responseText = $responseLines -join "`n"
    $separator = $responseText.LastIndexOf("`n")
    $statusCode = [int]$responseText.Substring($separator+1)
    if ($statusCode -eq 404 -and $Method -eq 'GET') { return $null }
    if ($statusCode -lt 200 -or $statusCode -ge 300) {
        $apiError = $null
        try { $apiError = $responseText.Substring(0,$separator) | ConvertFrom-Json } catch { }
        $detail = if ($apiError) { (@{message=$apiError.message;errors=$apiError.errors} | ConvertTo-Json -Depth 5 -Compress) } else { 'No structured error.' }
        throw "GitHub $Method $Path returned HTTP ${statusCode}: $detail"
    }
    return $responseText.Substring(0,$separator) | ConvertFrom-Json
}
try {
    $account = Invoke-GitHub '/user'
    if ($account.login -ne $owner) { throw 'The authenticated GitHub account does not match the intended owner.' }
    $repo = $null
    $repo = Invoke-GitHub "/repos/$owner/$repository"
    if (-not $repo) {
        if (-not $CreateRepository) { throw 'Repository does not exist; pass -CreateRepository to create the agreed public repository.' }
        $body = @{ name = $repository; description = 'Client-side modular farming assistant for Society: Sunlit Valley (Forge 1.20.1).'; private = $false; auto_init = $false } | ConvertTo-Json
        $repo = Invoke-GitHub '/user/repos' 'POST' $body
    }
    if ($repo.private) { throw 'Destination is private; expected the user-approved public repository.' }
    $expectedRemote = "https://github.com/$owner/$repository.git"
    $remoteNames = @(git remote)
    if ($remoteNames -notcontains 'origin') { git remote add origin $expectedRemote }
    else {
        $existingRemote = git remote get-url origin
        if ($existingRemote -ne $expectedRemote) { throw 'Existing origin differs from the agreed repository; no remote was changed.' }
    }
    if ($Push) {
        git push -u origin main
        if ($LASTEXITCODE -ne 0) {
            # Some Windows DNS clients fail while curl resolves correctly. Keep TLS hostname verification.
            $gitHubIp = (curl.exe -sS -o NUL -w '%{remote_ip}' https://github.com).Trim()
            $validatedIp = [Net.IPAddress]::Parse($gitHubIp)
            git -c "http.curloptResolve=github.com:443:$validatedIp" push -u origin main
            if ($LASTEXITCODE -ne 0) { throw 'Git push failed; local commits are intact.' }
        }
    }
    if ($Release) {
        $existingTag = Invoke-GitHub "/repos/$owner/$repository/git/ref/tags/$tag"
        if ($existingTag) {
            $tagObject = $existingTag.object
            for ($depth = 0; $tagObject.type -eq 'tag' -and $depth -lt 5; $depth++) {
                $annotatedTag = Invoke-GitHub "/repos/$owner/$repository/git/tags/$($tagObject.sha)"
                if (-not $annotatedTag) { throw 'An existing release tag could not be resolved; it was not changed.' }
                $tagObject = $annotatedTag.object
            }
            if ($tagObject.type -ne 'commit' -or $tagObject.sha -ne $head) { throw 'The version tag already points at another commit; bump mod_version instead of replacing it.' }
        }
        $releaseInfo = Invoke-GitHub "/repos/$owner/$repository/releases/tags/$tag"
        if ($releaseInfo -and ($releaseInfo.tag_name -ne $tag -or -not $releaseInfo.prerelease -or $releaseInfo.draft)) { throw 'An existing release is not the expected published prerelease; it was not changed.' }
        if (-not $releaseInfo) {
            $releaseBody = @{
                tag_name=$tag; target_commitish=$head; name="Auto Valley $modVersion - Society 4.1.4"; draft=$false; prerelease=$true
                body=$releaseNotes
            } | ConvertTo-Json
            $releaseInfo = Invoke-GitHub "/repos/$owner/$repository/releases" 'POST' $releaseBody
        }
        if (@($releaseInfo.assets.name) -notcontains $assetName) {
            $uploadedAsset = Invoke-GitHub "/repos/$owner/$repository/releases/$($releaseInfo.id)/assets?name=$assetName" 'POST' '' $assetPath
            Write-Output $uploadedAsset.browser_download_url
        } else {
            Write-Output "Existing $assetName was preserved; no release asset was overwritten."
        }
        Write-Output $releaseInfo.html_url
    }
    Write-Output $repo.html_url
}
finally { $githubToken = $null; $credentialRequest = $null }
