param([switch]$CreateRepository, [switch]$Push, [switch]$Release)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoRoot
$owner = 'Schwalbe262'
$repository = 'auto_minecraft'

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
    if ($statusCode -eq 404) { return $null }
    if ($statusCode -lt 200 -or $statusCode -ge 300) { throw "GitHub returned HTTP $statusCode." }
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
        $tag = 'v0.1.0'
        $head = (git rev-parse HEAD).Trim()
        $releaseInfo = Invoke-GitHub "/repos/$owner/$repository/releases/tags/$tag"
        if (-not $releaseInfo) {
            $releaseBody = @{
                tag_name=$tag; target_commitish=$head; name='Auto Valley 0.1.0 - Society 4.1.4'; draft=$false; prerelease=$true
                body="Initial client-only Forge 1.20.1 farming assistant. Includes per-feature controls, registered locations, day-based harvest/production schedules, grade/vintage storage, wine-first allocation and sleep assistance.`n`nBuild and automated tests pass. UI preview verified. First-cycle validation on the actual server requires restart and location registration; no unattended server-run result is claimed.`n`nInstall the attached JAR into the existing Society 4.1.4 instance's mods directory, then restart. Ctrl+F8 configures; F8 toggles; Pause stops. See README for setup."
            } | ConvertTo-Json
            $releaseInfo = Invoke-GitHub "/repos/$owner/$repository/releases" 'POST' $releaseBody
        }
        $assetName = 'autovalley-0.1.0.jar'
        if (@($releaseInfo.assets.name) -notcontains $assetName) {
            $assetPath = Join-Path $repoRoot "build\libs\$assetName"
            $uploadedAsset = Invoke-GitHub "/repos/$owner/$repository/releases/$($releaseInfo.id)/assets?name=$assetName" 'POST' '' $assetPath
            Write-Output $uploadedAsset.browser_download_url
        }
        Write-Output $releaseInfo.html_url
    }
    Write-Output $repo.html_url
}
finally { $githubToken = $null; $credentialRequest = $null }
