param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../src/main/resources/tls'),
    [string]$Password = 'local-mock-changeit'
)

$ErrorActionPreference = 'Stop'
$certificateDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $certificateDirectory | Out-Null
$keyStorePath = Join-Path $certificateDirectory 'localhost.p12'
$certificatePath = Join-Path $certificateDirectory 'localhost.crt'
if ((Test-Path -LiteralPath $keyStorePath) -or (Test-Path -LiteralPath $certificatePath)) {
    throw 'Certificate files already exist. Back them up and move them before regenerating.'
}

& keytool -genkeypair -alias localhost -keyalg RSA -keysize 3072 -sigalg SHA256withRSA `
    -validity 3650 -dname 'CN=localhost, OU=Local Mock, O=INZENT, C=KR' `
    -ext 'SAN=dns:localhost,ip:127.0.0.1,ip:::1' -ext 'EKU=serverAuth' `
    -storetype PKCS12 -keystore $keyStorePath -storepass $Password -keypass $Password -noprompt
if ($LASTEXITCODE -ne 0) { throw 'Key pair generation failed.' }
& keytool -exportcert -rfc -alias localhost -keystore $keyStorePath -storepass $Password -file $certificatePath
if ($LASTEXITCODE -ne 0) { throw 'Certificate export failed.' }
Write-Output "Generated local mock certificate: $certificatePath"
