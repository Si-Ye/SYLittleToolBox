package io.github.siye.littletoolbox.tools.textstats

import android.icu.lang.UCharacter
import android.icu.lang.UCharacterCategory
import android.icu.lang.UScript
import android.icu.text.BreakIterator
import android.icu.util.ULocale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class TextStatistics(
    val words: Int = 0,
    val characters: Int = 0,
    val hanCharacters: Int = 0,
    val englishWords: Int = 0,
    val charactersWithoutLineBreaks: Int = 0,
    val charactersWithoutWhitespace: Int = 0,
    val lines: Long = 1,
    val nonBlankLines: Int = 0,
    val utf8Bytes: Long = 0,
    val utf16Bytes: Long = 0,
)

internal suspend fun calculateTextStatistics(text: String): TextStatistics {
    val context = currentCoroutineContext()
    context.ensureActive()

    val breaks = BreakIterator.getCharacterInstance(ULocale.ROOT)
    breaks.setText(text)

    var words = 0
    var characters = 0
    var hanCharacters = 0
    var englishWords = 0
    var lineBreaks = 0
    var whitespaceCharacters = 0
    var nonBlankLines = 0
    var utf8Bytes = 0L
    var lineHasText = false
    var englishWordOpen = false
    var joinerPending = false
    var unitsUntilCancellationCheck = 4096

    var start = breaks.first()
    var end = breaks.next()
    while (end != BreakIterator.DONE) {
        var hasLetterOrNumber = false
        var hasKeycap = false
        var whitespaceOnly = true
        var offset = start
        while (offset < end) {
            val codePoint = text.codePointAt(offset)
            val units = Character.charCount(codePoint)
            val type = UCharacter.getType(codePoint)
            val letter = UCharacter.isLetter(codePoint)
            val letterOrNumber = letter || when (type) {
                UCharacterCategory.DECIMAL_DIGIT_NUMBER.toInt(),
                UCharacterCategory.LETTER_NUMBER.toInt(),
                UCharacterCategory.OTHER_NUMBER.toInt() -> true
                else -> false
            }
            val script = if (letterOrNumber) UScript.getScript(codePoint) else UScript.INVALID_CODE
            hasLetterOrNumber = hasLetterOrNumber || letterOrNumber
            hasKeycap = hasKeycap || (codePoint == 0x20E3 && text[start] in '0'..'9')
            whitespaceOnly = whitespaceOnly && UCharacter.isUWhiteSpace(codePoint)
            if (script == UScript.HAN) hanCharacters++

            val combiningMark = when (type) {
                UCharacterCategory.NON_SPACING_MARK.toInt(),
                UCharacterCategory.COMBINING_SPACING_MARK.toInt(),
                UCharacterCategory.ENCLOSING_MARK.toInt() -> true
                else -> false
            }
            when {
                letter && script == UScript.LATIN -> {
                    if (!englishWordOpen) englishWords++
                    englishWordOpen = true
                    joinerPending = false
                }
                englishWordOpen && !joinerPending && combiningMark -> Unit
                englishWordOpen && !joinerPending && isEnglishJoiner(codePoint) -> joinerPending = true
                else -> {
                    englishWordOpen = false
                    joinerPending = false
                }
            }

            utf8Bytes += utf8Width(codePoint)
            offset += units
            unitsUntilCancellationCheck -= units
            if (unitsUntilCancellationCheck <= 0) {
                context.ensureActive()
                unitsUntilCancellationCheck = 4096
            }
        }

        characters++
        if (hasLetterOrNumber && !hasKeycap) words++
        if (whitespaceOnly) whitespaceCharacters++
        if (isLineBreak(text[start])) {
            // ICU groups CRLF into a single grapheme cluster.
            lineBreaks++
            if (lineHasText) nonBlankLines++
            lineHasText = false
        } else if (!whitespaceOnly) {
            lineHasText = true
        }
        start = end
        end = breaks.next()
    }
    if (lineHasText) nonBlankLines++

    return TextStatistics(
        words = words,
        characters = characters,
        hanCharacters = hanCharacters,
        englishWords = englishWords,
        charactersWithoutLineBreaks = characters - lineBreaks,
        charactersWithoutWhitespace = characters - whitespaceCharacters,
        lines = lineBreaks.toLong() + 1,
        nonBlankLines = nonBlankLines,
        utf8Bytes = utf8Bytes,
        utf16Bytes = text.length.toLong() * 2,
    )
}

private fun isLineBreak(character: Char): Boolean =
    character == '\r' || character == '\n' || character == '\u0085' ||
        character == '\u2028' || character == '\u2029'

private fun isEnglishJoiner(codePoint: Int): Boolean =
    codePoint == '\''.code || codePoint == 0x2019 || codePoint == '-'.code ||
        codePoint == 0x2010 || codePoint == 0x2011

private fun utf8Width(codePoint: Int): Int = when (codePoint) {
    in 0..0x7F -> 1
    in 0x80..0x7FF -> 2
    // The string encoder uses a single-byte replacement for an unpaired surrogate.
    in 0xD800..0xDFFF -> 1
    in 0x800..0xFFFF -> 3
    else -> 4
}
