[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ResultPath,
    [switch] $AllowExploratory
)

$ErrorActionPreference = 'Stop'
$results = @(Get-Content -Raw -LiteralPath $ResultPath | ConvertFrom-Json)
if ($results.Count -ne 12) { throw "Expected 12 primary results; got $($results.Count)" }
$rows = @()
$failures = @()
foreach ($workload in @('flat', 'nested', 'numeric')) {
    foreach ($direction in @('Serialize', 'Deserialize')) {
        $pojo = @($results | Where-Object { $_.params.workload -eq $workload -and $_.benchmark.EndsWith(".pojo$direction") })
        $jackson = @($results | Where-Object { $_.params.workload -eq $workload -and $_.benchmark.EndsWith(".jackson$direction") })
        if ($pojo.Count -ne 1 -or $jackson.Count -ne 1) { throw "Missing/duplicate pair: $workload $direction" }
        $pojo = $pojo[0]
        $jackson = $jackson[0]
        foreach ($measurement in @($pojo, $jackson)) {
            if ($measurement.mode -ne 'avgt' -or $measurement.threads -ne 1 -or $measurement.primaryMetric.scoreUnit -ne 'us/op') { throw 'Expected single-thread average time in us/op' }
            if (-not $AllowExploratory -and ($measurement.forks -lt 2 -or $measurement.warmupIterations -lt 5 -or $measurement.measurementIterations -lt 5)) { throw 'Formal gate requires >=2 forks and >=5 warmup/measurement iterations' }
            if ($measurement.primaryMetric.score -le 0 -or [double]::IsNaN($measurement.primaryMetric.score)) { throw 'Invalid time score' }
        }
        if ($pojo.jvm -ne $jackson.jvm -or $pojo.jdkVersion -ne $jackson.jdkVersion -or ($pojo.jvmArgs -join ' ') -ne ($jackson.jvmArgs -join ' ')) { throw 'Mismatched JVM settings' }
        $pojoAllocation = @($pojo.secondaryMetrics.PSObject.Properties | Where-Object { $_.Name.EndsWith('gc.alloc.rate.norm') })
        $jacksonAllocation = @($jackson.secondaryMetrics.PSObject.Properties | Where-Object { $_.Name.EndsWith('gc.alloc.rate.norm') })
        if ($pojoAllocation.Count -ne 1 -or $jacksonAllocation.Count -ne 1) { throw 'Missing allocation profiler scores' }
        $pojoBytes = [double]$pojoAllocation[0].Value.score
        $jacksonBytes = [double]$jacksonAllocation[0].Value.score
        if ([double]::IsNaN($pojoBytes) -or $pojoBytes -lt 0 -or $jacksonBytes -le 0) { throw 'Invalid allocation score' }
        $timePass = $pojo.primaryMetric.scoreConfidence[1] -lt $jackson.primaryMetric.scoreConfidence[0]
        $allocationPass = $pojoAllocation[0].Value.scoreConfidence[1] -lt $jacksonAllocation[0].Value.scoreConfidence[0]
        $pass = $timePass -and $allocationPass
        if (-not $pass) { $failures += "$workload $direction" }
        $rows += [pscustomobject]@{
            Workload = $workload
            Direction = $direction
            JacksonUs = [math]::Round($jackson.primaryMetric.score, 4)
            PojoUs = [math]::Round($pojo.primaryMetric.score, 4)
            Speedup = [math]::Round($jackson.primaryMetric.score / $pojo.primaryMetric.score, 2)
            JacksonBytes = [math]::Round($jacksonBytes, 2)
            PojoBytes = [math]::Round($pojoBytes, 4)
            Pass = $pass
        }
    }
}
$rows | Format-Table -AutoSize
if ($failures.Count -gt 0) { throw "Performance gate failed (confidence intervals must not overlap): $($failures -join ', ')" }
Write-Output 'PASS: every pair improves both processing time and allocation with disjoint JMH confidence intervals.'
