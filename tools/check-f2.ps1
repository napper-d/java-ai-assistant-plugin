param([string]$IdeHome, [string]$JdkHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (!$JdkHome) { throw 'Set JAVA_HOME to JDK 21, or pass -JdkHome.' }
if (!$IdeHome) {
    $candidate = Get-ChildItem (Join-Path $env:USERPROFILE '.gradle\caches\9.0.0\transforms') -Directory -Filter 'ideaIU-2025.2.4-win' -Recurse | Select-Object -First 1
    if (!$candidate) { throw 'Run Gradle sync first or pass -IdeHome.' }
    $IdeHome = $candidate.FullName
}
$checkOutput = Join-Path $projectRoot 'build\reports\f2-check\classes'
New-Item -ItemType Directory -Force $checkOutput | Out-Null
$classpath = (Get-ChildItem (Join-Path $IdeHome 'lib') -Filter '*.jar' | ForEach-Object FullName) -join ';'
$sourceRoot = Join-Path $projectRoot 'src\main\java'
$sources = @(Get-ChildItem (Join-Path $sourceRoot 'edu\teamc\courseaiassistant\modification') -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $JdkHome 'bin\javac.exe') -encoding UTF-8 -cp $classpath -sourcepath $sourceRoot -d $checkOutput @sources (Join-Path $PSScriptRoot 'F2ContractCheck.java')
if ($LASTEXITCODE -ne 0) { throw 'F2 compilation failed.' }
& (Join-Path $JdkHome 'bin\java.exe') -cp "$checkOutput;$classpath" F2ContractCheck
if ($LASTEXITCODE -ne 0) { throw 'F2 checks failed.' }
