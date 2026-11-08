package dev.ramenwine.library

import android.content.Context
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import dev.ramenwine.inspection.SafeGamePath

/**
 * Small app-private JSON store for the first game-library milestone.
 * It stores metadata only; imported game files remain in their own directories.
 */
class GameLibraryStore(context: Context) {
    private val gamesRoot = File(context.filesDir, "games")
    private val libraryFile = File(gamesRoot, LIBRARY_FILE_NAME)

    @Synchronized
    fun list(): List<ImportedGame> {
        if (!libraryFile.isFile) return emptyList()

        return runCatching {
            val root = JSONObject(libraryFile.readText(StandardCharsets.UTF_8))
            val items = root.optJSONArray("games") ?: JSONArray()
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val directory = item.optString("relativeDirectory")
                    val executable = item.optString("executableRelativePath")
                    val safeExecutable = SafeGamePath.normalizeRelativePath(executable)
                    if (
                        !id.matches(ID_PATTERN) ||
                        directory != "games/$id" ||
                        safeExecutable == null ||
                        !safeExecutable.endsWith(".exe", ignoreCase = true)
                    ) {
                        continue
                    }
                    add(
                        ImportedGame(
                            id = id,
                            title = item.optString("title", "Visual novel"),
                            relativeDirectory = directory,
                            executableRelativePath = executable,
                            architecture = item.optString("architecture", "Unknown"),
                            totalBytes = item.optLong("totalBytes", 0L).coerceAtLeast(0L),
                            importedAtEpochMillis = item.optLong("importedAtEpochMillis", 0L)
                                .coerceAtLeast(0L)
                        )
                    )
                }
            }.sortedByDescending { it.importedAtEpochMillis }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(game: ImportedGame) {
        require(game.id.matches(ID_PATTERN)) { "Invalid game ID." }
        require(game.relativeDirectory == "games/" + game.id) {
            "Invalid game directory."
        }
        val safeExecutable = SafeGamePath.normalizeRelativePath(game.executableRelativePath)
        require(
            safeExecutable != null &&
                safeExecutable.endsWith(".exe", ignoreCase = true)
        ) {
            "Invalid executable path."
        }
        require(game.title.length <= MAX_TITLE_LENGTH) {
            "Game title is too long."
        }

        if (!gamesRoot.exists() && !gamesRoot.mkdirs()) {
            error("Could not create the private games directory.")
        }

        val updatedGames = list().filterNot { it.id == game.id } + game
        val array = JSONArray()
        updatedGames.sortedByDescending { it.importedAtEpochMillis }.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("relativeDirectory", item.relativeDirectory)
                    .put("executableRelativePath", item.executableRelativePath)
                    .put("architecture", item.architecture)
                    .put("totalBytes", item.totalBytes)
                    .put("importedAtEpochMillis", item.importedAtEpochMillis)
            )
        }

        val temporaryFile = File(gamesRoot, "$LIBRARY_FILE_NAME.tmp")
        try {
            FileOutputStream(temporaryFile).use { stream ->
                stream.write(
                    JSONObject()
                        .put("schemaVersion", SCHEMA_VERSION)
                        .put("games", array)
                        .toString(2)
                        .toByteArray(StandardCharsets.UTF_8)
                )
                stream.flush()
                stream.fd.sync()
            }

            // Both files are in the same app-private directory, so POSIX rename
            // replaces the previous index atomically on Android/Linux.
            Os.rename(temporaryFile.absolutePath, libraryFile.absolutePath)
        } catch (error: Throwable) {
            temporaryFile.delete()
            throw error
        }
    }

    companion object {
        private const val LIBRARY_FILE_NAME = "library.json"
        private const val SCHEMA_VERSION = 1
        private const val MAX_TITLE_LENGTH = 512
        private val ID_PATTERN = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    }
}
