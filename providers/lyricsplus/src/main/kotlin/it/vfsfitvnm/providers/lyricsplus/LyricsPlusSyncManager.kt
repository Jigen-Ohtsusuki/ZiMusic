package it.vfsfitvnm.providers.lyricsplus

import it.vfsfitvnm.providers.lyricsplus.models.LyricLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LyricsPlusSyncManager(
    private val lyrics: List<LyricLine>,
    private val positionProvider: () -> Long
) {
    private val _currentLineIndex = MutableStateFlow(-1)
    val currentLineIndex: StateFlow<Int> = _currentLineIndex.asStateFlow()

    private val _currentWordIndex = MutableStateFlow(-1)

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    fun updatePosition() {
        val position = positionProvider()
        _currentPosition.value = position

        val lineIndex = lyrics.indexOfLast { position >= it.startTimeMs }
        _currentLineIndex.value = lineIndex

        if (lineIndex != -1) {
            val words = lyrics[lineIndex].words
            val wordIndex = words.indexOfLast { position >= it.startTimeMs }
            _currentWordIndex.value = wordIndex
        } else {
            _currentWordIndex.value = -1
        }
    }

    fun getLyrics(): List<LyricLine> = lyrics

    /**
     * Force immediate synchronization to current playback position
     * Call this when lyrics view opens mid-song to prevent sync issues
     */
    fun forceSync() {
        try {
            val currentPos = positionProvider()

            // Handle edge case where position is before first line
            if (currentPos < (lyrics.firstOrNull()?.startTimeMs ?: 0L)) {
                _currentLineIndex.value = -1
                _currentPosition.value = currentPos
                return
            }

            // Handle edge case where position is after last line
            val lastLine = lyrics.lastOrNull()
            if (lastLine != null && currentPos > (lastLine.startTimeMs + lastLine.durationMs)) {
                _currentLineIndex.value = lyrics.size - 1
                _currentPosition.value = currentPos
                return
            }

            // Find the correct line index for current position
            val correctLineIndex = lyrics.indexOfLast { line ->
                currentPos >= line.startTimeMs && currentPos <= (line.startTimeMs + line.durationMs)
            }

            // If no exact match found, find the closest previous line
            val fallbackIndex = if (correctLineIndex < 0) {
                lyrics.indexOfLast { line -> currentPos >= line.startTimeMs }
            } else correctLineIndex

            // Update the current line index and position immediately
            _currentLineIndex.value = fallbackIndex.coerceAtLeast(-1)
            _currentPosition.value = currentPos

            // Log for debugging (remove in production)
            // println("ForceSync: currentPos=$currentPos, foundIndex=$correctLineIndex, finalIndex=${_currentLineIndex.value}")

        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to regular sync if force sync fails
            updatePosition()
        }
    }

}
