package com.google.ai.edge.gallery.tts

object TextChunkSentinel {
    /**
     * Extracts completed sentence chunks from the accumulator.
     * Detects boundaries at: ". ", "? ", "! ", and "," (when chunk > 80 chars).
     * Removes extracted text from the front of the accumulator.
     */
    fun extractCompletedChunks(accumulator: StringBuilder): List<String> {
        val chunks = mutableListOf<String>()
        val text = accumulator.toString()
        var lastExtractEnd = 0

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val isEndOfText = i == text.length - 1
            val nextIsSpace = i + 1 < text.length && text[i + 1] == ' '

            val isBoundary = when {
                // Period at end of string or followed by space
                ch == '.' && (isEndOfText || nextIsSpace) -> true
                // Question mark followed by space
                ch == '?' && nextIsSpace -> true
                // Exclamation followed by space
                ch == '!' && nextIsSpace -> true
                // Comma when chunk is long (>80 chars)
                ch == ',' && (i - lastExtractEnd) > 80 -> true
                else -> false
            }

            if (isBoundary) {
                val endIndex = if (ch == '.' && isEndOfText) i + 1
                    else if (nextIsSpace) i + 2  // Include the space after punctuation
                    else i + 1
                val chunk = text.substring(lastExtractEnd, endIndex).trim()
                if (chunk.isNotBlank()) {
                    chunks.add(chunk)
                }
                lastExtractEnd = endIndex
            }
            i++
        }

        // Remove extracted text from the accumulator
        if (lastExtractEnd > 0) {
            accumulator.delete(0, lastExtractEnd)
        }

        return chunks
    }
}
