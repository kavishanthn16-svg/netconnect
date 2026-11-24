param([ValidateSet('Server','Client')][string]$Mode = 'Client')
$ErrorActionPreference = 'Stop'
# Run from the repository root after mvn -B clean verify. No dependency installation.
$repo = (Get-Location).Path
if (!(Test-Path (Join-Path $repo 'common/target/classes'))) { throw 'Run from the built repository root.' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$m2 = Join-Path $env:USERPROFILE '.m2/repository'
$jackson = @('jackson-annotations','jackson-core','jackson-databind') | ForEach-Object {
    $jar = Join-Path $m2 "com/fasterxml/jackson/core/$_/2.18.3/$_-2.18.3.jar"
    if (!(Test-Path $jar)) { throw "Missing build dependency: $jar" }
    $jar
}
if ($Mode -eq 'Server') {
    $cp = (@((Join-Path $repo 'server/target/classes'),(Join-Path $repo 'common/target/classes')) + $jackson) -join ';'
    & $java -cp $cp com.netconnect.server.security.SecurityDemoServer
} else {
    $cp = (@((Join-Path $repo 'client/target/classes'),(Join-Path $repo 'common/target/classes')) + $jackson) -join ';'
    $fx = @('base','graphics','controls') | ForEach-Object {
        $jar = Join-Path $m2 "org/openjfx/javafx-$_/21.0.6/javafx-$_-21.0.6-win.jar"
        if (!(Test-Path $jar)) { throw "Missing JavaFX dependency: $jar" }
        $jar
    }
    & $java --module-path ($fx -join ';') --add-modules javafx.controls -cp $cp com.netconnect.client.security.SecureLoginLauncher
}
if ($LASTEXITCODE -ne 0) { throw "Demo exited with code $LASTEXITCODE" }
