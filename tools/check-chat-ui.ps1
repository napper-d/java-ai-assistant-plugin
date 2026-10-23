$ErrorActionPreference = 'Stop'
$pluginRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $pluginRoot
try {
    # This check intentionally needs a local JDK 21; no downloads or Gradle caches.
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
    New-Item -ItemType Directory -Force -Path 'build/ui-check' | Out-Null
    $sources = @(Get-ChildItem -LiteralPath 'src/main/java/edu/teamc/courseaiassistant/sample' -Filter '*.java' |
        ForEach-Object FullName)
    $sources += @(Get-ChildItem -LiteralPath 'src/main/java/edu/teamc/courseaiassistant/ui' -Filter '*.java' |
        Where-Object Name -ne 'CourseAiToolWindowFactory.java' | ForEach-Object FullName)
    $sources += 'tools/ChatPanelSmokeCheck.java'
    & $compiler -encoding UTF-8 --release 21 -d 'build/ui-check' @sources
    if ($LASTEXITCODE -ne 0) { throw 'UI check compilation failed.' }
    & $runtime '-Djava.awt.headless=true' -cp 'build/ui-check' 'edu.teamc.courseaiassistant.ui.ChatPanelSmokeCheck'
    if ($LASTEXITCODE -ne 0) { throw 'UI interaction checks failed.' }
} finally {
    Pop-Location
}
