[CmdletBinding()]
param(
    [ValidateSet('unit', 'server', 'inventory', 'planning', 'settings', 'diagnostic', 'knowledge', 'baseline', 'all')]
    [string]$Group = 'unit',
    [ValidateSet('lease', 'timing')]
    [string]$Diagnostic,
    [switch]$Offline,
    [switch]$DryRun,
    [switch]$SelfTest
)

# 委托既有夹具；进程退出成功本身不是界面行为通过的证据。
# Delegate to the existing harnesses; process exit alone is not UI evidence.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

function New-TestStep {
    param([string[]]$Arguments, [string]$Suite = '', [string]$DiagnosticMode = 'none', [string]$KnowledgePhase = '')
    [pscustomobject]@{ Arguments = $Arguments; Suite = $Suite; Diagnostic = $DiagnosticMode; KnowledgePhase = $KnowledgePhase }
}

function Resolve-TestPlan {
    param([string]$Name, [string]$Mode)
    if ($Name -eq 'diagnostic') {
        if ($Mode -notin @('lease', 'timing')) { throw 'The diagnostic group requires -Diagnostic lease or timing.' }
    } elseif ($Mode) { throw '-Diagnostic is only valid with -Group diagnostic.' }
    switch ($Name) {
        'unit'      { New-TestStep @('cleanTest', 'test') }
        'server'    { New-TestStep @('runGameTestServer') }
        'inventory' { New-TestStep @('runClient', '-PcraftableClientSuite=inventory') 'inventory' }
        'planning'  { New-TestStep @('runClient', '-PcraftableClientSuite=planning') 'planning' }
        'settings'  { New-TestStep @('runClient', '-PcraftableClientSuite=settings') 'settings' }
        'diagnostic' { New-TestStep @('runClient', '-PcraftableClientSuite=planning', "-PcraftableClientDiagnostic=$Mode") 'planning' $Mode }
        'knowledge' {
            New-TestStep @('runServer', '-PcraftableKnowledgePhase=warmup') '' 'none' 'warmup'
            New-TestStep @('runServer', '-PcraftableKnowledgePhase=restart') '' 'none' 'restart'
        }
        'baseline'  { New-TestStep @('cleanTest', 'test', 'runGameTestServer', 'build') }
        'all' {
            New-TestStep @('cleanTest', 'test', 'runGameTestServer', 'build')
            New-TestStep @('runClient', '-PcraftableClientSuite=inventory') 'inventory'
            New-TestStep @('runClient', '-PcraftableClientSuite=planning') 'planning'
            New-TestStep @('runServer', '-PcraftableKnowledgePhase=warmup') '' 'none' 'warmup'
            New-TestStep @('runServer', '-PcraftableKnowledgePhase=restart') '' 'none' 'restart'
        }
        default { throw "Unknown test group: $Name" }
    }
}

function Assert-ClientRun {
    param([string]$Text, [string]$Suite, [string]$Mode)
    # 与 build.gradle/ClientTestRun 使用同一作用域和本次身份协议，不能借用旧 PASS。
    # Use the same scope/run-ID protocol as build.gradle and ClientTestRun; old PASS markers cannot authorize this run.
    $scope = 'suite=' + [regex]::Escape($Suite) + ' diagnostic=' + [regex]::Escape($Mode)
    $starts = [regex]::Matches($Text, '(?m)CLIENT_TEST START ' + $scope + ' run=([0-9a-f-]{36})\r?$')
    if ($starts.Count -ne 1 -or [regex]::Matches($Text, 'CLIENT_TEST START ').Count -ne 1) {
        throw "Missing or ambiguous client START marker for $Suite/$Mode."
    }
    $runId = $starts[0].Groups[1].Value
    $passes = [regex]::Matches($Text, '(?m)CLIENT_TEST PASS ' + $scope + ' run=' + [regex]::Escape($runId) + '\r?$')
    if ($passes.Count -ne 1 -or [regex]::Matches($Text, 'CLIENT_TEST PASS ').Count -ne 1 -or $passes[0].Index -le $starts[0].Index) {
        throw 'No unique matching current-run client PASS after START.'
    }
    if ($Text -match '(?:CLIENT_TEST|INVENTORY_SMOKE|PLANNING_SMOKE|CLIENT_SETTINGS|CLIENT_DIAGNOSTIC) FAIL\b') {
        throw 'A failed client assertion cannot be overridden by a later PASS.'
    }
    if (($Suite -ne 'planning' -or $Mode -ne 'none') -and $Text -match 'PLANNING_SMOKE PASS:') {
        throw 'A filtered or diagnostic run must not claim full planning PASS.'
    }
}

