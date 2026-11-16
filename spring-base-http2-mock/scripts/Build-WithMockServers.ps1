param([switch]$RunTests)
$ErrorActionPreference = 'Stop'
$moduleDirectory = Split-Path -Parent $PSScriptRoot
$workspaceDirectory = Split-Path -Parent $moduleDirectory
foreach ($moduleName in @('http-payload-generator', 'netty-http-mock-server', 'spring-base-http2-mock')) {
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
    $frontendTests = @(Get-ChildItem -LiteralPath (Join-Path $moduleDirectory 'src/test/js') -Filter '*.test.cjs' | Sort-Object Name | ForEach-Object FullName)
    & node --test @frontendTests
    if ($LASTEXITCODE -ne 0) { throw 'Mock and caller frontend tests failed' }
}
