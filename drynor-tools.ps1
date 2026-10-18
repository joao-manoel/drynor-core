param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $ToolArguments
)
$ErrorActionPreference = 'Stop'
$taskJdkRoot = Join-Path $env:USERPROFILE '.jdks/temurin-26'
$taskJava = Get-ChildItem -LiteralPath $taskJdkRoot -Filter java.exe -Recurse -ErrorAction SilentlyContinue |
    Where-Object { $_.Directory.Name -eq 'bin' } | Select-Object -First 1
if (-not $taskJava) { throw "Instale JDK 26 em $taskJdkRoot para executar as ferramentas." }
$env:JAVA_HOME = Split-Path -Parent $taskJava.Directory.FullName
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:CACHE_PATH = (Join-Path (Split-Path -Parent $PSScriptRoot) 'cache') + '/'
$env:GAMEVAL_PATH = Join-Path (Split-Path -Parent $PSScriptRoot) 'cache-unpacked/gamevals'
Push-Location $PSScriptRoot
try {
    if ($ToolArguments.Length -ge 3 -and $ToolArguments[0] -eq 'cache') {
        foreach ($taskIndex in 2..([Math]::Min(3, $ToolArguments.Length - 1))) {
            $ToolArguments[$taskIndex] = [IO.Path]::GetFullPath($ToolArguments[$taskIndex], $PSScriptRoot)
        }
    }
    & "$PSScriptRoot/gradlew.bat" ':tools:installDist' '--console=plain'
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    # The generated .bat exceeds cmd.exe's 8191-character classpath limit.
    # Java expands the lib wildcard itself, keeping the native command short.
    & "$env:JAVA_HOME/bin/java.exe" '-Xmx4g' '--enable-native-access=ALL-UNNAMED' '--enable-final-field-mutation=ALL-UNNAMED' `
        '-cp' "$PSScriptRoot/tools/build/install/tools/lib/*" 'org.drynor.tools.MainKt' @ToolArguments
    exit $LASTEXITCODE
} finally { Pop-Location }