function Assert-ProcessExit {
    param($Code)
    if ($null -eq $Code -or $Code -ne 0) { throw "Gradle did not complete successfully (exit: $Code)." }
}

function Assert-KnowledgeRun {
    param([string]$Text, [string]$Phase)
    $scope = 'phase=' + [regex]::Escape($Phase)
    $starts = [regex]::Matches($Text, '(?m)KNOWLEDGE_TEST START ' + $scope + ' run=([0-9a-f-]{36})\r?$')
    if ($starts.Count -ne 1 -or [regex]::Matches($Text, 'KNOWLEDGE_TEST START ').Count -ne 1) {
        throw "Missing or ambiguous knowledge START marker for $Phase."
    }
    $runId = $starts[0].Groups[1].Value
    $passes = [regex]::Matches($Text, '(?m)KNOWLEDGE_TEST PASS ' + $scope + ' run=' + [regex]::Escape($runId) + '\r?$')
    if ($passes.Count -ne 1 -or [regex]::Matches($Text, 'KNOWLEDGE_TEST PASS ').Count -ne 1 -or $passes[0].Index -le $starts[0].Index) {
        throw 'No unique matching current-run knowledge PASS after START.'
    }
    if ($Text -match '(?:KNOWLEDGE_TEST|KNOWLEDGE_SERVER_SMOKE) FAIL\b') {
        throw 'A failed knowledge assertion cannot be overridden by a later PASS.'
    }
    if ($Text.Substring($passes[0].Index) -notmatch 'Craftable knowledge: saved keys=[1-9]\d* bytes=[1-9]\d*') {
        throw 'Knowledge process did not save a nonempty cache after its assertions passed.'
    }
}

function Invoke-LoggedProcess {
    param([string]$Command, [string[]]$Arguments, [string]$ConsoleLog)
    $previousErrorPreference = $ErrorActionPreference
    try {
        # PS 5.1 可把原生 stderr 包成 ErrorRecord；原生进程更新全局 LASTEXITCODE，
        # 若仅重置函数/脚本局部同名变量，反而会遮蔽真实退出码。
        # Native stderr may be an ErrorRecord in PS 5.1. The native process
        # updates GLOBAL LASTEXITCODE; a script/function-local reset shadows it.
        $ErrorActionPreference = 'Continue'
        $global:LASTEXITCODE = $null
        & $Command @Arguments 2>&1 | Tee-Object -FilePath $ConsoleLog -ErrorAction Stop | Out-Host -ErrorAction Stop
        return $global:LASTEXITCODE
    } finally { $ErrorActionPreference = $previousErrorPreference }
}

function Assert-ResponsibilityNames {
    param([string[]]$Paths)
    $retired = @($Paths | Where-Object {
        [IO.Path]::GetFileNameWithoutExtension($_) -match '^(?:pre[-_]?m\d|m\d|stage[-_]?\d)'
    })
    if ($retired.Count) { throw "Stage-prefixed test/fixture names must describe a responsibility instead: $($retired -join ', ')" }
}

