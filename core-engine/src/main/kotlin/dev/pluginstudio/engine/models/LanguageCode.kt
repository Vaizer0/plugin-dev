package dev.pluginstudio.engine.models

enum class LanguageCode(val iso639_1: String) {
    EN("en"),
    RU("ru"),
    ZH("zh"),
    JA("ja"),
    ID("id"),
    FR("fr"),
    KO("ko"),
    MTL("mtl");

    companion object {
        fun fromIso639_1(code: String): LanguageCode? =
            entries.firstOrNull { it.iso639_1 == code }
    }
}
