package dev.ramenwine.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ramenwine.BuildConfig
import dev.ramenwine.GamePlayerActivity
import dev.ramenwine.importer.GameFolderImporter
import dev.ramenwine.importer.ImportProgress
import dev.ramenwine.inspection.ExecutableCandidate
import dev.ramenwine.inspection.FolderScanResult
import dev.ramenwine.inspection.GameFolderScanner
import dev.ramenwine.inspection.MainExecutableSelector
import dev.ramenwine.library.GameLibraryStore
import dev.ramenwine.library.ImportedGame
import dev.ramenwine.runtime.NativeRuntime
import dev.ramenwine.runtime.RuntimeBackendId
import dev.ramenwine.runtime.RuntimeLayout
import dev.ramenwine.runtime.RuntimePackageInstaller
import dev.ramenwine.runtime.RuntimeState
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

    var selectedFolder by remember { mutableStateOf<Uri?>(null) }
    var scanResult by remember { mutableStateOf<FolderScanResult?>(null) }
    var selectedExecutable by remember { mutableStateOf<ExecutableCandidate?>(null) }
    var automaticExecutablePath by remember { mutableStateOf<String?>(null) }
    var importedGames by remember { mutableStateOf(libraryStore.list()) }
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

    val runtimePackagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && !installingRuntime) {
            installingRuntime = true
            runtimePackageError = null
            runtimePackageNotice = null
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        RuntimePackageInstaller(context.applicationContext).install(uri)
                    }
                    runtimeInspection = RuntimeLayout.inspect(context.filesDir)
                    runtimePackageNotice = result.backend.displayName +
                        " · " + result.runtimeVersion +
                        " · " + result.installedFileCount + " файлов"
                } catch (error: Exception) {
                    runtimePackageError = error.message ?: error.javaClass.simpleName
                    runtimeInspection = RuntimeLayout.inspect(context.filesDir)
                } finally {
                    installingRuntime = false
                }
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            selectedFolder = uri
            scanResult = null
            selectedExecutable = null
            automaticExecutablePath = null
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
                    val result = withContext(Dispatchers.IO) {
                        GameFolderScanner(context.applicationContext).scan(uri)
                    }
                    scanResult = result
                    val suggestedPath = MainExecutableSelector.select(
                        candidates = result.executableCandidates.mapNotNull { candidate ->
                            val inspection = candidate.peInspection ?: return@mapNotNull null
                            MainExecutableSelector.Hint(
                                relativePath = candidate.relativePath,
                                architecture = inspection.architecture,
                                isValidPe = inspection.isValidPe
                            )
                        },
                        folderName = result.folderName
                    )
                    automaticExecutablePath = suggestedPath
                    selectedExecutable = result.executableCandidates.firstOrNull {
                        it.relativePath == suggestedPath
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
                        onProgress = { progress ->
                            scope.launch {
                                importProgress = progress
                            }
                        }
                    )
                }
                importedGames = libraryStore.list()
                importNotice = "Игра импортирована: " + imported.title
            } catch (error: Exception) {
                importError = error.message ?: error.javaClass.simpleName
            } finally {
                importing = false
            }
        }
    }

    val prootRuntimeReady = runtimeInspection.backends.any {
        it.backend == RuntimeBackendId.GLIBC_PROOT_BOX64 &&
            it.state == RuntimeState.FILES_PRESENT
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
                    nativeDiagnostic = nativeDiagnostic,
                    installingRuntime = installingRuntime,
                    showDeveloperTools = BuildConfig.DEBUG,
                    onInstallRuntime = {
                        runtimePackagePicker.launch(
                            arrayOf(
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/octet-stream"
                            )
                        )
                    }
                )

                if (BuildConfig.DEBUG) {
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent().setClassName(
                                    context.packageName,
                                    "dev.ramenwine.debug.WawonaPreviewActivity"
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Тест графики Wawona (для разработчиков)")
                    }
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
                            automaticExecutablePath = null
                            importError = null
                            importNotice = null
                        }
                    )
                }

                selectedExecutable?.let { candidate ->
                    SelectedExecutableCard(
                        candidate = candidate,
                        automaticallySelected = automaticExecutablePath == candidate.relativePath,
                        importing = importing,
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
                        launchEnabled = prootRuntimeReady,
                        onLaunch = { selectedGame ->
                            context.startActivity(
                                Intent(context, GamePlayerActivity::class.java).apply {
                                    putExtra(GamePlayerActivity.EXTRA_GAME_ID, selectedGame.id)
                                }
                            )
                        }
                    )
                }

                Text(
                    text = "Папка игры сохраняется в библиотеке приложения. Для запуска требуется установленный игровой движок; в игровом режиме отображается только окно новеллы.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RuntimeStatusCard(
    runtimeInspection: dev.ramenwine.runtime.RuntimeInspection,
    nativeDiagnostic: String,
    installingRuntime: Boolean,
    showDeveloperTools: Boolean,
    onInstallRuntime: () -> Unit
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
            if (showDeveloperTools) {
                Text(
                    "Служебная функция для разработчиков. Готовый Wine/Proton runtime пока не входит в APK. " +
                        "Обычному пользователю не нужно искать или скачивать ZIP вручную.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onInstallRuntime,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !installingRuntime
                ) {
                    Text(
                        if (installingRuntime) "Установка…" else
                            "Импортировать runtime из ZIP (для разработчиков)"
                    )
                }
                HorizontalDivider()
                Text("Native bridge · диагностика", fontWeight = FontWeight.Bold)
                Text(text = nativeDiagnostic, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SelectedExecutableCard(
    candidate: ExecutableCandidate,
    automaticallySelected: Boolean,
    importing: Boolean,
    onImport: () -> Unit
) {
    val isValidPe = candidate.peInspection?.isValidPe == true

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
            Text(
                if (automaticallySelected) "Главный файл игры найден автоматически"
                else "Главный EXE выбран",
                fontWeight = FontWeight.Bold
            )
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
            }

            Button(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth(),
                enabled = isValidPe && !importing
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
            Text(
                "Проверила папку и нашла исполняемые файлы. Если главный файл определён уверенно, он уже выбран.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("Папка: " + result.folderName)
            Text("Просмотрено файлов: " + result.visitedFiles)
            Text("Найдено EXE: " + result.executableCandidates.size)

            if (result.wasTruncated) {
                Text(
                    "Достигнут лимит сканирования. Часть папки не проверена.",
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            result.executableCandidates.take(MAX_VISIBLE_CANDIDATES).forEach { candidate ->
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
    launchEnabled: Boolean,
    onLaunch: (ImportedGame) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Библиотека", fontWeight = FontWeight.Bold)
            games.forEach { game ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(game.title, fontWeight = FontWeight.Medium)
                    Text(
                        text = game.executableRelativePath + " · " + game.architecture,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatFileSize(game.totalBytes) + " · добавлена в библиотеку",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Button(
                        onClick = { onLaunch(game) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = launchEnabled
                    ) {
                        Text("Играть")
                    }
                    if (!launchEnabled) {
                        Text(
                            "Игровой движок ещё не установлен.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
        sizeBytes >= 1024L * 1024L -> "%.1f MB".format(sizeBytes / (1024.0 * 1024.0))
        sizeBytes >= 1024L -> "%.1f KB".format(sizeBytes / 1024.0)
        else -> "$sizeBytes B"
    }
}

private const val MAX_VISIBLE_CANDIDATES = 100
