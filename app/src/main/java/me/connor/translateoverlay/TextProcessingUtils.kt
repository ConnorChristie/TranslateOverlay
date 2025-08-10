package me.connor.translateoverlay

object TextProcessingUtils {
    // ASCII sentence punctuation
    private val ASCII_PUNCT_AFTER = Regex("([\\.!?;:,])(?!\\s)")
    // Common CJK punctuation (includes comma-like separators)
    private val CJK_PUNCT_AFTER = Regex("([。！？；：、，])(?!\\s)")
    // Collapse multiple spaces/tabs/newlines to a single space
    private val MULTI_WS = Regex("\\s+")
    // Sentence boundary split (keep punctuation with the sentence)
    private val SENTENCE_SPLIT = Regex("""(?<=[\\.\\!\\?。！？])\\s+""")
    // Any sentence-terminating punctuation
    private val SENTENCE_END_CHAR = Regex("[\\.\\!\\?。！？]")

    fun normalizeAndSpace(input: String): String {
        if (input.isBlank()) return input
        var out = input
        out = out.replace(ASCII_PUNCT_AFTER) { it.groupValues[1] + " " }
        out = out.replace(CJK_PUNCT_AFTER) { it.groupValues[1] + " " }
        out = out.replace(MULTI_WS, " ").trim()
        return out
    }

    /** Returns Pair(completedSentences, remainder) from the buffer. */
    fun splitCompletedSentences(buffer: String): Pair<List<String>, String> {
        if (buffer.isBlank()) return emptyList<String>() to ""
        // Find last terminal punctuation
        val lastEnd = SENTENCE_END_CHAR.findAll(buffer).lastOrNull() ?: return emptyList<String>() to buffer
        val boundary = lastEnd.range.last + 1
        val ready = buffer.substring(0, boundary)
        val remainder = buffer.substring(boundary)
        val sentences = ready.split(SENTENCE_SPLIT)
            .map { normalizeAndSpace(it.trim()) }
            .filter { it.isNotEmpty() }
        return sentences to remainder
    }
}


