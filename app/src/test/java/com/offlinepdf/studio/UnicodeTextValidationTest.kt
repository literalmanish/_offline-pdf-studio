package com.offlinepdf.studio

import org.junit.Assert.*
import org.junit.Test

class UnicodeTextValidationTest {

    @Test
    fun testEnglishTextProperties() {
        val englishText = "Offline PDF Studio: Fast, offline, secure PDF documents."
        assertTrue("English text must be purely ASCII", englishText.all { it.code in 0..127 })
        assertEquals(56, englishText.length)
    }

    @Test
    fun testDevanagariMatrasCodepoints() {
        // Matras: कि (क + ि), की (क + ी), कु (क + ु), के (क + े)
        val ki = "कि"
        val kee = "की"
        val ku = "कु"
        val ke = "के"

        // Verify pre-base I-matra: Ka (U+0915) + Vowel Sign I (U+093F)
        assertEquals(2, ki.length)
        assertEquals('\u0915', ki[0]) // क
        assertEquals('\u093F', ki[1]) // ि

        // Verify post-base II-matra: Ka (U+0915) + Vowel Sign II (U+0940)
        assertEquals(2, kee.length)
        assertEquals('\u0915', kee[0]) // क
        assertEquals('\u0940', kee[1]) // ी

        // Verify below-base U-matra: Ka (U+0915) + Vowel Sign U (U+0941)
        assertEquals(2, ku.length)
        assertEquals('\u0915', ku[0]) // क
        assertEquals('\u0941', ku[1]) // ु

        // Verify above-base E-matra: Ka (U+0915) + Vowel Sign E (U+0947)
        assertEquals(2, ke.length)
        assertEquals('\u0915', ke[0]) // क
        assertEquals('\u0947', ke[1]) // े
    }

    @Test
    fun testDevanagariConjunctsAndVirama() {
        // Conjuncts: स्त (स + ् + त), त्र (त + ् + र), ज्ञ (ज + ् + ञ), क्ष (क + ् + ष)
        val virama = '\u094D'

        val sta = "स्त" // स + ् + त
        assertEquals(3, sta.length)
        assertEquals('\u0938', sta[0])
        assertEquals(virama, sta[1])
        assertEquals('\u0924', sta[2])

        val tra = "त्र" // त + ् + र
        assertEquals(3, tra.length)
        assertEquals('\u0924', tra[0])
        assertEquals(virama, tra[1])
        assertEquals('\u0930', tra[2])

        val gya = "ज्ञ" // ज + ् + ञ
        assertEquals(3, gya.length)
        assertEquals('\u091C', gya[0])
        assertEquals(virama, gya[1])
        assertEquals('\u091E', gya[2])

        val ksha = "क्ष" // क + ् + ष
        assertEquals(3, ksha.length)
        assertEquals('\u0915', ksha[0])
        assertEquals(virama, ksha[1])
        assertEquals('\u0937', ksha[2])
    }

    @Test
    fun testTargetHindiWordsCodepointSequences() {
        val words = listOf("किताब", "स्थिति", "दृष्टिकोण", "उज्ज्वल", "विद्या")
        for (word in words) {
            assertFalse("Word should not be blank: $word", word.isBlank())
            // Ensure every character belongs to Devanagari block (U+0900..U+097F)
            assertTrue("All characters in '$word' must belong to Devanagari block",
                word.all { it.code in 0x0900..0x097F })
        }
    }

    @Test
    fun testIndianRupeeSymbolAndNumerals() {
        val rupeeSymbol = "₹"
        assertEquals(1, rupeeSymbol.length)
        assertEquals('\u20B9', rupeeSymbol[0])

        // Devanagari numerals: ०, १, २, ३, ४, ५, ६, ७, ८, ९
        val hindiNumerals = "०१२३४५६७८९"
        assertEquals(10, hindiNumerals.length)
        for (i in 0 until 10) {
            val expectedCode = 0x0966 + i
            assertEquals(expectedCode, hindiNumerals[i].code)
        }

        val latinNumerals = "0123456789"
        assertEquals(10, latinNumerals.length)
    }

    @Test
    fun testMixedBilingualTextIntegrity() {
        val mixedLine = "Receipt: ₹1,500 | कुल देय राशि: १५०० रुपये"
        assertTrue(mixedLine.contains("Receipt:"))
        assertTrue(mixedLine.contains("₹1,500"))
        assertTrue(mixedLine.contains("कुल देय राशि"))
        assertTrue(mixedLine.contains("१५०० रुपये"))

        // Ensure no question mark exists in input text
        assertFalse("Mixed text should not contain literal '?'", mixedLine.contains('?'))
    }

    @Test
    fun testPaginationHeightCalculation() {
        val a4Height = 842
        val margin = 48
        val printableHeight = a4Height - (margin * 2)
        assertEquals(746, printableHeight)

        val estimatedLineHeight = 16f
        val maxLinesPerPage = (printableHeight / estimatedLineHeight).toInt()
        assertTrue("A4 page should accommodate between 40 and 50 lines", maxLinesPerPage in 40..50)

        val largeDocumentLineCount = 120
        val expectedPages = (largeDocumentLineCount + maxLinesPerPage - 1) / maxLinesPerPage
        assertTrue("120 lines should require at least 3 pages", expectedPages >= 3)
    }
}
