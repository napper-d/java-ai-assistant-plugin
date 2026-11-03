param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location -LiteralPath $root
try {
    & "$PSScriptRoot/check-llm-services.ps1" -Offline:$Offline
    $lib = 'build/member3-check/lib'
    $out = 'build/member3-check/f2-integration'
    New-Item -ItemType Directory -Force $out | Out-Null
    $sources = @(Get-ChildItem src/main/java/edu/teamc/courseaiassistant/llm,src/main/java/edu/teamc/courseaiassistant/model,src/main/java/edu/teamc/courseaiassistant/service -Filter *.java | ForEach-Object FullName)
    $sources += @('src/main/java/edu/teamc/courseaiassistant/context/CodeContext.java',
        'src/main/java/edu/teamc/courseaiassistant/rag/RagException.java',
        'src/main/java/edu/teamc/courseaiassistant/modification/ProposalValidator.java',
        'src/main/java/edu/teamc/courseaiassistant/modification/SampleCodeGenerationService.java',
        'src/main/java/edu/teamc/courseaiassistant/modification/GenerationFailures.java',
        'src/test/java/edu/teamc/courseaiassistant/modification/LlmF2IntegrationTest.java')
    & "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 --release 21 -cp "$lib/*" -d $out @sources
    if ($LASTEXITCODE -ne 0) { throw 'F2 LLM integration compilation failed.' }
    & "$env:JAVA_HOME/bin/java.exe" -jar "$lib/junit.jar" execute --class-path "$out;$lib/gson.jar;$lib/dotenv.jar;$lib/annotations.jar" --select-class edu.teamc.courseaiassistant.modification.LlmF2IntegrationTest --reports-dir build/reports/f2-integration --details summary
    if ($LASTEXITCODE -ne 0) { throw 'F2 LLM integration tests failed.' }
} finally { Pop-Location }
