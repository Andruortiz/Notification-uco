#Requires -Version 7
[CmdletBinding()]
param(
    [string]$Tenant = 'tenant-dev',
    [string]$Subject = 'dev-user',
    [ValidateSet('ADMINISTRADOR', 'OPERADOR', 'CLIENTE')]
    [string]$Role = 'ADMINISTRADOR',
    [string]$Client,
    [int]$TtlMinutes = 60,
    [string]$TenantClaim = 'tenantId',
    [string]$SubjectClaim = 'sub',
    [string]$RoleClaim = 'role',
    [string]$ClientClaim = 'azp',
    [string]$KeysDir
)

$ErrorActionPreference = 'Stop'

if (-not $KeysDir) {
    $KeysDir = Join-Path (Split-Path -Parent $PSScriptRoot) '.dev-keys'
}
$privatePath = Join-Path $KeysDir 'private.pem'

$rsa = [System.Security.Cryptography.RSA]::Create(2048)
if (Test-Path $privatePath) {
    $rsa.ImportFromPem((Get-Content -Raw $privatePath))
} else {
    New-Item -ItemType Directory -Force $KeysDir | Out-Null
    Set-Content -NoNewline -Path $privatePath -Value $rsa.ExportPkcs8PrivateKeyPem()
}
$publicKey = [Convert]::ToBase64String($rsa.ExportSubjectPublicKeyInfo())

function ConvertTo-Base64Url([byte[]]$bytes) {
    [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$now = [DateTimeOffset]::UtcNow
$claims = [ordered]@{
    $SubjectClaim = $Subject
    $TenantClaim  = $Tenant
    $RoleClaim    = $Role
    iat           = $now.ToUnixTimeSeconds()
    exp           = $now.AddMinutes($TtlMinutes).ToUnixTimeSeconds()
}
if ($Client) {
    $claims[$ClientClaim] = $Client
}

$header = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes('{"alg":"RS256","typ":"JWT"}'))
$payload = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes(($claims | ConvertTo-Json -Compress)))
$signature = ConvertTo-Base64Url ($rsa.SignData(
        [Text.Encoding]::UTF8.GetBytes("$header.$payload"),
        [System.Security.Cryptography.HashAlgorithmName]::SHA256,
        [System.Security.Cryptography.RSASignaturePadding]::Pkcs1))

Write-Host "Exporta antes de arrancar el servicio:"
Write-Host "  `$env:AUTH_PLATFORM_PUBLIC_KEY = '$publicKey'"
Write-Host ""
Write-Host "Authorization: Bearer $header.$payload.$signature"
