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

function Invoke-ShinsouProbe {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Argument,
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $identifier = [Guid]::NewGuid().ToString("N")
    $token = [Guid]::NewGuid().ToString("N")
    $probeRoot = Join-Path $env:RUNNER_TEMP "shinsou-$Name-$identifier"
    $markerPath = Join-Path $probeRoot "completed.marker"
    $userProfile = Join-Path $probeRoot "profile"
    $appData = Join-Path $userProfile "AppData\Roaming"
    $localAppData = Join-Path $userProfile "AppData\Local"
    $stdoutPath = Join-Path $probeRoot "stdout.log"
    $stderrPath = Join-Path $probeRoot "stderr.log"
    New-Item -ItemType Directory -Path $appData -Force | Out-Null
    New-Item -ItemType Directory -Path $localAppData -Force | Out-Null

    $previousUserProfile = $env:USERPROFILE
    $previousAppData = $env:APPDATA
    $previousLocalAppData = $env:LOCALAPPDATA
    $previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS
    $previousJpackageDebug = $env:JPACKAGE_DEBUG
    $env:USERPROFILE = $userProfile
    $env:APPDATA = $appData
    $env:LOCALAPPDATA = $localAppData
    $env:JAVA_TOOL_OPTIONS =
        "-Duser.home=`"$userProfile`" " +
        "-Djavax.accessibility.assistive_technologies=com.sun.java.accessibility.AccessBridge"
    $env:JPACKAGE_DEBUG = "true"
    $env:SHINSOU_DESKTOP_PROBE_MARKER = $markerPath
    $env:SHINSOU_DESKTOP_PROBE_TOKEN = $token

    try {
        $process = Start-Process `
            -FilePath $resolvedApplication `
            -ArgumentList $Argument `
            -RedirectStandardOutput $stdoutPath `
            -RedirectStandardError $stderrPath `
            -PassThru

        if (-not $process.WaitForExit(90000)) {
            # The startup probe writes its thread stacks here after 60 seconds (Main.kt).
            $threads = "$markerPath.threads"
            if (Test-Path -LiteralPath $threads) {
                Write-Host "----- $threads -----"
                Get-Content -LiteralPath $threads | ForEach-Object { Write-Host $_ }
            } else {
                Write-Host "No thread dump: the application never reached main."
            }
            # The launcher may run the JVM in a child process; stop it too.
            Get-CimInstance Win32_Process | Where-Object { $_.ParentProcessId -eq $process.Id } |
                ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            # Show what the app printed before it stalled; otherwise a hang leaves no trace.
            Start-Sleep -Seconds 2
            foreach ($log in @($stdoutPath, $stderrPath)) {
                if (Test-Path -LiteralPath $log) {
                    Write-Host "----- $log -----"
                    Get-Content -LiteralPath $log -Tail 200 | ForEach-Object { Write-Host $_ }
                }
            }
            throw "Packaged application $Name probe timed out after 90 seconds."
        }
        $process.Refresh()

        if (Test-Path -LiteralPath $stdoutPath) {
            Get-Content -LiteralPath $stdoutPath
        }
        if (Test-Path -LiteralPath $stderrPath) {
            Get-Content -LiteralPath $stderrPath | ForEach-Object { Write-Host $_ }
        }
        if ($process.ExitCode -ne 0) {
            throw "Packaged application $Name probe exited with code $($process.ExitCode)."
        }
        if (-not (Test-Path -LiteralPath $markerPath -PathType Leaf)) {
            throw "Packaged application $Name probe did not write its completion marker."
        }
        $actualToken = Get-Content -LiteralPath $markerPath -Raw
        if ($actualToken -cne $token) {
            throw "Packaged application $Name probe wrote an invalid completion marker."
        }
        if ($Name -eq "startup") {
            $database = Join-Path $userProfile "ShinsouXData\SyncV2\local-sync.db"
            if (-not (Test-Path -LiteralPath $database -PathType Leaf)) {
                throw "Packaged application startup probe did not initialize SQLite: $database"
            }
        }
    } finally {
        $env:USERPROFILE = $previousUserProfile
        $env:APPDATA = $previousAppData
        $env:LOCALAPPDATA = $previousLocalAppData
        $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
        $env:JPACKAGE_DEBUG = $previousJpackageDebug
        Remove-Item Env:SHINSOU_DESKTOP_PROBE_MARKER -ErrorAction SilentlyContinue
        Remove-Item Env:SHINSOU_DESKTOP_PROBE_TOKEN -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $probeRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Invoke-ShinsouProbe -Argument "--verify-desktop-runtime" -Name "runtime"
Invoke-ShinsouProbe -Argument "--verify-desktop-startup" -Name "startup"
