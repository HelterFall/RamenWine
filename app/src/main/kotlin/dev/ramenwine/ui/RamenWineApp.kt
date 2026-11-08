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
import dev.ramenwine.inspection.ExecutableCandidate
import dev.ramenwine.inspection.FolderScanResult
import dev.ramenwine.inspection.GameFolderScanner
import dev.ramenwine.runtime.NativeRuntime
import dev.ramenwine.runtime.RuntimeLayout
import dev.ramenwine.runtime.RuntimeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RamenWineApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedFolder by remember { mutableStateOf<Uri?>(null) }
    var scanResult by remember { mutableStateOf<FolderScanResult?>(null) }
    var selectedExecutable by remember { mutableStateOf<ExecutableCandidate?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }

    val runtimeInspection = remember(context.filesDir) {
        RuntimeLayout.inspect(context.filesDir)
    }
    val nativeDiagnostic = remember { NativeRuntime.diagnostic() }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            selectedFolder = uri
            scanResult = null
            selectedExecutable = null
            scanError = null

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
                        text = "Development build · Phase 1: Android host and safe EXE inspection",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

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
                            RuntimeState.NOT_INSTALLED -> Text(
                                "Linux/Wine runtime is not installed yet."
                            )
                            RuntimeState.INCOMPLETE -> {
                                Text("Runtime directory exists but is incomplete.")
                                runtimeInspection.missingItems.forEach { Text("• " + it) }
                            }
                            RuntimeState.FILES_PRESENT -> Text(
                                "Expected files are present. A real launch self-test is still required."
                            )
                        }
                        Text(
                            text = "Expected location: " +
                                runtimeInspection.runtimeRoot.relativeTo(context.filesDir),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider()
                        Text("Native bridge", fontWeight = FontWeight.Bold)
                        Text(
                            text = nativeDiagnostic,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Button(
                    onClick = { folderPicker.launch(null) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !scanning
                ) {
                    Text(if (scanning) "Сканирование…" else "Выбрать папку игры")
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
                        onSelectExecutable = { selectedExecutable = it }
                    )
                }

                selectedExecutable?.let { candidate ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Главный EXE выбран", fontWeight = FontWeight.Bold)
                            Text(candidate.displayName)
                            Text(
                                text = "Архитектура: " +
                                    (candidate.peInspection?.architecture?.displayName
                                        ?: "не определена"),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "Следующий этап — подключение Wine runtime и реального запуска. " +
                                    "Сейчас файл только выбран, он не исполнялся.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                Text(
                    text = "Сканер только читает ограниченные заголовки EXE. " +
                        "Он не запускает игровые файлы и не устанавливает Wine.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
        Text(candidate.displayName, fontWeight = FontWeight.Medium)
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
            Text(if (isSelected) "Выбран для запуска" else "Выбрать этот EXE")
        }
        HorizontalDivider()
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
