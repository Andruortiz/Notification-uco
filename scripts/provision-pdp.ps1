param(
    [string]$PdpUrl = '',
    [string]$ApplicationName = 'notification-service',
    [string]$ApplicationId = '',
    [string]$CatalogPath = '',
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$SessionVariable = 'PDP_ADMIN_SESSION'
$SessionCookieName = 'SECURITY_BASELINE_SESSION'
$CsrfCookieName = 'XSRF-TOKEN'
$CsrfHeaderName = 'X-XSRF-TOKEN'
$PageSize = 100
$RoleOrder = @('CLIENTE', 'OPERADOR', 'ADMINISTRADOR')
$ProfileByRole = @{ CLIENTE = 'PERFIL_CLIENTE'; OPERADOR = 'PERFIL_OPERADOR'; ADMINISTRADOR = 'PERFIL_ADMINISTRADOR' }
$PathPattern = '^/[A-Za-z0-9._~-]+(/[A-Za-z0-9._~-]+)*$'
$AllowedMethods = @('GET', 'POST', 'PUT', 'PATCH', 'DELETE')

function Fail {
    param([string]$Message, [int]$Code = 1)
    [Console]::Error.WriteLine("ERROR: $Message")
    exit $Code
}

foreach ($extra in $args) {
    if ($extra -eq '--dry-run') {
        $DryRun = [switch]$true
    } else {
        Fail "argumento no reconocido: $extra. Uso: provision-pdp.ps1 [-PdpUrl url] [-ApplicationName nombre] [-ApplicationId id] [-CatalogPath ruta] [-DryRun | --dry-run]" 2
    }
}

if (-not $PdpUrl) {
    $PdpUrl = if ($env:PDP_BASE_URL) { $env:PDP_BASE_URL } else { 'http://localhost:18080' }
}
$PdpUrl = $PdpUrl.TrimEnd('/')
if ($PdpUrl -notmatch '^https?://') {
    Fail "PdpUrl debe empezar por http:// o https://" 2
}

if (-not $CatalogPath) {
    $CatalogPath = Join-Path (Join-Path $PSScriptRoot '..') 'infrastructure/target/contract/protected-resources.json'
}

function Read-Catalog {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        Fail "no existe la lista canonica $Path. Generala con: .\mvnw.cmd -B -ntp -pl infrastructure -am test -Dtest=ProtectedResourcesCatalogTest -Dsurefire.failIfNoSpecifiedTests=false" 2
    }
    $raw = Get-Content -LiteralPath $Path -Raw -Encoding UTF8
    $parsed = ConvertFrom-Json -InputObject $raw
    return @($parsed)
}

function Get-DesiredState {
    param([object[]]$Entries)
    $resources = New-Object System.Collections.Specialized.OrderedDictionary
    foreach ($entry in $Entries) {
        $status = [string]$entry.status
        if ($status -ne 'available') { continue }
        $method = [string]$entry.method
        $path = [string]$entry.path
        $minimum = [string]$entry.minimumRole
        if ($AllowedMethods -notcontains $method) { Fail "metodo no admitido en la lista canonica: $method $path" }
        if ($path -notmatch $PathPattern) { Fail "ruta no registrable en el PDP (solo letras, digitos, . _ ~ - y /): $path" }
        if ($RoleOrder -notcontains $minimum) { Fail "rol minimo desconocido '$minimum' en $method $path" }
        $expectedProfiles = @($RoleOrder[([array]::IndexOf($RoleOrder, $minimum))..($RoleOrder.Count - 1)])
        $declaredProfiles = @($entry.profiles)
        if (($expectedProfiles -join ',') -ne ($declaredProfiles -join ',')) {
            Fail "la lista canonica declara perfiles [$($declaredProfiles -join ',')] para $method $path pero el rol minimo $minimum implica [$($expectedProfiles -join ',')]"
        }
        $key = "$method $path"
        if ($resources.Contains($key)) { Fail "recurso duplicado en la lista canonica: $key" }
        $resources.Add($key, @{ Method = $method; Path = $path; Role = $minimum })
    }
    if ($resources.Count -eq 0) { Fail 'la lista canonica no trae operaciones available' }

    $grants = @{}
    foreach ($role in $RoleOrder) { $grants[$role] = New-Object System.Collections.Generic.List[string] }
    foreach ($key in $resources.Keys) { $grants[$resources[$key].Role].Add($key) }

    $profiles = @{}
    for ($i = 0; $i -lt $RoleOrder.Count; $i++) {
        $profiles[$ProfileByRole[$RoleOrder[$i]]] = @($RoleOrder[0..$i])
    }
    return @{ Resources = $resources; Grants = $grants; Profiles = $profiles }
}

