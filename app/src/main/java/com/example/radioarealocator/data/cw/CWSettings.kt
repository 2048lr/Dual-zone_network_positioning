package com.example.radioarealocator.data.cw

import androidx.compose.runtime.Immutable

enum class CharacterSet {
    LETTERS, NUMBERS, SYMBOLS, CUSTOM
}

enum class PlayMode {
    CONTINUOUS, INTERVAL
}

@Immutable
data class CWSettings(
    val wpm: Int = 15,
    val frequency: Int = 600,
    val characterSet: CharacterSet = CharacterSet.LETTERS,
    val practiceLength: Int = 100,
    val practiceDuration: Int = 5,
    val playMode: PlayMode = PlayMode.CONTINUOUS
)
