param([string]$Output = 'target/test-performance/inventory.csv')
$ErrorActionPreference = 'Stop'
# Source indicators are conservative review candidates, not proof of runtime I/O.
$patterns = [ordered]@{
    Spring = '@SpringBootTest|@WebMvcTest|@DataJpaTest|@ContextConfiguration|ApplicationContextRunner|AnnotationConfigApplicationContext|GenericApplicationContext|SpringApplicationBuilder|new SpringApplication'
    SpringBootTest = '@SpringBootTest'
    ContextCustomization = '@DirtiesContext|@MockBean|@SpyBean|@MockitoBean|@MockitoSpyBean|@ActiveProfiles|@TestPropertySource|@DynamicPropertySource'
    SQLite = 'jdbc:sqlite|new Sqlite\w+|new (?:org\.sqlite\.)?SQLiteDataSource|new DriverManagerDataSource'
    Filesystem = '@TempDir|Files\.|new File\(|\.toFile\(|new FileSessionRepository|new FileSystem\w+|createTemp'
    HTTP = 'HttpServer|MockWebServer|ServerSocket|TomcatServletWebServerFactory|WireMock|HttpClient\.new|HttpRequest\.new'
    Process = 'new ProcessBuilder|ProcessHandle|javaCommand\(|buildGitCommand\('
    Threads = 'new Thread\(|Executors\.|ExecutorService|CompletableFuture|CountDownLatch'
    Sleep = 'Thread\.sleep\('
    Polling = 'deadline|await\(|\.get\(\d+,\s*TimeUnit'
    EventBus = 'EventBus'
    GlobalState = 'System\.setProperty|System\.clearProperty|System\.setOut|System\.setErr|static (?!final)[\w<>]+\s+\w+\s*[=;]'
    Environment = 'System\.getenv|environment|REI_DATA_DIR'
    Testcontainers = 'Testcontainers|org\.testcontainers'
    External = 'setAutoDownload\(true\)|releaseBaseUrl|api\.openai\.com'
}
$rows = foreach ($file in (Get-ChildItem src/test/java -Recurse -Filter '*.java' | Where-Object Name -match 'Tests?\.java$')) {
    $source = Get-Content -Raw -LiteralPath $file.FullName
    $row = [ordered]@{Path=[IO.Path]::GetRelativePath((Get-Location).Path, $file.FullName).Replace('\','/'); Class=$file.BaseName}
    foreach ($name in $patterns.Keys) { $row[$name] = [bool]($source -match $patterns[$name]) }
    $row['Category'] = if ($source -match '@(?:org.junit.jupiter.api|net.jqwik.api).Tag\("e2e"\)') { 'E2E' }
        elseif ($source -match '@(?:org.junit.jupiter.api|net.jqwik.api).Tag\("integration"\)') { 'Integration' }
        elseif ($row.Spring -or $row.SQLite -or $row.Filesystem -or $row.HTTP -or $row.Process -or $row.External) { 'Integration candidate' }
        elseif ($source -match 'InMemory|Fake|mock\(|Mockito|EventBus') { 'Component' } else { 'Unit' }
    [pscustomobject]$row
}
New-Item -ItemType Directory -Force (Split-Path $Output) | Out-Null
$rows | Export-Csv $Output -NoTypeInformation -Encoding utf8
$rows | Group-Object Category | Select-Object Name,Count
