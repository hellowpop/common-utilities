param([switch]$RunTests)
# Compatibility wrapper for the former caller build: build the single owning application.
& (Join-Path $PSScriptRoot 'Build-WithMockServers.ps1') -RunTests:$RunTests
