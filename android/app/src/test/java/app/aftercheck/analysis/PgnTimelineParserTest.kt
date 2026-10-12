package app.aftercheck.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PgnTimelineParserTest {
    @Test
    fun replaysStandardSanMovesAndCapturesFenBeforeAndAfter() {
        val pgn = """
            [Event "Parser fixture"]
            [White "White"]
            [Black "Black"]
            [Result "1-0"]

            1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 1-0
        """.trimIndent()

        val timeline = PgnTimelineParser.parse(pgn)
        assertEquals(6, timeline.size)
        assertEquals("e4", timeline.first().san)
        assertEquals("WHITE", timeline.first().sideToMove)
        assertTrue(timeline.first().fenBefore.contains(" w "))
        assertTrue(timeline.first().fenAfter.contains(" b "))
        assertEquals("a6", timeline.last().san)
    }

    @Test
    fun stripsCommentsAndParenthesizedVariations() {
        val pgn = """
            [Event "Parser fixture"]
            [White "White"]
            [Black "Black"]
            [Result "1-0"]

            1. e4 {mainline comment} e5 (1... c5 2. Nf3) 2. Nf3 Nc6 1-0
        """.trimIndent()

        val timeline = PgnTimelineParser.parse(pgn)
        assertEquals(listOf("e4", "e5", "Nf3", "Nc6"), timeline.map { it.san })
    }
}