function Invoke-RunnerSelfTest {
    $checks = 0
    foreach ($name in @('unit', 'server', 'inventory', 'planning', 'settings', 'knowledge', 'baseline', 'all')) {
        if (@(Resolve-TestPlan $name '').Count -eq 0) { throw "Empty plan for $name" }; $checks++
    }
    if (@(Resolve-TestPlan 'knowledge' '').Count -ne 2 -or @(Resolve-TestPlan 'all' '').Count -ne 5) { throw 'Lost regular test step.' }; $checks++
    foreach ($name in @('knowledge', 'all')) {
        $phases = @((Resolve-TestPlan $name '') | Where-Object KnowledgePhase)
        if ($phases.Count -ne 2 -or $phases[0].KnowledgePhase -ne 'warmup' -or $phases[1].KnowledgePhase -ne 'restart' -or
                $phases[0].Arguments -notcontains '-PcraftableKnowledgePhase=warmup' -or
                $phases[1].Arguments -notcontains '-PcraftableKnowledgePhase=restart') {
            throw 'Knowledge dispatch must warm up, then strictly reload in a second JVM.'
        }; $checks++
    }
    foreach ($mode in @('lease', 'timing')) {
        $step = @(Resolve-TestPlan 'diagnostic' $mode)[0]
        if ($step.Suite -ne 'planning' -or $step.Diagnostic -ne $mode -or $step.Arguments -notcontains "-PcraftableClientDiagnostic=$mode") {
            throw 'Diagnostic dispatch lost its explicit scope.'
        }; $checks++
    }
    foreach ($bad in @(@('bogus', ''), @('diagnostic', ''), @('diagnostic', 'bogus'), @('planning', 'timing'))) {
        $failed = $false
        try { Resolve-TestPlan $bad[0] $bad[1] | Out-Null } catch { $failed = $true }
        if (!$failed) { throw 'Invalid runner arguments were accepted.' }; $checks++
    }
    $id = '11111111-1111-1111-1111-111111111111'
    $old = '22222222-2222-2222-2222-222222222222'
    foreach ($scope in @(@('planning', 'none'), @('settings', 'none'), @('planning', 'lease'), @('planning', 'timing'), @('inventory', 'none'))) {
        $start = "CLIENT_TEST START suite=$($scope[0]) diagnostic=$($scope[1]) run=$id"
        $pass = "CLIENT_TEST PASS suite=$($scope[0]) diagnostic=$($scope[1]) run=$id"
        Assert-ClientRun "$start`n$pass" $scope[0] $scope[1]; $checks++
    }
    $start = "CLIENT_TEST START suite=planning diagnostic=none run=$id"
    $pass = "CLIENT_TEST PASS suite=planning diagnostic=none run=$id"
    foreach ($text in @(
        'PLANNING_SMOKE PASS: old or fabricated result',
        "$start`nCLIENT_TEST PASS suite=planning diagnostic=none run=$old",
        "$pass`n$start",
        "CLIENT_TEST START suite=settings diagnostic=none run=$id`nCLIENT_TEST PASS suite=settings diagnostic=none run=$id",
        "$start`n$start`n$pass", "$start`n$pass`n$pass",
        "$start`nCLIENT_TEST PASS suite=settings diagnostic=none run=$old`n$pass")) {
        $failed = $false
        try { Assert-ClientRun $text 'planning' 'none' } catch { $failed = $true }
        if (!$failed) { throw 'A stale, failed, mixed-scope, or fake PASS was accepted.' }; $checks++
    }
    foreach ($marker in @('CLIENT_TEST', 'INVENTORY_SMOKE', 'PLANNING_SMOKE', 'CLIENT_SETTINGS', 'CLIENT_DIAGNOSTIC')) {
        $failed = $false
        try { Assert-ClientRun "$start`n$marker FAIL assertion`n$pass" 'planning' 'none' } catch { $failed = $true }
        if (!$failed) { throw "A $marker failure was overridden by PASS." }; $checks++
    }
    foreach ($scope in @(@('settings', 'none'), @('inventory', 'none'), @('planning', 'lease'), @('planning', 'timing'))) {
        $failed = $false
        $filteredStart = "CLIENT_TEST START suite=$($scope[0]) diagnostic=$($scope[1]) run=$id"
        $filteredPass = "CLIENT_TEST PASS suite=$($scope[0]) diagnostic=$($scope[1]) run=$id"
        try { Assert-ClientRun "$filteredStart`nPLANNING_SMOKE PASS: false-full`n$filteredPass" $scope[0] $scope[1] } catch { $failed = $true }
        if (!$failed) { throw 'Filtered or diagnostic run claimed full planning PASS.' }; $checks++
    }
    $saved = 'Craftable knowledge: saved keys=4 bytes=1118'
    foreach ($phase in @('warmup', 'restart')) {
        Assert-KnowledgeRun "KNOWLEDGE_TEST START phase=$phase run=$id`nKNOWLEDGE_TEST PASS phase=$phase run=$id`n$saved" $phase
        $checks++
    }
    $knowledgeStart = "KNOWLEDGE_TEST START phase=restart run=$id"
    $knowledgePass = "KNOWLEDGE_TEST PASS phase=restart run=$id"
    foreach ($text in @(
        'KNOWLEDGE_SERVER_SMOKE PASS: old or fabricated result',
        "$knowledgeStart`nKNOWLEDGE_TEST PASS phase=restart run=$old`n$saved",
        "$knowledgePass`n$knowledgeStart`n$saved",
        "$knowledgeStart`nKNOWLEDGE_TEST FAIL assertion`n$knowledgePass`n$saved",
        "$knowledgeStart`nKNOWLEDGE_SERVER_SMOKE FAIL assertion`n$knowledgePass`n$saved",
        "KNOWLEDGE_TEST START phase=warmup run=$id`nKNOWLEDGE_TEST PASS phase=warmup run=$id`n$saved",
        "$knowledgeStart`n$knowledgeStart`n$knowledgePass`n$saved",
        "$knowledgeStart`n$knowledgePass`n$knowledgePass`n$saved",
        "$knowledgeStart`n$knowledgePass",
        "$knowledgeStart`n$saved`n$knowledgePass",
        "$knowledgeStart`n$knowledgePass`nCraftable knowledge: saved keys=0 bytes=1118")) {
        $failed = $false
        try { Assert-KnowledgeRun $text 'restart' } catch { $failed = $true }
        if (!$failed) { throw 'A wrong-phase, failed, stale, or unsaved knowledge PASS was accepted.' }; $checks++
    }
    Assert-ProcessExit 0; $checks++
    foreach ($code in @($null, 1, -1)) {
        $failed = $false
        try { Assert-ProcessExit $code } catch { $failed = $true }
        if (!$failed) { throw 'A missing/failed native process was accepted.' }; $checks++
    }
    # 用嵌套作用域遮蔽自动变量并运行真实日志管线，才能发现退出码遮蔽；只向校验器喂虚构整数测不到。
    # Exercise the actual logged pipeline from a nested scope with a shadowed
    # automatic variable, not just Assert-ProcessExit with invented integers.
    foreach ($expectedExit in @(3, 0)) {
        $temporaryLog = New-TemporaryFile
        try {
            $actualExit = & {
                param($RequestedExit, $LogPath)
                $LASTEXITCODE = -99
                Invoke-LoggedProcess 'cmd.exe' @('/d', '/c', "exit $RequestedExit") $LogPath
            } $expectedExit $temporaryLog.FullName
            if ($actualExit -ne $expectedExit) { throw "Native logged pipeline lost exit $expectedExit (got: $actualExit)." }; $checks++
            $failed = $false
            try { Assert-ProcessExit $actualExit } catch { $failed = $true }
            if ($failed -ne ($expectedExit -ne 0)) { throw 'Real native process exit was misclassified.' }; $checks++
        } finally { Remove-Item -LiteralPath $temporaryLog.FullName -Force }
    }
    foreach ($name in @('ConfigCorrectionTest.java', 'RecipeStatusLifecycleTest.java', 'MixedMaterialScenario.java', 'recipe-yield-reload', 'ingredient-tag-reload')) {
        Assert-ResponsibilityNames @($name); $checks++
    }
    foreach ($name in @('PreM5ConfigTest.java', 'M3StatusLifecycleTest.java', 'm47-yield', 'pre-m5-fixture', 'Stage7Scenario.java')) {
        $failed = $false
        try { Assert-ResponsibilityNames @($name) } catch { $failed = $true }
        if (!$failed) { throw "Stage-prefixed test name was accepted: $name" }; $checks++
    }
    $testNames = @(
        Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src/test'), (Join-Path $projectRoot 'src/integrationTest') -Recurse -File -Filter '*.java' | ForEach-Object FullName
        Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src/main') -Recurse -File -Filter '*.java' | Where-Object Name -Match '(GameTests|TestSupport)\.java$' | ForEach-Object FullName
        Get-ChildItem -LiteralPath (Join-Path $projectRoot 'tests/datapacks') -Directory | ForEach-Object FullName
    )
    Assert-ResponsibilityNames $testNames; $checks++
    Write-Host "RUNNER_SELF_TEST PASS: $checks dispatch/argument/scope/fresh-run/false-PASS/naming checks; no Gradle or Minecraft launched."
}

