package dev.ramenwine.library

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

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
                    if (id.isBlank() || directory != "games/$id" || executable.isBlank()) {
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
        require(game.executableRelativePath.isNotBlank()) {
            "Missing executable path."
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

            if (libraryFile.exists() && !libraryFile.delete()) {
                error("Could not replace the game library index.")
            }
            if (!temporaryFile.renameTo(libraryFile)) {
                error("Could not commit the game library index.")
            }
        } catch (error: Throwable) {
            temporaryFile.delete()
            throw error
        }
    }

    companion object {
        private const val LIBRARY_FILE_NAME = "library.json"
        private const val SCHEMA_VERSION = 1
        private val ID_PATTERN = Regex("[a-f0-9-]{36}")
    }
}
