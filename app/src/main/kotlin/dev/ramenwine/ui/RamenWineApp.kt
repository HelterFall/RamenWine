package dev.ramenwine.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ramenwine.display.DisplaySession
import dev.ramenwine.display.WawonaDisplayBackend
import dev.ramenwine.display.WawonaSurfaceHost
import dev.ramenwine.importer.GameFolderImporter
import dev.ramenwine.importer.ImportProgress
import dev.ramenwine.inspection.ExecutableCandidate
import dev.ramenwine.inspection.FolderScanResult
import dev.ramenwine.inspection.GameFolderScanner
import dev.ramenwine.inspection.PeArchitecture
import dev.ramenwine.library.GameLibraryStore
import dev.ramenwine.library.GameLocaleProfile
import dev.ramenwine.library.GameStorageManager
import dev.ramenwine.library.GameStorageUsage
import dev.ramenwine.library.ImportedGame
import dev.ramenwine.runtime.GameSession
import dev.ramenwine.runtime.GameSessionCoordinator
import dev.ramenwine.runtime.NativeRuntime
import dev.ramenwine.runtime.RuntimeBackendId
import dev.ramenwine.runtime.RuntimeLayout
import dev.ramenwine.runtime.RuntimePackageInstaller
import dev.ramenwine.runtime.RuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RamenWineApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val libraryStore = remember(context.filesDir) {
        GameLibraryStore(context.applicationContext)
    }
    val storageManager = remember(context.filesDir) {
        GameStorageManager(context.filesDir, context.cacheDir, libraryStore::remove)
    }

    var selectedFolder by remember { mutableStateOf<Uri?>(null) }
    var scanResult by remember { mutableStateOf<FolderScanResult?>(null) }
    var selectedExecutable by remember { mutableStateOf<ExecutableCandidate?>(null) }
    var importedGames by remember { mutableStateOf(libraryStore.list()) }
    var gameStorageUsage by remember {
        mutableStateOf<Map<String, GameStorageUsage>>(emptyMap())
    }
    var sharedRuntimeBytes by remember { mutableStateOf(0L) }
    var pendingDeletionGame by remember { mutableStateOf<ImportedGame?>(null) }
    var deletingGameId by remember { mutableStateOf<String?>(null) }
    var storageError by remember { mutableStateOf<String?>(null) }
    var storageNotice by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf(ImportProgress(0, 0L)) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importNotice by remember { mutableStateOf<String?>(null) }

    var runtimeInspection by remember(context.filesDir) {
        mutableStateOf(RuntimeLayout.inspect(context.filesDir))
    }
    var installingRuntime by remember { mutableStateOf(false) }
    var runtimePackageError by remember { mutableStateOf<String?>(null) }
    var runtimePackageNotice by remember { mutableStateOf<String?>(null) }
    val nativeDiagnostic = remember { NativeRuntime.diagnostic() }

    val displayBackend = remember(context.applicationContext) {
        WawonaDisplayBackend(context.applicationContext)
    }
    val gameSessionCoordinator = remember(context.filesDir) {
        GameSessionCoordinator(context.applicationContext, displayBackend)
    }
    var selectedDisplayGame by remember { mutableStateOf<ImportedGame?>(null) }
    var activeDisplaySession by remember { mutableStateOf<DisplaySession?>(null) }
    var activeGameSession by remember { mutableStateOf<GameSession?>(null) }
    var surfaceReady by remember { mutableStateOf(false) }
    var preparingGame by remember { mutableStateOf(false) }
    var gameLaunchError by remember { mutableStateOf<String?>(null) }
    var gameLaunchNotice by remember { mutableStateOf<String?>(null) }
    var processOutput by remember { mutableStateOf("") }

    suspend fun refreshLibraryStorage() {
        val snapshot = withContext(Dispatchers.IO) {
            val games = libraryStore.list()
            val usage = games.associate { game ->
                game.id to storageManager.usage(game)
            }
            Triple(games, usage, storageManager.sharedRuntimeBytes())
        }
        importedGames = snapshot.first
        gameStorageUsage = snapshot.second
        sharedRuntimeBytes = snapshot.third
    }

    LaunchedEffect(context.filesDir) {
        try {
            withContext(Dispatchers.IO) {
                releaseUnusedPersistedTreePermissions(context)
                storageManager.cleanupAbandonedData(
                    registeredGameIds = libraryStore.list().map { it.id }.toSet(),
                    canPruneUnlistedData = libraryStore.hasTrustworthyIndex()
                )
            }
            refreshLibraryStorage()
            if (!libraryStore.hasTrustworthyIndex() &&
                java.io.File(context.filesDir, "games/library.json").exists()
            ) {
                storageError = "Индекс библиотеки повреждён или имеет неизвестную версию. " +
                    "Неиспользуемые папки не удалялись автоматически, чтобы сохранить данные."
            }
        } catch (error: Exception) {
            storageError = error.message ?: error.javaClass.simpleName
        }

        val installedBackend = RuntimeLayout.inspect(context.filesDir)
            .backends
            .firstOrNull { it.backend == RuntimeBackendId.GLIBC_PROOT_BOX64 }

        if (installedBackend?.state != RuntimeState.FILES_PRESENT) {
            installingRuntime = true
            runtimePackageError = null
            runtimePackageNotice = null
            try {
                val result = withContext(Dispatchers.IO) {
                    RuntimePackageInstaller(context.applicationContext).installBundled()
                }
                runtimeInspection = RuntimeLayout.inspect(context.filesDir)
                runtimePackageNotice = result.runtimeVersion +
                    " · " + result.installedFileCount + " файлов"
                // The shared runtime is installed after the initial storage
                // snapshot. Refresh the summary so it never keeps showing 0 B.
                refreshLibraryStorage()
            } catch (error: Exception) {
                runtimePackageError = error.message ?: error.javaClass.simpleName
                runtimeInspection = RuntimeLayout.inspect(context.filesDir)
            } finally {
                installingRuntime = false
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            selectedFolder?.let { previous ->
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        previous,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            selectedFolder = uri
            scanResult = null
            selectedExecutable = null
            scanError = null
            importError = null
            importNotice = null

            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            scanning = true
            scope.launch {
                try {
                    scanResult = withContext(Dispatchers.IO) {
                        GameFolderScanner(context.applicationContext).scan(uri)
                    }
                } catch (error: Exception) {
                    scanError = error.message ?: error.javaClass.simpleName
                } finally {
                    scanning = false
                }
            }
        }
    }

    fun startImport() {
        val folderUri = selectedFolder ?: return
        val candidate = selectedExecutable ?: return
        val title = scanResult?.folderName ?: "Visual novel"

        importing = true
        importProgress = ImportProgress(0, 0L)
        importError = null
        importNotice = null

        scope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    GameFolderImporter(context.applicationContext).importGame(
                        treeUri = folderUri,
                        gameTitle = title,
                        selectedExecutableRelativePath = candidate.relativePath,
                        selectedExecutableUri = candidate.uri,
                        onProgress = { progress ->
                            scope.launch {
                                importProgress = progress
                            }
                        }
                    )
                }
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        folderUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                selectedFolder = null
                selectedExecutable = null
                scanResult = null
                refreshLibraryStorage()
                importNotice = "Игра импортирована: " + imported.title
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                importError = error.message ?: error.javaClass.simpleName
            } finally {
                importing = false
            }
        }
    }

    fun prepareGame(game: ImportedGame) {
        if (preparingGame || activeDisplaySession != null ||
            activeGameSession?.isRunning == true
        ) return

        gameLaunchError = null
        gameLaunchNotice = "Проверяю Wawona и подготавливаю ресурсы…"
        processOutput = ""
        preparingGame = true

        scope.launch {
            try {
                activeDisplaySession = gameSessionCoordinator.openDisplay()
                selectedDisplayGame = game
                surfaceReady = false
                gameLaunchNotice = "Compositor запущен. Подключаю Android Surface…"
            } catch (error: Exception) {
                gameLaunchError = error.message ?: error.javaClass.simpleName
                gameLaunchNotice = null
            } finally {
                preparingGame = false
            }
        }
    }

    fun launchSelectedGame() {
        val game = selectedDisplayGame ?: return
        if (!surfaceReady || preparingGame) return

        preparingGame = true
        gameLaunchError = null
        gameLaunchNotice = "Проверяю runtime и запускаю Windows EXE…"
        scope.launch {
            try {
                val launched = gameSessionCoordinator.launchGame(
                    game = game,
                    onOutput = { line ->
                        processOutput = (processOutput + line + "\n").takeLast(8_000)
                    },
                    onExit = { exitCode ->
                        activeGameSession = null
                        activeDisplaySession = null
                        selectedDisplayGame = null
                        surfaceReady = false
                        preparingGame = false
                        gameLaunchNotice = "Windows-процесс завершился (код $exitCode)."
                    }
                )
                if (launched.isRunning) {
                    activeGameSession = launched
                    gameLaunchNotice = "Процесс игры запущен. Диагностика доступна ниже."
                } else {
                    activeGameSession = null
                    activeDisplaySession = null
                    selectedDisplayGame = null
                    surfaceReady = false
                    gameLaunchNotice = "Процесс завершился до подтверждения запуска."
                }
            } catch (error: Exception) {
                gameLaunchError = error.message ?: error.javaClass.simpleName
                runCatching { gameSessionCoordinator.closeDisplay() }
                activeGameSession = null
                activeDisplaySession = null
                selectedDisplayGame = null
                surfaceReady = false
            } finally {
                preparingGame = false
            }
        }
    }

    fun stopSelectedGame() {
        scope.launch {
            preparingGame = true
            val running = activeGameSession
            try {
                if (running != null) {
                    running.stop()
                } else {
                    gameSessionCoordinator.closeDisplay()
                }
            } catch (error: Exception) {
                gameLaunchError = error.message ?: error.javaClass.simpleName
            } finally {
                // Do not remove the Surface or forget the session while the
                // process is still alive; that would strand a running game.
                if (running == null || !running.isRunning) {
                    activeGameSession = null
                    activeDisplaySession = null
                    selectedDisplayGame = null
                    surfaceReady = false
                }
                preparingGame = false
            }
        }
    }

    fun changeGameLocale(game: ImportedGame, locale: String) {
        if (!GameLocaleProfile.isSupported(locale)) return
        val updatedGame = game.copy(locale = locale)
        try {
            libraryStore.add(updatedGame)
            importedGames = importedGames.map { current ->
                if (current.id == game.id) updatedGame else current
            }
            storageError = null
            storageNotice = "Язык среды Wine для «" + game.title +
                "» сохранён. Изменение применяется при следующем запуске."
        } catch (error: Exception) {
            storageError = error.message ?: error.javaClass.simpleName
        }
    }

    fun deleteImportedGame(game: ImportedGame) {
        if (preparingGame || importing ||
            selectedDisplayGame?.id == game.id ||
            activeGameSession?.game?.id == game.id
        ) {
            storageError = "Сначала закрой экран этой игры, затем удаляй её."
            return
        }
        deletingGameId = game.id
        storageError = null
        storageNotice = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    storageManager.deleteGame(game)
                }
                refreshLibraryStorage()
                storageNotice = "Игра и её сохранения удалены. Общий runtime оставлен для остальных игр."
            } catch (error: Exception) {
                storageError = error.message ?: error.javaClass.simpleName
            } finally {
                deletingGameId = null
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "RAMENWINE",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "Windows visual novel runtime for Android",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Development build · Phase 1: import and EXE inspection",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                RuntimeStatusCard(
                    runtimeInspection = runtimeInspection,
                    nativeDiagnostic = nativeDiagnostic
                )

                StorageSummaryCard(
                    games = importedGames,
                    usage = gameStorageUsage,
                    sharedRuntimeBytes = sharedRuntimeBytes
                )
                storageError?.let {
                    Text("Хранилище: " + it, color = MaterialTheme.colorScheme.error)
                }
                storageNotice?.let {
                    Text(it, color = MaterialTheme.colorScheme.secondary)
                }

                if (installingRuntime) {
                    Text(
                        "Проверяю и устанавливаю локальный runtime-пакет…",
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                runtimePackageError?.let {
                    Text(
                        text = "Runtime-пакет не установлен: " + it,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                runtimePackageNotice?.let {
                    Text(
                        text = "Runtime установлен: " + it,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Button(
                    onClick = { folderPicker.launch(null) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !scanning && !importing
                ) {
                    Text(
                        when {
                            scanning -> "Сканирование…"
                            importing -> "Импортирование…"
                            else -> "Выбрать папку игры"
                        }
                    )
                }

                selectedFolder?.let {
                    Text(
                        text = "Выбрана папка: " + (it.lastPathSegment ?: it.toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                when {
                    scanning -> Text("Безопасно просматриваю файлы…")
                    scanError != null -> Text(
                        text = "Ошибка сканирования: " + scanError,
                        color = MaterialTheme.colorScheme.error
                    )
                    scanResult != null -> ScanResultCard(
                        result = scanResult!!,
                        selectedExecutable = selectedExecutable,
                        onSelectExecutable = {
                            selectedExecutable = it
                            importError = null
                            importNotice = null
                        }
                    )
                }

                selectedExecutable?.let { candidate ->
                    SelectedExecutableCard(
                        candidate = candidate,
                        importing = importing,
                        canImport = scanResult?.canImport == true,
                        onImport = ::startImport
                    )
                }

                if (importing) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Копирую игру в приватное хранилище приложения…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = "Файлов: " + importProgress.copiedFiles +
                                " · Скопировано: " + formatFileSize(importProgress.copiedBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                importError?.let {
                    Text(
                        text = "Ошибка импорта: " + it,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                importNotice?.let {
                    Text(it, color = MaterialTheme.colorScheme.secondary)
                }

                if (importedGames.isNotEmpty()) {
                    LibraryCard(
                        games = importedGames,
                        storageUsage = gameStorageUsage,
                        canPrepareGame = activeDisplaySession == null &&
                            activeGameSession?.isRunning != true && !preparingGame,
                        preparingGame = preparingGame,
                        deletingGameId = deletingGameId,
                        canDeleteGame = { game ->
                            deletingGameId == null && !importing && !preparingGame &&
                                selectedDisplayGame?.id != game.id &&
                                activeGameSession?.game?.id != game.id
                        },
                        onPrepareGame = ::prepareGame,
                        onDeleteGame = { pendingDeletionGame = it },
                        onChangeLocale = ::changeGameLocale
                    )
                }

                gameLaunchError?.let {
                    Text(
                        text = "Не удалось открыть игру: $it",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                gameLaunchNotice?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.secondary)
                }

                val currentDisplay = activeDisplaySession
                val currentGame = selectedDisplayGame
                if (currentDisplay != null && currentGame != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(currentGame.title, fontWeight = FontWeight.Bold)
                            Text(
                                text = if (surfaceReady) {
                                    "Wawona Surface подключён · Wayland socket готов"
                                } else {
                                    "Ожидаю Android Surface…"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            WawonaSurfaceHost(
                                session = currentDisplay,
                                modifier = Modifier.fillMaxWidth().height(320.dp),
                                onSurfaceStateChanged = { attached -> surfaceReady = attached }
                            )
                            if (activeGameSession == null) {
                                Button(
                                    onClick = ::launchSelectedGame,
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = surfaceReady && !preparingGame
                                ) {
                                    Text(
                                        if (preparingGame) "Подготовка…"
                                        else "Запустить Windows EXE"
                                    )
                                }
                            } else {
                                Button(
                                    onClick = ::stopSelectedGame,
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !preparingGame
                                ) {
                                    Text("Остановить игру")
                                }
                            }
                            Button(
                                onClick = ::stopSelectedGame,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !preparingGame
                            ) {
                                Text("Закрыть игровой экран")
                            }
                            if (processOutput.isNotBlank()) {
                                HorizontalDivider()
                                Text("Вывод runtime", fontWeight = FontWeight.Medium)
                                Text(
                                    text = processOutput.takeLast(8_000),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 16
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "Запуск разрешается только после подключения Wawona Surface и проверки " +
                        "локального PRoot/Wine runtime. Отсутствующие компоненты блокируют запуск.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        pendingDeletionGame?.let { game ->
            AlertDialog(
                onDismissRequest = { pendingDeletionGame = null },
                title = { Text("Удалить «" + game.title + "»?") },
                text = {
                    Text(
                        "Будут удалены копия игры, сохранения и настройки Wine, " +
                            "а также временные файлы. Исходная папка, которую ты выбрал " +
                            "при импорте, не изменится. Общий runtime останется для других игр."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingDeletionGame = null
                            deleteImportedGame(game)
                        }
                    ) {
                        Text("Удалить игру и сохранения")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeletionGame = null }) {
                        Text("Отмена")
                    }
                }
            )
        }
    }
}

@Composable
private fun StorageSummaryCard(
    games: List<ImportedGame>,
    usage: Map<String, GameStorageUsage>,
    sharedRuntimeBytes: Long
) {
    val gameBytes = games.fold(0L) { total, game ->
        val bytes = usage[game.id]?.totalBytes ?: game.totalBytes
        if (bytes > 0L && total > Long.MAX_VALUE - bytes) Long.MAX_VALUE
        else total + bytes
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text("Память RamenWine", fontWeight = FontWeight.Bold)
            Text("Игры, сохранения и временные данные: " + formatFileSize(gameBytes))
            Text("Общий runtime: " + formatFileSize(sharedRuntimeBytes))
            Text(
                "Данные находятся в приватной памяти приложения. Удалять папки через " +
                    "файловый менеджер не нужно: для каждой игры есть отдельная очистка ниже.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RuntimeStatusCard(
    runtimeInspection: dev.ramenwine.runtime.RuntimeInspection,
    nativeDiagnostic: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Runtime environment", fontWeight = FontWeight.Bold)
            when (runtimeInspection.state) {
                RuntimeState.NOT_INSTALLED -> Text("Windows runtime ещё не установлен.")
                RuntimeState.INCOMPLETE -> Text(
                    "Найдена неполная установка runtime. Проверь отсутствующие файлы ниже."
                )
                RuntimeState.FILES_PRESENT -> Text(
                    "Найден как минимум один комплект runtime-файлов. Реальный запуск ещё нужно проверить."
                )
            }
            runtimeInspection.backends.forEach { backend ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val stateLabel = when (backend.state) {
                        RuntimeState.NOT_INSTALLED -> "не установлен"
                        RuntimeState.INCOMPLETE -> "неполный"
                        RuntimeState.FILES_PRESENT -> "файлы на месте"
                    }
                    Text(
                        text = backend.backend.displayName + " · " + stateLabel,
                        fontWeight = FontWeight.Medium
                    )
                    backend.runtimeVersion?.let {
                        Text(
                            text = "Версия: " + it,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    backend.missingItems.forEach { missing ->
                        Text(
                            text = "• " + missing,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Text(
                text = "Путь runtime: " + runtimeInspection.runtimeRoot.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()
            Text("Native bridge", fontWeight = FontWeight.Bold)
            Text(text = nativeDiagnostic, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SelectedExecutableCard(
    candidate: ExecutableCandidate,
    importing: Boolean,
    canImport: Boolean,
    onImport: () -> Unit
) {
    val isValidPe = candidate.peInspection?.isValidPe == true
    val architecture = candidate.peInspection?.architecture
    val isSupportedArchitecture =
        architecture == PeArchitecture.X86 || architecture == PeArchitecture.X86_64

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Главный EXE выбран", fontWeight = FontWeight.Bold)
            Text(candidate.relativePath)
            Text(
                text = "Архитектура: " +
                    (candidate.peInspection?.architecture?.displayName ?: "не определена"),
                style = MaterialTheme.typography.bodySmall
            )
            candidate.peInspection?.diagnostic?.let {
                Text(it, style = MaterialTheme.typography.labelSmall)
            }

            if (!isValidPe) {
                Text(
                    "Заголовок PE не прошёл проверку. Выбери другой EXE.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            } else if (!isSupportedArchitecture) {
                Text(
                    "Этот EXE собран для " +
                        (candidate.peInspection?.architecture?.displayName ?: "неизвестной архитектуры") +
                        ". Сейчас доступны только Windows x86 и x86-64. " +
                        "Файлы игры не будут копироваться.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (!canImport) {
                Text(
                    "Импорт пока заблокирован: сканирование обнаружило недоступные, " +
                        "неподдерживаемые или непроверенные элементы. Выбери более узкую " +
                        "папку игры или исправь указанные проблемы и просканируй снова.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Button(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth(),
                enabled = isValidPe && isSupportedArchitecture && canImport && !importing
            ) {
                Text(if (importing) "Импортирование…" else "Импортировать игру")
            }
        }
    }
}

@Composable
private fun ScanResultCard(
    result: FolderScanResult,
    selectedExecutable: ExecutableCandidate?,
    onSelectExecutable: (ExecutableCandidate) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Результат сканирования", fontWeight = FontWeight.Bold)
            Text("Папка: " + result.folderName)
            Text("Просмотрено файлов: " + result.visitedFiles)
            Text("Найдено EXE: " + result.executableCandidates.size)

            if (result.wasTruncated) {
                Text(
                    "Достигнут лимит сканирования. Часть папки не проверена; импорт отключён.",
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (result.hasBlockingIssues) {
                Text(
                    "Есть ошибки чтения или несовместимые имена/элементы. " +
                        "Импорт отключён, чтобы не получить неполную копию игры.",
                    color = MaterialTheme.colorScheme.error
                )
            }

            result.executableCandidates.forEach { candidate ->
                ExecutableRow(
                    candidate = candidate,
                    isSelected = selectedExecutable?.uri == candidate.uri,
                    onSelect = { onSelectExecutable(candidate) }
                )
            }

            result.warnings.forEach { warning ->
                Text(
                    text = warning,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (result.executableCandidates.isEmpty()) {
                Text("EXE-файлы не найдены в пределах лимитов сканирования.")
            }
        }
    }
}

@Composable
private fun ExecutableRow(
    candidate: ExecutableCandidate,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(candidate.relativePath, fontWeight = FontWeight.Medium)
        val architecture = candidate.peInspection?.architecture?.displayName
            ?: "Заголовок PE не распознан"
        Text(
            text = architecture,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        candidate.sizeBytes?.let { size ->
            Text(
                text = formatFileSize(size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        candidate.peInspection?.diagnostic?.let {
            Text(it, style = MaterialTheme.typography.labelSmall)
        }
        Button(
            onClick = onSelect,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isSelected) "Выбран" else "Выбрать этот EXE")
        }
        HorizontalDivider()
    }
}

@Composable
private fun LibraryCard(
    games: List<ImportedGame>,
    storageUsage: Map<String, GameStorageUsage>,
    canPrepareGame: Boolean,
    preparingGame: Boolean,
    deletingGameId: String?,
    canDeleteGame: (ImportedGame) -> Boolean,
    onPrepareGame: (ImportedGame) -> Unit,
    onDeleteGame: (ImportedGame) -> Unit,
    onChangeLocale: (ImportedGame, String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Библиотека", fontWeight = FontWeight.Bold)
            games.forEach { game ->
                var localeMenuExpanded by remember(game.id) { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(game.title, fontWeight = FontWeight.Medium)
                    Text(
                        text = game.executableRelativePath + " · " + game.architecture,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box {
                        TextButton(onClick = { localeMenuExpanded = true }) {
                            Text("Язык среды Wine: " + GameLocaleProfile.labelFor(game.locale))
                        }
                        DropdownMenu(
                            expanded = localeMenuExpanded,
                            onDismissRequest = { localeMenuExpanded = false }
                        ) {
                            GameLocaleProfile.options.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    enabled = option.locale != game.locale,
                                    onClick = {
                                        localeMenuExpanded = false
                                        onChangeLocale(game, option.locale)
                                    }
                                )
                            }
                        }
                    }
                    val usage = storageUsage[game.id] ?: GameStorageUsage(
                        gameFilesBytes = game.totalBytes
                    )
                    Text(
                        text = "Файлы игры: " + formatFileSize(usage.gameFilesBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Сохранения и настройки Wine: " +
                            formatFileSize(usage.wineDataBytes) +
                            " · временные файлы: " + formatFileSize(usage.temporaryBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Button(
                        onClick = { onPrepareGame(game) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = canPrepareGame
                    ) {
                        Text(if (preparingGame) "Подготовка…" else "Открыть игровой экран")
                    }
                    Button(
                        onClick = { onDeleteGame(game) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = canDeleteGame(game)
                    ) {
                        Text(
                            if (deletingGameId == game.id) "Удаляю данные…"
                            else "Удалить игру и сохранения"
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

private fun formatFileSize(sizeBytes: Long): String {
    return when {
        sizeBytes >= 1024L * 1024L * 1024L ->
            "%.2f GB".format(sizeBytes / (1024.0 * 1024.0 * 1024.0))
        sizeBytes >= 1024L * 1024L -> "%.1f MB".format(sizeBytes / (1024.0 * 1024.0))
        sizeBytes >= 1024L -> "%.1f KB".format(sizeBytes / 1024.0)
        else -> "$sizeBytes B"
    }
}

private fun releaseUnusedPersistedTreePermissions(context: android.content.Context) {
    context.contentResolver.persistedUriPermissions.forEach { permission ->
        var flags = 0
        if (permission.isReadPermission) {
            flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        if (permission.isWritePermission) {
            flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }
        if (flags != 0) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(permission.uri, flags)
            }
        }
    }
}
