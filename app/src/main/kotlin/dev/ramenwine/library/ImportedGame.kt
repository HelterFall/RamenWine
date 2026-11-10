package dev.ramenwine.library

data class ImportedGame(
    val id: String,
    val title: String,
    val relativeDirectory: String,
    val executableRelativePath: String,
    val architecture: String,
    val totalBytes: Long,
    val importedAtEpochMillis: Long,
    val locale: String = GameLocaleProfile.DEFAULT_LOCALE
)