$script:Http = $null
$script:SessionValue = ''
$script:CsrfToken = ''

function New-HttpClient {
    Add-Type -AssemblyName System.Net.Http
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    } catch {
    }
    $handler = New-Object System.Net.Http.HttpClientHandler
    $handler.UseCookies = $false
    $client = New-Object System.Net.Http.HttpClient($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(30)
    return $client
}

function Invoke-Pdp {
    param([string]$Method, [string]$Path, $Body = $null, [bool]$Mutating = $false)
    $httpMethod = New-Object System.Net.Http.HttpMethod -ArgumentList $Method
    $request = New-Object System.Net.Http.HttpRequestMessage -ArgumentList $httpMethod, "$PdpUrl$Path"
    $cookie = "$SessionCookieName=$($script:SessionValue)"
    if ($Mutating) {
        $cookie = "$cookie; $CsrfCookieName=$($script:CsrfToken)"
        [void]$request.Headers.TryAddWithoutValidation($CsrfHeaderName, $script:CsrfToken)
    }
    [void]$request.Headers.TryAddWithoutValidation('Cookie', $cookie)
    [void]$request.Headers.TryAddWithoutValidation('Accept', 'application/json')
    if ($null -ne $Body) {
        $json = ConvertTo-Json -InputObject $Body -Compress
        $request.Content = New-Object System.Net.Http.StringContent -ArgumentList $json, ([Text.Encoding]::UTF8), 'application/json'
    }
    try {
        $response = $script:Http.SendAsync($request).GetAwaiter().GetResult()
    } catch {
        Fail "no fue posible contactar el PDP en $PdpUrl ($($_.Exception.Message))"
    }
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $data = $null
    if ($text) {
        try { $data = ConvertFrom-Json -InputObject $text } catch { $data = $null }
    }
    $setCookies = @()
    $values = $null
    if ($response.Headers.TryGetValues('Set-Cookie', [ref]$values)) { $setCookies = @($values) }
    return @{ Status = [int]$response.StatusCode; Body = $data; SetCookies = $setCookies }
}

function Get-Detail {
    param($Result)
    if ($null -ne $Result.Body) {
        foreach ($name in 'detail', 'message') {
            if ($Result.Body.PSObject.Properties.Name -contains $name -and $Result.Body.$name) { return [string]$Result.Body.$name }
        }
    }
    return ''
}

function Assert-Ok {
    param($Result, [string]$What)
    if ($Result.Status -lt 200 -or $Result.Status -ge 300) {
        $detail = Get-Detail $Result
        Fail "el PDP respondio $($Result.Status) al $What. $detail"
    }
}

function Get-Page {
    param([string]$BasePath)
    $items = New-Object System.Collections.Generic.List[object]
    $offset = 0
    while ($true) {
        $separator = if ($BasePath.Contains('?')) { '&' } else { '?' }
        $result = Invoke-Pdp 'GET' "$BasePath${separator}offset=$offset&limit=$PageSize"
        Assert-Ok $result "listar $BasePath"
        $page = $result.Body.data
        $content = @($page.content)
        foreach ($item in $content) { $items.Add($item) }
        $offset += $PageSize
        if ($content.Count -eq 0 -or $offset -ge [long]$page.total) { break }
    }
    return , $items.ToArray()
}

function Open-Session {
    $value = [Environment]::GetEnvironmentVariable($SessionVariable)
    if (-not $value) { Fail "falta la variable de entorno $SessionVariable con el valor de la cookie $SessionCookieName de un administrador de la aplicacion" 2 }
    $value = $value.Trim()
    if ($value.StartsWith("$SessionCookieName=")) { $value = $value.Substring($SessionCookieName.Length + 1) }
    if ($value -notmatch '^[A-Za-z0-9._~%+/=-]+$') { Fail "$SessionVariable tiene caracteres no validos para una cookie" 2 }
    $script:SessionValue = $value
    $result = Invoke-Pdp 'GET' '/api/v1/session'
    if ($result.Status -eq 401 -or $result.Status -eq 403) { Fail 'el PDP rechazo la sesion: expiro o no es valida. Inicia sesion en la consola y vuelve a copiar la cookie' }
    Assert-Ok $result 'validar la sesion'
    $token = ''
    foreach ($line in $result.SetCookies) {
        if ($line -match "(?i)(?:^|[\s,])$([regex]::Escape($CsrfCookieName))=([^;,\s]+)") { $token = $Matches[1] }
    }
    if (-not $token) { $token = [guid]::NewGuid().ToString('N') }
    $script:CsrfToken = $token
}

