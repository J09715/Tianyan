param([string]$OutputFile = "", [switch]$Clipboard, [switch]$Proot)

$ErrorActionPreference = 'Stop'
$webExampleRoot = Split-Path -Parent $PSScriptRoot
$webExampleId = if ($Clipboard) { 'web-clipboard' } else { 'web-info' }
if ($Proot) { $webExampleId += '-proot' }
if (-not $OutputFile) { $OutputFile = Join-Path $webExampleRoot "build/examples/$webExampleId.txplugin" }
$webExampleOutput = [IO.Path]::GetFullPath($OutputFile)
if ([IO.Path]::GetExtension($webExampleOutput) -ne '.txplugin') { throw 'Output must be a .txplugin file.' }
$webExampleAssets = Join-Path $webExampleRoot 'plugin/ui/src/main/assets/tianyan-web-demo'
if ($Clipboard) { $webExampleAssets = Join-Path $webExampleRoot 'tools/examples/web-clipboard' }
$webExamplePayload = [ordered]@{}
$webExampleHashes = [ordered]@{}
foreach ($name in @('index.html', 'plugin.js', 'plugin.css')) {
    $source = Join-Path $webExampleAssets $name
    $webExamplePayload[$name] = [IO.File]::ReadAllBytes($source)
    $webExampleHashes[$name] = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
}
$webExampleManifest = [ordered]@{
    schemaVersion = 2
    id = $webExampleId
    name = $(if ($Clipboard) { 'Web Clipboard' } else { 'Web Info' }) + $(if ($Proot) { ' (PRoot)' } else { '' })
    description = $(if ($Clipboard) { 'Copy plain text after confirming the exact text in the app each time.' } else { 'Offline Web plugin requesting basic app information.' })
    version = '1.0.0'
    source = 'LOCAL'
    offlineOnly = $true
    installMethod = 'LOCAL_PACKAGE'
    plugin = [ordered]@{
        id = "example.$webExampleId"
        api = @{ minimum = 1; maximum = 1 }
        execution = 'WEB'
        webSource = $(if ($Proot) { 'PROOT_STATIC' } else { 'PACKAGE' })
        extensions = @(@{ id = 'main'; point = 'PAGE' })
        dependencies = @()
        resources = @()
        capabilities = @($(if ($Clipboard) { @{ name = 'CLIPBOARD_WRITE'; target = 'clipboard' } } else { @{ name = 'APP_INFO'; target = 'application' } }))
        integrity = $webExampleHashes
    }
}
$webExampleJson = $webExampleManifest | ConvertTo-Json -Depth 10
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($webExampleOutput)) | Out-Null
Add-Type -AssemblyName System.IO.Compression
$webExampleStream = [IO.File]::Open($webExampleOutput, [IO.FileMode]::Create, [IO.FileAccess]::Write)
try {
    $webExampleZip = [IO.Compression.ZipArchive]::new($webExampleStream, [IO.Compression.ZipArchiveMode]::Create, $true)
    try {
        $webExampleEntries = [ordered]@{ 'manifest.json' = [Text.Encoding]::UTF8.GetBytes($webExampleJson) }
        foreach ($name in $webExamplePayload.Keys) { $webExampleEntries["payload/$name"] = $webExamplePayload[$name] }
        foreach ($name in $webExampleEntries.Keys) {
            $entryStream = $webExampleZip.CreateEntry($name).Open()
            try { $bytes = $webExampleEntries[$name]; $entryStream.Write($bytes, 0, $bytes.Length) }
            finally { $entryStream.Dispose() }
        }
    } finally { $webExampleZip.Dispose() }
} finally { $webExampleStream.Dispose() }
Write-Output $webExampleOutput
