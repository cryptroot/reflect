param([string] $Job)

$ErrorActionPreference = 'Stop'
trap {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}

if (-not $Job -or $args.Count -ne 0 -or $Job -notin @('stage0', 'unit', 'lean', 'all', 'shell')) {
    [Console]::Error.WriteLine('Usage: docker/dev.ps1 {stage0|unit|lean|all|shell}')
    exit 2
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    [Console]::Error.WriteLine('Docker is unavailable. Install and start Docker, then try again.')
    exit 1
}

# Windows PowerShell 5.1 treats redirected native stderr as PowerShell errors.
$ErrorActionPreference = 'Continue'
& docker info *> $null
$engineExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($engineExit -ne 0) {
    [Console]::Error.WriteLine('Docker engine is unavailable. Start Docker and try again.')
    exit 1
}

$root = (Get-Item -LiteralPath (Join-Path $PSScriptRoot '..')).FullName
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $root 'docker/build/Dockerfile')).Hash
$image = 'reflect-build:' + $hash.Substring(0, 12).ToLowerInvariant()

$ErrorActionPreference = 'Continue'
& docker image inspect $image *> $null
$imageExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($imageExit -ne 0) {
    $buildArgs = @('build', '--platform', 'linux/amd64', '--tag', $image)
    if ($env:REFLECT_EXTRA_CA) {
        if (-not (Test-Path -LiteralPath $env:REFLECT_EXTRA_CA -PathType Leaf)) {
            [Console]::Error.WriteLine("REFLECT_EXTRA_CA is not a file: $env:REFLECT_EXTRA_CA")
            exit 2
        }
        $ca = (Get-Item -LiteralPath $env:REFLECT_EXTRA_CA).FullName
        $buildArgs += @('--secret', "id=reflect_extra_ca,src=$ca")
    }
    $buildArgs += (Join-Path $root 'docker/build')
    & docker @buildArgs
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

$runArgs = @(
    'run', '--rm', '--platform', 'linux/amd64', '--network', 'none',
    '--env', 'HOME=/tmp',
    '--mount', "type=bind,source=$root,target=/work", '--workdir', '/work',
    $image, 'sh', 'docker/ci.sh', $Job
)
if ($Job -eq 'shell') {
    $runArgs = @('run', '--interactive', '--tty') + $runArgs[1..($runArgs.Count - 1)]
}
& docker @runArgs
exit $LASTEXITCODE
