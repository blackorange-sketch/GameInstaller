package dev.gameinstaller

/** JNI-міст. Сюди підключатимуться innoextract і unarc (див. third_party/README.md). */
object NativeBridge {
    init { System.loadLibrary("gameinstaller") }
    external fun version(): String
}
