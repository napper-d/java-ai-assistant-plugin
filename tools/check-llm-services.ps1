param([switch]$Offline)

# Standalone Member 3 checks. Does not replace full Gradle/plugin or IDE acceptance.
$ErrorActionPreference = 'Stop'
$pluginRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $pluginRoot
try {
    if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a local JDK 21 in this shell.' }
    $compiler = Join-Path $env:JAVA_HOME 'bin/javac.exe'
    $runtime = Join-Path $env:JAVA_HOME 'bin/java.exe'
    if ((& $runtime -version 2>&1 | Out-String) -notmatch 'version "21[\."]') {
        throw 'This check requires JDK 21.'
    }
    $checkRoot = Join-Path $pluginRoot 'build/member3-check'
    $lib = Join-Path $checkRoot 'lib'
    New-Item -ItemType Directory -Force -Path $lib | Out-Null
    # Match production Gson/dotenv and JUnit 5.13.4 in build.gradle.kts.
    $dependencies = @(
        @('gson.jar', 'com/google/code/gson/gson/2.13.2/gson-2.13.2.jar', 'DD0CE1B55A3ED2080CB70F9C655850CDA86C206862310009DCB5E5C95265A5E0'),
        @('dotenv.jar', 'io/github/cdimascio/dotenv-java/3.2.0/dotenv-java-3.2.0.jar', '47F5D208B29497F286A454817C955750CC92B99B4680392DD40E5DB592259D22'),
        @('annotations.jar', 'org/jetbrains/annotations/26.0.2/annotations-26.0.2.jar', '2037BE378980D3BA9333E97955F3B2CDE392AA124D04CA73CE2EEE6657199297'),
        @('junit.jar', 'org/junit/platform/junit-platform-console-standalone/1.13.4/junit-platform-console-standalone-1.13.4.jar', '3FDFC37E29744A9A67DD5365E81467E26FBDE0B7AA204E6F8BBE79EEAA7AE892')
    )
    foreach ($dependency in $dependencies) {
        $target = Join-Path $lib $dependency[0]
        if (-not (Test-Path -LiteralPath $target)) {
            if ($Offline) { throw "Missing cached test dependency: $($dependency[0])" }
            Invoke-WebRequest -Uri ('https://repo.maven.apache.org/maven2/' + $dependency[1]) -OutFile $target
        }
        if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $dependency[2]) {
            throw "Checksum mismatch: $target. Remove that cached JAR and retry online."
        }
    }
    # A unique output directory avoids stale compiled tests after source removal.
    $classes = Join-Path $checkRoot ('classes-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $classes | Out-Null
    $sources = @()
    foreach ($package in @('llm', 'model', 'service')) {
        $sources += Get-ChildItem -LiteralPath "src/main/java/edu/teamc/courseaiassistant/$package" -Filter '*.java' | ForEach-Object FullName
    }
    $sources += 'src/main/java/edu/teamc/courseaiassistant/context/CodeContext.java'
    $sources += 'src/main/java/edu/teamc/courseaiassistant/rag/RagException.java'
    $sources += Get-ChildItem -LiteralPath 'src/test/java/edu/teamc/courseaiassistant/llm' -Filter '*.java' | ForEach-Object FullName
    & $compiler -encoding UTF-8 --release 21 -cp "$lib/*" -d $classes @sources
    if ($LASTEXITCODE -ne 0) { throw 'Member 3 compilation failed.' }
    $classpath = $classes + ';' + (($dependencies | ForEach-Object { Join-Path $lib $_[0] }) -join ';')
    & $runtime -jar (Join-Path $lib 'junit.jar') execute --class-path $classpath --scan-class-path --fail-if-no-tests --reports-dir (Join-Path $checkRoot 'reports') --details tree
    if ($LASTEXITCODE -ne 0) { throw 'Member 3 service tests failed.' }
} finally {
    Pop-Location
}
