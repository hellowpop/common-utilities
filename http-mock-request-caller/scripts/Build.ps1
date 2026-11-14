param([switch]$RunTests)
$ErrorActionPreference = 'Stop'
$modulePath = Split-Path -Parent $PSScriptRoot
$workspacePath = Split-Path -Parent $modulePath
$payloadPom = Join-Path $workspacePath 'http-payload-generator/pom.xml'
$callerPom = Join-Path $modulePath 'pom.xml'
if ($RunTests) {
    & mvn -f $payloadPom '-DskipTests=false' clean verify
    if ($LASTEXITCODE -ne 0) { throw 'Payload verification failed' }
}
& mvn -f $payloadPom '-Dmaven.test.skip=true' install
if ($LASTEXITCODE -ne 0) { throw 'Payload install failed' }
if ($RunTests) {
    & mvn -f $callerPom '-DskipTests=false' clean verify
    if ($LASTEXITCODE -ne 0) { throw 'Caller verification failed' }
    if (Get-Command node -ErrorAction SilentlyContinue) {
        & node --test (Join-Path $modulePath 'src/test/js/json-view.test.cjs')
        if ($LASTEXITCODE -ne 0) { throw 'JSON view verification failed' }
    } else {
        Write-Warning 'Node.js is not installed; JSON view tests were not run.'
    }
}
& mvn -f $callerPom '-DskipTests=true' install
if ($LASTEXITCODE -ne 0) { throw 'Caller install failed' }
