param([switch]$Offline)

$ErrorActionPreference = 'Stop'
Push-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
try {
    $checkArguments = @('test', '--tests', 'edu.teamc.courseaiassistant.ui.ChatPanelTest',
        '--tests', 'edu.teamc.courseaiassistant.ui.RequestLifecycleTest', '--console=plain', '--configuration-cache')
    if ($Offline) { $checkArguments += '--offline' }
    & .\gradlew.bat @checkArguments
    if ($LASTEXITCODE -ne 0) { throw 'Chat interaction checks failed.' }
} finally { Pop-Location }
