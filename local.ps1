$ErrorActionPreference = "Stop"
& python -u (Join-Path $PSScriptRoot "scripts/local.py") @args
exit $LASTEXITCODE
