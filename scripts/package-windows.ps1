param(
    [ValidateSet("app-image", "exe")]
    [string]$Type = "app-image",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$RepositoryRoot = Split-Path -Parent $PSScriptRoot
$GuiModule = Join-Path $RepositoryRoot "dm-adapter-gui"
$TargetDirectory = Join-Path $GuiModule "target"
$PackageDirectory = Join-Path $TargetDirectory "package"
if (-not $SkipBuild) {
    $MavenCommand = Get-Command "mvn.cmd" -ErrorAction SilentlyContinue
    if ($null -eq $MavenCommand) {
        $MavenCommand = Get-Command "mvn" -ErrorAction Stop
    }
    & $MavenCommand.Source -q -f (Join-Path $RepositoryRoot "pom.xml") -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven package failed with exit code $LASTEXITCODE."
    }
}

$GuiJar = Get-ChildItem $TargetDirectory -Filter "dm-adapter-gui-*.jar" |
    Where-Object { $_.Name -notlike "original-*" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if ($null -eq $GuiJar) {
    throw "GUI shaded jar was not found under: $TargetDirectory"
}
$GuiJarName = $GuiJar.Name
$ArtifactVersion = $GuiJar.BaseName.Substring("dm-adapter-gui-".Length)
$AppVersion = $ArtifactVersion -replace "-SNAPSHOT$", ""
if ($AppVersion -notmatch "^\d+(\.\d+){0,3}$") {
    $AppVersion = "0.1.0"
}

Remove-Item $PackageDirectory -Recurse -Force -ErrorAction SilentlyContinue
New-Item $PackageDirectory -ItemType Directory -Force | Out-Null
$JpackageWork = Join-Path ([System.IO.Path]::GetTempPath()) (
    "dm-adapter-gui-jpackage-" + [Guid]::NewGuid().ToString("N")
)
$InputDirectory = Join-Path $JpackageWork "input"
$LocalPackageDirectory = Join-Path $JpackageWork "output"

try {
    New-Item $InputDirectory -ItemType Directory -Force | Out-Null
    New-Item $LocalPackageDirectory -ItemType Directory -Force | Out-Null
    Copy-Item $GuiJar.FullName (Join-Path $InputDirectory $GuiJarName)

    $JpackageArguments = @(
        "--type", $Type,
        "--name", "dm-adapter-gui",
        "--app-version", $AppVersion,
        "--vendor", "dm-adapter",
        "--description", "Spring Boot MyBatis project adapter for Dameng database",
        "--input", $InputDirectory,
        "--main-jar", $GuiJarName,
        "--main-class", "com.github.dmadapter.gui.DmAdapterGui",
        "--dest", $LocalPackageDirectory,
        "--jlink-options", "--strip-debug --no-man-pages --no-header-files"
    )

    if ($Type -eq "exe") {
        $JpackageArguments += @("--win-dir-chooser", "--win-menu", "--win-shortcut")
    }

    & jpackage @JpackageArguments
    if ($LASTEXITCODE -ne 0) {
        if ($Type -eq "exe") {
            throw "jpackage failed. Windows installer EXE packaging requires a compatible WiX Toolset installation."
        }
        throw "jpackage failed with exit code $LASTEXITCODE."
    }
    Copy-Item (Join-Path $LocalPackageDirectory "*") $PackageDirectory -Recurse -Force
} finally {
    Remove-Item $JpackageWork -Recurse -Force -ErrorAction SilentlyContinue
}

if ($Type -eq "app-image") {
    Write-Host "Portable application created: $PackageDirectory\dm-adapter-gui\dm-adapter-gui.exe"
} else {
    Write-Host "Windows installer created under: $PackageDirectory"
}
