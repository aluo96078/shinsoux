# ADR-0002 S0 Windows verification (GitHub Actions windows-2022). Expects build\ with
# launcher.exe, probe.exe, core_test.exe and bootstrap.js. Writes results to build\results.txt.
$ErrorActionPreference = "Stop"
$B = Join-Path $PSScriptRoot "build"
$out = Join-Path $B "results.txt"
"" | Set-Content $out
function Log($s) { $s | Tee-Object -FilePath $out -Append }

Log "== machine"
Log ((Get-CimInstance Win32_OperatingSystem).Caption + " " + (Get-CimInstance Win32_OperatingSystem).Version)
Log ((Get-CimInstance Win32_Processor | Select-Object -First 1).Name)

# Secrets and a loopback listener the probe must not reach.
$secret = Join-Path $env:USERPROFILE "shinsou-s0-secret.txt"
"s0-secret" | Set-Content $secret
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = $listener.LocalEndpoint.Port

$fail = 0
# "container" (regular AppContainer) is reported for comparison; the requirement is "lpac".
foreach ($mode in @("none", "job", "container", "lpac")) {
    Log "== probe mode=$mode"
    $o = & "$B\launcher.exe" $mode 128 "$B\probe.exe" $secret $port $PID 2>&1
    $o | ForEach-Object { Log $_ }
    $pass = ($o -join "`n") -match "RESULT PASS"
    if ($mode -eq "lpac" -and -not $pass) { $fail = 1 }
}

Log "== core_test inside LPAC + Job (1024 MiB)"
$o = & "$B\launcher.exe" lpac 1024 "$B\core_test.exe" "$B\bootstrap.js" 2>&1
$o | ForEach-Object { Log $_ }
if (-not (($o -join "`n") -match "ALL PASSED")) { $fail = 1 }

foreach ($mode in @("lpac", "container")) {
    $times = @()
    for ($i = 0; $i -lt 20; $i++) {
        $l = (& "$B\launcher.exe" $mode 128 "$B\probe.exe" noop) | Select-String "LAUNCH"
        $times += [double]([regex]::Match($l, "ms=([0-9.]+)").Groups[1].Value)
    }
    $sorted = $times | Sort-Object
    Log ("cold start {0} median_ms={1} min_ms={2}" -f $mode, $sorted[10], $sorted[0])
}
$times = @()
for ($i = 0; $i -lt 20; $i++) {
    $l = (& "$B\launcher.exe" none 128 "$B\probe.exe" noop) | Select-String "LAUNCH"
    $times += [double]([regex]::Match($l, "ms=([0-9.]+)").Groups[1].Value)
}
$sorted = $times | Sort-Object
Log ("cold start plain median_ms={0} min_ms={1}" -f $sorted[10], $sorted[0])

Log "== helper bench inside LPAC + Job (256 MiB)"
$o = & "$B\launcher.exe" lpac 256 --bench "$B\helper.exe" "$B\bootstrap.js" 2>&1
$o | ForEach-Object { Log $_ }
if (-not (($o -join "`n") -match "BENCH done") -or (($o -join "`n") -match "BENCH failed")) { $fail = 1 }

Log "== Job CPU rate hard cap (single busy thread, 2 s wall)"
$cores = [Environment]::ProcessorCount
# A run counts only if the launcher succeeded and printed exactly one SPIN sample; otherwise -1.
function SpinCpu() {
    $o = (& "$B\launcher.exe" lpac 128 "$B\probe.exe" spin 2>&1) -join "`n"
    $code = $LASTEXITCODE
    $m = [regex]::Matches($o, "SPIN wall_ms=2000 cpu_ms=([0-9]+)")
    if ($code -ne 0 -or $m.Count -ne 1) { Log "spin run failed (exit=$code samples=$($m.Count)): $o"; return -1 }
    [double]$m[0].Groups[1].Value
}
$free = SpinCpu
$env:SHINSOU_S0_CPU_RATE = "10"
$capped = SpinCpu
Remove-Item Env:SHINSOU_S0_CPU_RATE
# 10% of all processors, but one thread can use at most one of them.
$expect = 2000 * [Math]::Min(1.0, 0.10 * $cores)
Log ("cores={0} uncapped cpu_ms={1} capped(10%) cpu_ms={2} expected_cap_ms~{3}" -f $cores, $free, $capped, $expect)
if ($free -lt 1600 -or $capped -le 0 -or $capped -gt $expect * 1.5) { $fail = 1 }

Log "== kill-on-close: kill the launcher, the sandboxed child must die with the job"
$p = Start-Process -FilePath "$B\launcher.exe" -ArgumentList @("lpac", "128", "$B\probe.exe", "hang") -PassThru -WindowStyle Hidden
Start-Sleep -Seconds 2
$before = @(Get-Process probe -ErrorAction SilentlyContinue).Count
Stop-Process -Id $p.Id -Force
Start-Sleep -Seconds 2
$after = @(Get-Process probe -ErrorAction SilentlyContinue).Count
Log "probe processes before=$before after=$after"
if ($before -lt 1 -or $after -ne 0) { $fail = 1 }

Log "== post-creation failure: the launcher must not leave a (suspended) child behind"
$env:SHINSOU_S0_FAIL_AFTER_CREATE = "1"
$o = & "$B\launcher.exe" lpac 128 "$B\probe.exe" hang 2>&1
$code = $LASTEXITCODE
Remove-Item Env:SHINSOU_S0_FAIL_AFTER_CREATE
$o | ForEach-Object { Log $_ }
Start-Sleep -Seconds 1
$left = @(Get-Process probe -ErrorAction SilentlyContinue).Count
Log "launcher exit=$code probe processes left=$left"
if ($code -ne 2 -or $left -ne 0) { $fail = 1 }

$listener.Stop()
Remove-Item $secret -ErrorAction SilentlyContinue
Log ("== OVERALL " + $(if ($fail) { "FAIL" } else { "PASS" }))
exit $fail
