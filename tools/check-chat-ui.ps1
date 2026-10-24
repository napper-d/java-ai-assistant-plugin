param([string]$IdeHome)

$ErrorActionPreference = 'Stop'
$pluginRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $pluginRoot
try {
    # Uses the real target SDK, but starts no IDE and makes no downloads.
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/javac.exe'))) {
        $compiler = Join-Path $env:JAVA_HOME 'bin/javac.exe'
        $runtime = Join-Path $env:JAVA_HOME 'bin/java.exe'
    } else {
        $compiler = (Get-Command javac -ErrorAction Stop).Source
        $runtime = Join-Path (Split-Path -Parent $compiler) 'java.exe'
    }
    $runtimeVersion = & $runtime -version 2>&1 | Out-String
    if ($runtimeVersion -notmatch 'version "21[\."]') {
        throw 'Set JAVA_HOME to your installed JDK 21 for this standalone check (current shell only).'
    }
    if (-not $IdeHome) {
        $gradleUserHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
        $sdkPattern = Join-Path $gradleUserHome 'caches/*/transforms/*/transformed/ideaIU-2025.2.4-*/lib/app.jar'
        $sdkJar = Get-Item -Path $sdkPattern -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($sdkJar) { $IdeHome = Split-Path -Parent (Split-Path -Parent $sdkJar.FullName) }
    }
    if (-not $IdeHome -or -not (Test-Path -LiteralPath (Join-Path $IdeHome 'lib/app.jar'))) {
        throw 'Target SDK not found. Sync Gradle once, or pass -IdeHome <IDEA 2025.2.4 directory>.'
    }
    $sdkClasspath = Join-Path $IdeHome 'lib/*'
    New-Item -ItemType Directory -Force -Path 'build/ui-check' | Out-Null
    $sources = @(Get-ChildItem -LiteralPath 'src/main/java' -Recurse -Filter '*.java' | ForEach-Object FullName)
    $sources += 'tools/ChatPanelSmokeCheck.java', 'tools/RequestLifecycleCheck.java'
    & $compiler -encoding UTF-8 --release 21 -cp $sdkClasspath -d 'build/ui-check' @sources
    if ($LASTEXITCODE -ne 0) { throw 'UI check compilation failed.' }
    $checkClasspath = "build/ui-check;$sdkClasspath"
    & $runtime '-Djava.awt.headless=true' -cp $checkClasspath 'edu.teamc.courseaiassistant.ui.ChatPanelSmokeCheck'
    if ($LASTEXITCODE -ne 0) { throw 'UI interaction checks failed.' }
    & $runtime '-Djava.awt.headless=true' -cp $checkClasspath 'edu.teamc.courseaiassistant.ui.RequestLifecycleCheck'
    if ($LASTEXITCODE -ne 0) { throw 'Request lifecycle checks failed.' }
} finally {
    Pop-Location
}