if ($SelfTest) {
    if ($PSBoundParameters.ContainsKey('Group') -or $Diagnostic -or $DryRun -or $Offline) {
        throw '-SelfTest must be used by itself.'
    }
    Invoke-RunnerSelfTest
    return
}
$steps = @(Resolve-TestPlan $Group $Diagnostic)
if (!(Test-Path -LiteralPath (Join-Path $projectRoot 'gradlew.bat') -PathType Leaf)) { throw 'Gradle wrapper is missing.' }
Push-Location -LiteralPath $projectRoot
try {
    $scopeName = $Group + $(if ($Diagnostic) { '-' + $Diagnostic })
    $outputDir = Join-Path $projectRoot ('run/verification/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + $scopeName)
    if (!$DryRun) { New-Item -ItemType Directory -Path $outputDir | Out-Null }
    $number = 0
    $knowledgeCachePath = Join-Path $projectRoot 'run/knowledgeServerSmoke/craftable-knowledge-smoke/craftable/cache/recipe-knowledge-v1.json'
    $knowledgeCacheHash = $null
    foreach ($step in $steps) {
        $number++
        $gradleArgs = @($step.Arguments) + @('--console=plain', '--no-daemon', '--no-configuration-cache')
        if ($step.Arguments -contains 'test') { $gradleArgs += '--no-build-cache' }
        if ($Offline) { $gradleArgs += '--offline' }
        if ($step.KnowledgePhase -eq 'restart') {
            if (!$DryRun -and (!$knowledgeCacheHash -or (Get-FileHash -LiteralPath $knowledgeCachePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $knowledgeCacheHash)) {
                throw "Restart cache is missing or differs from this test group's completed warmup output."
            }
            $expectedHash = if ($DryRun) { '<warmup-sha256>' } else { $knowledgeCacheHash }
            $gradleArgs += "-PcraftableKnowledgeExpectedCache=$expectedHash"
        }
        Write-Host ('.\gradlew.bat ' + ($gradleArgs -join ' '))
        if ($DryRun) { continue }
        $consoleLog = Join-Path $outputDir ("$number-console.log")
        $code = Invoke-LoggedProcess '.\gradlew.bat' $gradleArgs $consoleLog
        foreach ($runName in @('gameTestServer', 'client', 'server')) {
            $task = switch ($runName) { 'gameTestServer' { 'runGameTestServer' }; 'client' { 'runClient' }; 'server' { 'runServer' } }
            $log = Join-Path $projectRoot "run/$runName/logs/latest.log"
            if ($step.Arguments -contains $task -and (Test-Path -LiteralPath $log)) {
                Copy-Item -LiteralPath $log -Destination (Join-Path $outputDir "$number-$runName.log")
            }
        }
        try { Assert-ProcessExit $code } catch { throw "Test group '$scopeName' failed at step $number (exit $code). See $consoleLog" }
        $console = Get-Content -LiteralPath $consoleLog -Raw
        if ($step.Suite) { Assert-ClientRun $console $step.Suite $step.Diagnostic }
        if ($step.Arguments -contains 'runGameTestServer' -and $console -notmatch 'All \d+ required tests passed') {
            throw "GameTest completion marker missing from $consoleLog"
        }
        if ($step.KnowledgePhase) {
            Assert-KnowledgeRun $console $step.KnowledgePhase
            if ($step.KnowledgePhase -eq 'warmup') {
                $knowledgeCacheHash = (Get-FileHash -LiteralPath $knowledgeCachePath -Algorithm SHA256).Hash.ToLowerInvariant()
            }
        }
    }
    if (!$DryRun) {
        $label = if ($Group -eq 'diagnostic') { 'Diagnostic only (not full planning)' } elseif ($Group -eq 'settings') { 'Settings only (not full planning)' } else { "Test group '$Group'" }
        Write-Host "$label passed. Evidence: $outputDir"
    }
} finally { Pop-Location }