function Resolve-Application {
    if ($ApplicationId) { return @{ Id = $ApplicationId; Name = $ApplicationName } }
    $encoded = [Uri]::EscapeDataString($ApplicationName)
    $apps = Get-Page "/api/v1/applications?name=$encoded"
    $matching = @($apps | Where-Object { $_.name -eq $ApplicationName })
    if ($matching.Count -eq 0) { Fail "la aplicacion '$ApplicationName' no esta registrada en el PDP; registrala primero en la consola (ahi se entrega su credencial una sola vez)" }
    if ($matching.Count -gt 1) { Fail "hay mas de una aplicacion llamada '$ApplicationName'; indica -ApplicationId" }
    return @{ Id = [string]$matching[0].id; Name = $ApplicationName }
}

function Write-Action {
    param([string]$Tag, [string]$Text)
    Write-Output ("[{0}] {1}" -f $Tag, $Text)
}

$entries = Read-Catalog $CatalogPath
$desired = Get-DesiredState $entries
$counts = @{ created = 0; existing = 0; linked = 0; orphans = 0 }

$online = $true
if ($DryRun -and -not [Environment]::GetEnvironmentVariable($SessionVariable)) { $online = $false }

Write-Output "Aplicacion: $ApplicationName | PDP: $PdpUrl | modo: $(if ($DryRun) { 'dry-run' } else { 'aplicar' })"
Write-Output "Lista canonica: $($desired.Resources.Count) recursos available, $($RoleOrder.Count) roles, $($ProfileByRole.Count) perfiles"

if (-not $online) {
    Write-Output "PDP no consultado: $SessionVariable no esta definida, se lista el estado deseado completo"
    Write-Output 'Orden: recursos, roles, vinculos rol-recurso, perfiles, vinculos perfil-rol'
    foreach ($key in $desired.Resources.Keys) { Write-Action 'ASEGURAR' "recurso $key" }
    foreach ($role in $RoleOrder) {
        Write-Action 'ASEGURAR' "rol $role"
        foreach ($key in $desired.Grants[$role]) { Write-Action 'ASEGURAR' "vinculo rol $role -> recurso $key" }
    }
    foreach ($role in $RoleOrder) {
        $profile = $ProfileByRole[$role]
        Write-Action 'ASEGURAR' "perfil $profile"
        foreach ($member in $desired.Profiles[$profile]) { Write-Action 'ASEGURAR' "vinculo perfil $profile -> rol $member" }
    }
    Write-Output 'La asignacion de perfiles a usuarios no la hace este script: sigue siendo manual en la consola'
    exit 0
}

