package dev.ramenwine.library

/**
 * Locale choices that are generated inside the bundled Debian guest rootfs.
 *
 * Keeping this list finite prevents a saved game profile from selecting a
 * locale that is not actually installed in the offline runtime package.
 */
object GameLocaleProfile {
    data class Option(
        val locale: String,
        val label: String
    )

    const val DEFAULT_LOCALE = "ja_JP.UTF-8"

    val options: List<Option> = listOf(
        Option(DEFAULT_LOCALE, "日本語 — Japanese"),
        Option("en_US.UTF-8", "English (United States)"),
        Option("ru_RU.UTF-8", "Русский"),
        Option("zh_CN.UTF-8", "简体中文"),
        Option("zh_TW.UTF-8", "繁體中文"),
        Option("ko_KR.UTF-8", "한국어"),
        Option("C.UTF-8", "Unicode (C.UTF-8)")
    )

    private val optionsByLocale = options.associateBy(Option::locale)

    fun isSupported(locale: String): Boolean = optionsByLocale.containsKey(locale)

    fun labelFor(locale: String): String =
        optionsByLocale[locale]?.label
            ?: optionsByLocale.getValue(DEFAULT_LOCALE).label
}
