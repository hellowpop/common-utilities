[CmdletBinding()]
param(
    [string] $ResultPath = "",
    [switch] $Quick
)

$ErrorActionPreference = 'Stop'
$modulePath = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($ResultPath)) {
    $ResultPath = Join-Path $modulePath 'target/performance-final.json'
}
$ResultPath = [System.IO.Path]::GetFullPath($ResultPath)
$resultDirectory = Split-Path -Parent $ResultPath
New-Item -ItemType Directory -Force -Path $resultDirectory | Out-Null
& mvn -f (Join-Path $modulePath 'pom.xml') -Pbenchmark test-compile dependency:build-classpath '-Dmdep.outputFile=target/benchmark-classpath.txt' -DincludeScope=test
if ($LASTEXITCODE -ne 0) { throw 'Benchmark compilation failed' }
$dependencyClasspath = (Get-Content -Raw -LiteralPath (Join-Path $modulePath 'target/benchmark-classpath.txt')).Trim()
$classpath = (Join-Path $modulePath 'target/classes') + ';' + (Join-Path $modulePath 'target/test-classes') + ';' + $dependencyClasspath
$javaCommand = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$forks = if ($Quick) { 1 } else { 2 }
$warmups = if ($Quick) { 2 } else { 5 }
$iterations = if ($Quick) { 3 } else { 5 }
$duration = if ($Quick) { '500ms' } else { '1s' }
& $javaCommand -cp $classpath org.openjdk.jmh.Main CodecBenchmark -t 1 -prof gc -foe true -f $forks -wi $warmups -i $iterations -w $duration -r $duration -rf json -rff $ResultPath
if ($LASTEXITCODE -ne 0) { throw 'Benchmark failed' }
& (Join-Path $PSScriptRoot 'Test-Performance.ps1') -ResultPath $ResultPath -AllowExploratory:$Quick
