package dev.ramenwine.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ramenwine.display.DisplaySession
import dev.ramenwine.display.WawonaDisplayBackend
import dev.ramenwine.display.WawonaSurfaceHost
import dev.ramenwine.display.WawonaSurfaceView
import dev.ramenwine.importer.GameFolderImporter
import dev.ramenwine.importer.ImportProgress
import dev.ramenwine.inspection.ExecutableCandidate
import dev.ramenwine.inspection.FolderScanResult
import dev.ramenwine.inspection.GameFolderScanner
import dev.ramenwine.inspection.PeArchitecture
import dev.ramenwine.library.GameLibraryStore
import dev.ramenwine.library.ImportedGame
import dev.ramenwine.runtime.GameSession
import dev.ramenwine.runtime.GameSessionCoordinator
import dev.ramenwine.runtime.RuntimeBackendId
import dev.ramenwine.runtime.RuntimeLayout
import dev.ramenwine.runtime.RuntimePackageInstaller
import dev.ramenwine.runtime.RuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun RamenWineApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val libraryStore = remember(context.filesDir) {
        GameLibraryStore(context.applicationContext)
    }

    var importedGames by remember { mutableStateOf(libraryStore.list()) }
    var selectedFolder by remember { mutableStateOf<Uri?>(null) }
    var scanResult by remember { mutableStateOf<FolderScanResult?>(null) }
    var selectedExecutable by remember { mutableStateOf<ExecutableCandidate?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf(ImportProgress(0, 0L)) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

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
    var activeSurfaceView by remember { mutableStateOf<WawonaSurfaceView?>(null) }
    var surfaceFrameVerified by remember { mutableStateOf(false) }
    var verifyingSurfaceFrame by remember { mutableStateOf(false) }
    var surfaceFrameResult by remember { mutableStateOf<String?>(null) }
    var preparingGame by remember { mutableStateOf(false) }
    var gameLaunchError by remember { mutableStateOf<String?>(null) }
    var gameLaunchNotice by remember { mutableStateOf<String?>(null) }

    suspend fun refreshLibrary() {
        importedGames = withContext(Dispatchers.IO) { libraryStore.list() }
    }

    LaunchedEffect(context.filesDir) {
        try {
            withContext(Dispatchers.IO) {
                val storageManager = dev.ramenwine.library.GameStorageManager(
                    context.filesDir,
                    context.cacheDir,
                    libraryStore::remove
                )
                releaseUnusedPersistedTreePermissions(context)
                storageManager.cleanupAbandonedData(
                    registeredGameIds = libraryStore.list().map { it.id }.toSet(),
                    canPruneUnlistedData = libraryStore.hasTrustworthyIndex()
                )
            }
        } catch (_: Exception) {
            // Library entries remain available even if optional cleanup fails.
        }

        val installedBackend = RuntimeLayout.inspect(context.filesDir)
            .backends
            .firstOrNull { it.backend == RuntimeBackendId.GLIBC_PROOT_BOX64 }

        if (installedBackend?.state != RuntimeState.FILES_PRESENT) {
            runCatching {
                withContext(Dispatchers.IO) {
                    RuntimePackageInstaller(context.applicationContext).installBundled()
                }
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

    fun cancelAddFlow() {
        if (importing) return
        selectedFolder?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        selectedFolder = null
        scanResult = null
        selectedExecutable = null
        scanError = null
        importError = null
        scanning = false
    }

    fun startImport() {
        val folderUri = selectedFolder ?: return
        val candidate = selectedExecutable ?: return
        val result = scanResult ?: return
        val architecture = candidate.peInspection?.architecture
        val supported = candidate.peInspection?.isValidPe == true &&
            (architecture == PeArchitecture.X86 || architecture == PeArchitecture.X86_64)
        if (!result.canImport || !supported || importing || scanning) return

        importing = true
        importProgress = ImportProgress(0, 0L)
        importError = null

        scope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    GameFolderImporter(context.applicationContext).importGame(
                        treeUri = folderUri,
                        gameTitle = result.folderName.ifBlank { "Visual novel" },
                        selectedExecutableRelativePath = candidate.relativePath,
                        selectedExecutableUri = candidate.uri,
                        onProgress = { progress ->
                            scope.launch { importProgress = progress }
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
                scanResult = null
                selectedExecutable = null
                refreshLibrary()

                // Show the new game immediately; discover its cover in the background.
                scope.launch {
                    val coverUrl = withContext(Dispatchers.IO) {
                        runCatching { GameCoverSearch.findCover(imported.title) }.getOrNull()
                    }
                    if (!coverUrl.isNullOrBlank()) {
                        withContext(Dispatchers.IO) {
                            libraryStore.add(imported.copy(coverImageUrl = coverUrl))
                        }
                        refreshLibrary()
                    }
                }
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
        if (preparingGame || activeDisplaySession != null || activeGameSession?.isRunning == true) {
            return
        }
        gameLaunchError = null
        gameLaunchNotice = "Открываю игровой экран…"
        surfaceFrameVerified = false
        surfaceFrameResult = null
        preparingGame = true
        scope.launch {
            try {
                activeDisplaySession = gameSessionCoordinator.openDisplay()
                selectedDisplayGame = game
                surfaceReady = false
                activeSurfaceView = null
                surfaceFrameVerified = false
                gameLaunchNotice = "Подключаю игровой экран…"
            } catch (error: Exception) {
                gameLaunchError = error.message ?: error.javaClass.simpleName
                gameLaunchNotice = null
            } finally {
                preparingGame = false
            }
        }
    }

    fun verifyWaylandSurfaceOutput() {
        val view = activeSurfaceView
        if (view == null || !surfaceReady || verifyingSurfaceFrame || preparingGame) return

        verifyingSurfaceFrame = true
        surfaceFrameVerified = false
        surfaceFrameResult = "Проверяю вывод изображения…"
        scope.launch {
            try {
                val result = view.verifyWaylandShmOutput()
                surfaceFrameVerified = result.visible
                surfaceFrameResult = if (result.visible) "Экран готов" else result.detail
            } catch (error: Exception) {
                surfaceFrameVerified = false
                surfaceFrameResult = error.message ?: error.javaClass.simpleName
            } finally {
                verifyingSurfaceFrame = false
            }
        }
    }

    fun startSelectedSession() {
        val game = selectedDisplayGame ?: return
        if (!surfaceReady || !surfaceFrameVerified || preparingGame || verifyingSurfaceFrame) return

        preparingGame = true
        gameLaunchError = null
        gameLaunchNotice = "Запускаю игру…"
        scope.launch {
            try {
                val launched = gameSessionCoordinator.launchGame(
                    game = game,
                    launchDiagnosticWindow = false,
                    onOutput = { },
                    onExit = { exitCode ->
                        activeGameSession = null
                        activeDisplaySession = null
                        selectedDisplayGame = null
                        surfaceReady = false
                        surfaceFrameVerified = false
                        activeSurfaceView = null
                        preparingGame = false
                        gameLaunchNotice = "Игра завершена (код $exitCode)"
                    }
                )
                if (launched.isRunning) {
                    activeGameSession = launched
                    gameLaunchNotice = null
                } else {
                    activeGameSession = null
                    activeDisplaySession = null
                    selectedDisplayGame = null
                    surfaceReady = false
                    surfaceFrameVerified = false
                    activeSurfaceView = null
                    gameLaunchNotice = "Процесс завершился до подтверждения запуска."
                }
            } catch (error: Exception) {
                gameLaunchError = error.message ?: error.javaClass.simpleName
                runCatching { gameSessionCoordinator.closeDisplay() }
                activeGameSession = null
                activeDisplaySession = null
                selectedDisplayGame = null
                surfaceReady = false
                surfaceFrameVerified = false
                activeSurfaceView = null
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
                if (running == null || !running.isRunning) {
                    activeGameSession = null
                    activeDisplaySession = null
                    selectedDisplayGame = null
                    surfaceReady = false
                    surfaceFrameVerified = false
                    activeSurfaceView = null
                }
                preparingGame = false
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        val currentDisplay = activeDisplaySession
        val currentGame = selectedDisplayGame

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (currentDisplay != null && currentGame != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    WawonaSurfaceHost(
                        session = currentDisplay,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        onSurfaceStateChanged = { attached ->
                            surfaceReady = attached
                            if (!attached) {
                                surfaceFrameVerified = false
                                surfaceFrameResult = null
                            }
                        },
                        onSurfaceViewChanged = { view ->
                            activeSurfaceView = view
                            if (view == null) surfaceFrameVerified = false
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = ::stopSelectedGame,
                            enabled = !preparingGame
                        ) {
                            Text("Назад")
                        }
                        if (activeGameSession == null) {
                            Button(
                                onClick = ::verifyWaylandSurfaceOutput,
                                enabled = surfaceReady && !verifyingSurfaceFrame && !preparingGame
                            ) {
                                Text(
                                    when {
                                        verifyingSurfaceFrame -> "Проверяю…"
                                        surfaceFrameVerified -> "Экран готов"
                                        else -> "Проверить экран"
                                    }
                                )
                            }
                            Button(
                                onClick = ::startSelectedSession,
                                enabled = surfaceReady && surfaceFrameVerified &&
                                    !preparingGame && !verifyingSurfaceFrame
                            ) {
                                Text(if (preparingGame) "Запуск…" else "Запустить")
                            }
                        } else {
                            Button(
                                onClick = ::stopSelectedGame,
                                enabled = !preparingGame
                            ) {
                                Text("Закрыть игру")
                            }
                        }
                    }
                    gameLaunchNotice?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                if (importedGames.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 84.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Добавь игру через +",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            top = 18.dp,
                            end = 16.dp,
                            bottom = 104.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(importedGames, key = { it.id }) { game ->
                            GameIcon(
                                game = game,
                                enabled = !preparingGame,
                                onClick = { prepareGame(game) }
                            )
                        }
                    }
                }

                FloatingActionButton(
                    onClick = { if (!scanning && !importing && !preparingGame) folderPicker.launch(null) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(22.dp),
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Text("+", style = MaterialTheme.typography.headlineMedium)
                }

                if (preparingGame) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            if (selectedFolder != null) {
                AddGameDialog(
                    scanning = scanning,
                    importing = importing,
                    result = scanResult,
                    selectedExecutable = selectedExecutable,
                    progress = importProgress,
                    scanError = scanError,
                    importError = importError,
                    onSelectExecutable = { selectedExecutable = it },
                    onImport = ::startImport,
                    onDismiss = ::cancelAddFlow
                )
            }

            gameLaunchError?.let { message ->
                AlertDialog(
                    onDismissRequest = { gameLaunchError = null },
                    title = { Text("Не удалось открыть игру") },
                    text = { Text(message) },
                    confirmButton = {
                        TextButton(onClick = { gameLaunchError = null }) {
                            Text("Закрыть")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun GameIcon(
    game: ImportedGame,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.70f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            GameCoverImage(
                imageUrl = game.coverImageUrl,
                fallbackTitle = game.title
            )
        }
        Text(
            text = game.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GameCoverImage(
    imageUrl: String?,
    fallbackTitle: String
) {
    var bitmap by remember(imageUrl) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(imageUrl) {
        bitmap = if (imageUrl.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val connection = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 7_000
                        readTimeout = 7_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "RamenWine/0.1.0")
                    }
                    try {
                        if (connection.responseCode !in 200..299) {
                            null
                        } else {
                            connection.inputStream.use { stream ->
                                BitmapFactory.decodeStream(stream)?.asImageBitmap()
                            }
                        }
                    } finally {
                        connection.disconnect()
                    }
                }.getOrNull()
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = fallbackTitle,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    } else {
        Text(
            text = fallbackTitle.take(2).uppercase(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AddGameDialog(
    scanning: Boolean,
    importing: Boolean,
    result: FolderScanResult?,
    selectedExecutable: ExecutableCandidate?,
    progress: ImportProgress,
    scanError: String?,
    importError: String?,
    onSelectExecutable: (ExecutableCandidate) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit
) {
    val selectedArchitecture = selectedExecutable?.peInspection?.architecture
    val selectedIsSupported = selectedExecutable?.peInspection?.isValidPe == true &&
        (selectedArchitecture == PeArchitecture.X86 ||
            selectedArchitecture == PeArchitecture.X86_64)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (importing) "Добавление игры" else "Добавить игру") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when {
                    scanning -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Сканирую папку игры…")
                    }
                    scanError != null -> {
                        Text(
                            text = scanError,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    result != null -> {
                        Text("Выбери EXE-файл игры")
                        if (result.wasTruncated || result.hasBlockingIssues || !result.canImport) {
                            Text(
                                "Папку нельзя импортировать целиком: обнаружены ограничения или ошибки сканирования.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (result.executableCandidates.isEmpty()) {
                            Text("EXE-файлы не найдены.")
                        }
                        result.executableCandidates.forEach { candidate ->
                            val isSelected = selectedExecutable?.uri == candidate.uri
                            val architecture = candidate.peInspection?.architecture
                                ?.displayName ?: "Архитектура не определена"
                            Card(
                                onClick = { onSelectExecutable(candidate) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(
                                        text = candidate.relativePath,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = architecture,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }

                if (importing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "Файлов: " + progress.copiedFiles +
                            " · Скопировано: " + formatFileSize(progress.copiedBytes),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                importError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                if (selectedExecutable != null && !selectedIsSupported) {
                    Text(
                        "Поддерживаются проверенные Windows x86 и x86-64 EXE.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onImport,
                enabled = !scanning && !importing && result?.canImport == true &&
                    selectedExecutable != null && selectedIsSupported
            ) {
                Text(if (importing) "Добавляю…" else "Добавить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !importing) {
                Text("Отмена")
            }
        }
    )
}

private object GameCoverSearch {
    fun findCover(gameTitle: String): String? {
        val title = gameTitle.trim()
        if (title.isEmpty()) return null

        val queries = listOf("$title video game", title)
        for (query in queries) {
            val image = searchWikipedia(query, title)
            if (!image.isNullOrBlank()) return image
        }
        return null
    }

    private fun searchWikipedia(query: String, originalTitle: String): String? {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val requestUrl = URL(
            "https://en.wikipedia.org/w/api.php?action=query&generator=search" +
                "&gsrsearch=$encoded&gsrnamespace=0&gsrlimit=8" +
                "&prop=pageimages&piprop=thumbnail&pithumbsize=640&format=json"
        )
        val connection = requestUrl.openConnection() as HttpURLConnection
        connection.connectTimeout = 7_000
        connection.readTimeout = 7_000
        connection.setRequestProperty("User-Agent", "RamenWine/0.1.0")

        return try {
            if (connection.responseCode !in 200..299) return null
            val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                org.json.JSONObject(it.readText())
            }
            val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return null
            val normalizedTitle = normalizeTitle(originalTitle)
            val pageIds = pages.keys()
            val candidates = buildList {
                while (pageIds.hasNext()) {
                    val page = pages.optJSONObject(pageIds.next()) ?: continue
                    val pageTitle = normalizeTitle(page.optString("title"))
                    val imageUrl = page.optJSONObject("thumbnail")?.optString("source")
                    if (
                        imageUrl.isNullOrBlank() ||
                        !imageUrl.startsWith("https://upload.wikimedia.org/") ||
                        pageTitle.isBlank()
                    ) {
                        continue
                    }
                    val score = when {
                        pageTitle == normalizedTitle -> 0
                        pageTitle.startsWith(normalizedTitle) ||
                            normalizedTitle.startsWith(pageTitle) -> 1
                        titleTokenSimilarity(normalizedTitle, pageTitle) >= 0.7 -> 2
                        else -> 99
                    }
                    if (score < 99) add(score to imageUrl)
                }
            }
            candidates.minByOrNull { it.first }?.second
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeTitle(value: String): String =
        value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun titleTokenSimilarity(left: String, right: String): Double {
        val leftTokens = left.split(" ").filter(String::isNotBlank).toSet()
        val rightTokens = right.split(" ").filter(String::isNotBlank).toSet()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
        return leftTokens.intersect(rightTokens).size.toDouble() /
            leftTokens.size.coerceAtLeast(rightTokens.size).toDouble()
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
