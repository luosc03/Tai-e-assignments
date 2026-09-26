$ErrorActionPreference = 'Stop'

$projectRoot = $PSScriptRoot
$archivePath = Join-Path $projectRoot 'A2.zip'
$relativeSources = @(
    'A2\tai-e\src\main\java\pascal\taie\analysis\dataflow\analysis\constprop\ConstantPropagation.java',
    'A2\tai-e\src\main\java\pascal\taie\analysis\dataflow\solver\Solver.java',
    'A2\tai-e\src\main\java\pascal\taie\analysis\dataflow\solver\WorkListSolver.java'
)

$sourcePaths = @()
foreach ($relativeSource in $relativeSources) {
    $sourcePath = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $relativeSource))
    if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
        throw "Source file not found: $sourcePath"
    }
    $sourcePaths += $sourcePath
}

if (Test-Path -LiteralPath $archivePath -PathType Leaf) {
    Remove-Item -LiteralPath $archivePath -Force
}

Add-Type -AssemblyName System.IO.Compression
$archiveStream = $null
$archive = $null
try {
    $archiveStream = [System.IO.File]::Open(
        $archivePath,
        [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::ReadWrite
    )
    $archive = [System.IO.Compression.ZipArchive]::new(
        $archiveStream,
        [System.IO.Compression.ZipArchiveMode]::Create,
        $true
    )

    foreach ($sourcePath in $sourcePaths) {
        $entryName = [System.IO.Path]::GetFileName($sourcePath)
        $entry = $archive.CreateEntry(
            $entryName,
            [System.IO.Compression.CompressionLevel]::Optimal
        )
        $sourceStream = [System.IO.File]::OpenRead($sourcePath)
        $entryStream = $entry.Open()
        try {
            $sourceStream.CopyTo($entryStream)
        }
        finally {
            $entryStream.Dispose()
            $sourceStream.Dispose()
        }
    }
}
catch {
    if (Test-Path -LiteralPath $archivePath -PathType Leaf) {
        Remove-Item -LiteralPath $archivePath -Force
    }
    throw
}
finally {
    if ($null -ne $archive) {
        $archive.Dispose()
    }
    if ($null -ne $archiveStream) {
        $archiveStream.Dispose()
    }
}

$checkStream = [System.IO.File]::OpenRead($archivePath)
$checkArchive = $null
try {
    $checkArchive = [System.IO.Compression.ZipArchive]::new(
        $checkStream,
        [System.IO.Compression.ZipArchiveMode]::Read,
        $true
    )
    $actualNames = @($checkArchive.Entries | ForEach-Object { $_.FullName } | Sort-Object)
    $expectedNames = @('ConstantPropagation.java', 'Solver.java', 'WorkListSolver.java' | Sort-Object)
    if (($actualNames -join '|') -ne ($expectedNames -join '|')) {
        throw "Unexpected archive entries: $($actualNames -join ', ')"
    }
}
finally {
    if ($null -ne $checkArchive) {
        $checkArchive.Dispose()
    }
    $checkStream.Dispose()
}

Write-Output "Created $archivePath"
Get-Item -LiteralPath $archivePath | Select-Object FullName, Length, LastWriteTime
