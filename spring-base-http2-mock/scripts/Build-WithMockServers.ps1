param([switch]$RunTests)
$ErrorActionPreference = 'Stop'
$moduleDirectory = Split-Path -Parent $PSScriptRoot
$workspaceDirectory = Split-Path -Parent $moduleDirectory
foreach ($moduleName in @('http-payload-generator', 'netty-http-mock-server', 'http-mock-response-server', 'spring-base-http2-mock')) {
    $modulePom = Join-Path $workspaceDirectory "$moduleName/pom.xml"
    if (-not (Test-Path -LiteralPath $modulePom)) { throw "Module POM not found: $modulePom" }
    if ($RunTests) {
        & mvn -f $modulePom verify -DskipTests=false -B -ntp
        if ($LASTEXITCODE -ne 0) { throw "$moduleName verification failed" }
    }
    & mvn -f $modulePom install -DskipTests=true -B -ntp
    if ($LASTEXITCODE -ne 0) { throw "$moduleName installation failed" }
}
if ($RunTests -and (Get-Command node -ErrorAction SilentlyContinue)) {
    & node --test (Join-Path $moduleDirectory 'src/test/js/test-console.test.cjs')
    if ($LASTEXITCODE -ne 0) { throw 'Browser console lifecycle tests failed' }
    & node --test (Join-Path $moduleDirectory 'src/test/js/execute-console.test.cjs')
    if ($LASTEXITCODE -ne 0) { throw 'Execution SSE decoder tests failed' }
}