$script:Http = New-HttpClient
try {
    Open-Session
    $application = Resolve-Application
    $appId = $application.Id
    $appPath = [Uri]::EscapeDataString($appId)

    $listed = Invoke-Pdp 'GET' "/api/v1/applications/$appPath/resources"
    Assert-Ok $listed 'listar los recursos de la aplicacion'
    $resourceIds = @{}
    foreach ($resource in @($listed.Body.data)) { $resourceIds["$($resource.method) $($resource.path)"] = [string]$resource.id }

    foreach ($key in $desired.Resources.Keys) {
        $item = $desired.Resources[$key]
        if ($resourceIds.ContainsKey($key)) {
            $counts.existing++
            Write-Action 'EXISTE' "recurso $key"
            continue
        }
        if ($DryRun) {
            $counts.created++
            Write-Action 'CREAR' "recurso $key"
            continue
        }
        $created = Invoke-Pdp 'POST' "/api/v1/applications/$appPath/resources" @{ path = $item.Path; method = $item.Method } $true
        Assert-Ok $created "registrar el recurso $key"
        $resourceIds[$key] = [string]$created.Body.data.id
        $counts.created++
        Write-Action 'CREADO' "recurso $key"
    }
    foreach ($key in @($resourceIds.Keys)) {
        if (-not $desired.Resources.Contains($key)) {
            $counts.orphans++
            Write-Action 'HUERFANO' "recurso $key esta en el PDP y no en la lista canonica (no se borra)"
        }
    }

    $allRoles = Get-Page '/api/v1/roles'
    $roleState = @{}
    foreach ($role in $RoleOrder) {
        $found = @($allRoles | Where-Object { $_.name -eq $role -and [string]$_.applicationId -eq $appId })
        if ($found.Count -gt 0) {
            $roleState[$role] = @{ Id = [string]$found[0].id; Resources = @($found[0].resourceIds) }
            $counts.existing++
            Write-Action 'EXISTE' "rol $role"
        } elseif ($DryRun) {
            $roleState[$role] = @{ Id = ''; Resources = @() }
            $counts.created++
            Write-Action 'CREAR' "rol $role"
        } else {
            $created = Invoke-Pdp 'POST' '/api/v1/roles' @{ name = $role; scope = 'APPLICATION'; applicationId = $appId } $true
            Assert-Ok $created "crear el rol $role"
            $roleState[$role] = @{ Id = [string]$created.Body.data.id; Resources = @() }
            $counts.created++
            Write-Action 'CREADO' "rol $role"
        }
    }

    foreach ($role in $RoleOrder) {
        $state = $roleState[$role]
        foreach ($key in $desired.Grants[$role]) {
            $resourceId = if ($resourceIds.ContainsKey($key)) { $resourceIds[$key] } else { '' }
            if ($resourceId -and ($state.Resources -contains $resourceId)) {
                $counts.existing++
                Write-Action 'EXISTE' "vinculo rol $role -> recurso $key"
                continue
            }
            if ($DryRun) {
                $counts.linked++
                Write-Action 'VINCULAR' "rol $role -> recurso $key"
                continue
            }
            $linked = Invoke-Pdp 'POST' "/api/v1/roles/$([Uri]::EscapeDataString($state.Id))/resources" @{ resourceId = $resourceId } $true
            Assert-Ok $linked "vincular el rol $role con el recurso $key"
            $counts.linked++
            Write-Action 'VINCULADO' "rol $role -> recurso $key"
        }
        $wanted = @($desired.Grants[$role] | ForEach-Object { if ($resourceIds.ContainsKey($_)) { $resourceIds[$_] } })
        foreach ($extraId in $state.Resources) {
            if ($wanted -notcontains $extraId) {
                $counts.orphans++
                Write-Action 'DESVIO' "el rol $role tiene un recurso vinculado fuera de la lista canonica (id $extraId, no se revoca)"
            }
        }
    }

    $allProfiles = Get-Page '/api/v1/profiles'
    foreach ($role in $RoleOrder) {
        $profile = $ProfileByRole[$role]
        $found = @($allProfiles | Where-Object { $_.name -eq $profile -and [string]$_.applicationId -eq $appId })
        $profileId = ''
        $linkedRoles = @()
        if ($found.Count -gt 0) {
            $profileId = [string]$found[0].id
            $linkedRoles = @($found[0].roleIds)
            $counts.existing++
            Write-Action 'EXISTE' "perfil $profile"
        } elseif ($DryRun) {
            $counts.created++
            Write-Action 'CREAR' "perfil $profile"
        } else {
            $created = Invoke-Pdp 'POST' '/api/v1/profiles' @{ name = $profile; scope = 'APPLICATION'; applicationId = $appId } $true
            Assert-Ok $created "crear el perfil $profile"
            $profileId = [string]$created.Body.data.id
            $counts.created++
            Write-Action 'CREADO' "perfil $profile"
        }
        foreach ($member in $desired.Profiles[$profile]) {
            $memberId = $roleState[$member].Id
            if ($memberId -and ($linkedRoles -contains $memberId)) {
                $counts.existing++
                Write-Action 'EXISTE' "vinculo perfil $profile -> rol $member"
                continue
            }
            if ($DryRun) {
                $counts.linked++
                Write-Action 'VINCULAR' "perfil $profile -> rol $member"
                continue
            }
            $added = Invoke-Pdp 'POST' "/api/v1/profiles/$([Uri]::EscapeDataString($profileId))/roles" @{ roleId = $memberId } $true
            Assert-Ok $added "agregar el rol $member al perfil $profile"
            $counts.linked++
            Write-Action 'VINCULADO' "perfil $profile -> rol $member"
        }
    }
} finally {
    if ($null -ne $script:Http) { $script:Http.Dispose() }
}

$verb = if ($DryRun) { 'por crear' } else { 'creados' }
Write-Output ("Resumen: {0} {1}, {2} ya existian, {3} vinculos {4}, {5} desvios informados" -f $counts.created, $verb, $counts.existing, $counts.linked, $(if ($DryRun) { 'por crear' } else { 'creados' }), $counts.orphans)
Write-Output 'La asignacion de perfiles a usuarios no la hace este script: sigue siendo manual en la consola'
exit 0
