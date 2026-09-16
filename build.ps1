param(
    [switch]$Clean
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$AndroidProject = Join-Path $ProjectRoot "android-app"
$GradleBat = Join-Path $ProjectRoot ".build-tools\gradle-8.7\bin\gradle.bat"
$WrapperBat = Join-Path $AndroidProject "gradlew.bat"
$ApkPath = Join-Path $AndroidProject "app\build\outputs\apk\debug\app-debug.apk"

if (-not (Test-Path $AndroidProject)) {
    throw "android-app project not found: $AndroidProject"
}

$UseGradle = $null
if (Test-Path $GradleBat) {
    $UseGradle = $GradleBat
} elseif (Test-Path $WrapperBat) {
    $UseGradle = $WrapperBat
} else {
    throw "No Gradle executable found. Expected one of:`n$GradleBat`n$WrapperBat"
}

Write-Host "Project root : $ProjectRoot"
Write-Host "Android app  : $AndroidProject"
Write-Host "Gradle       : $UseGradle"

Push-Location $AndroidProject
try {
    if ($Clean) {
        Write-Host "Running clean..."
        & $UseGradle clean
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle clean failed with exit code $LASTEXITCODE"
        }
    }

    Write-Host "Building debug APK..."
    & $UseGradle assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle assembleDebug failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

if (-not (Test-Path $ApkPath)) {
    throw "Build finished but APK not found: $ApkPath"
}

$Apk = Get-Item $ApkPath
Write-Host ""
Write-Host "Build successful"
Write-Host "APK : $($Apk.FullName)"
Write-Host "Size: $([math]::Round($Apk.Length / 1MB, 2)) MB"
