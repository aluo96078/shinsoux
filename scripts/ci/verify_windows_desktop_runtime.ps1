param(
    [Parameter(Mandatory = $true)]
    [string]$ApplicationPath
)

$ErrorActionPreference = "Stop"

$resolvedApplication = (Resolve-Path -LiteralPath $ApplicationPath).Path
$applicationRoot = Split-Path -Parent $resolvedApplication
$runtimeRelease = Join-Path $applicationRoot "runtime\release"
if (-not (Test-Path -LiteralPath $runtimeRelease -PathType Leaf)) {
    throw "Packaged runtime metadata does not exist: $runtimeRelease"
}

$moduleLine = Get-Content -LiteralPath $runtimeRelease |
    Where-Object { $_ -like 'MODULES=*' } |
    Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($moduleLine)) {
    throw "Packaged runtime metadata has no MODULES entry: $runtimeRelease"
}
if ($moduleLine -notmatch '(^|\s)jdk\.accessibility(\s|"$)') {
    throw "Packaged runtime does not contain jdk.accessibility: $moduleLine"
}

$identifier = [Guid]::NewGuid().ToString("N")
$stdoutPath = Join-Path $env:RUNNER_TEMP "shinsou-runtime-$identifier.stdout.log"
$stderrPath = Join-Path $env:RUNNER_TEMP "shinsou-runtime-$identifier.stderr.log"

# JAVA_TOOL_OPTIONS reproduces a machine where Windows Java Access Bridge has been enabled.
# jpackage debug output makes an unexpected launcher failure visible in the Actions log.
$env:JAVA_TOOL_OPTIONS =
    "-Djavax.accessibility.assistive_technologies=com.sun.java.accessibility.AccessBridge"
$env:JPACKAGE_DEBUG = "true"

try {
    $process = Start-Process `
        -FilePath $resolvedApplication `
        -ArgumentList "--verify-desktop-runtime" `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath `
        -PassThru

    if (-not $process.WaitForExit(60000)) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        throw "Packaged application runtime probe timed out after 60 seconds."
    }
    $process.Refresh()

    if (Test-Path -LiteralPath $stdoutPath) {
        Get-Content -LiteralPath $stdoutPath
    }
    if (Test-Path -LiteralPath $stderrPath) {
        Get-Content -LiteralPath $stderrPath | ForEach-Object { Write-Host $_ }
    }

    if ($process.ExitCode -ne 0) {
        throw "Packaged application runtime probe exited with code $($process.ExitCode)."
    }
} finally {
    Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
    Remove-Item Env:JPACKAGE_DEBUG -ErrorAction SilentlyContinue
}
