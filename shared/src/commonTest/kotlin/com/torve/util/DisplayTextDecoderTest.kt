package com.torve.util

import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayTextDecoderTest {
    @Test
    fun separatorEscapedReleaseNamesRecoverUtf8BeforeFilenameCleanup() {
        listOf("M.C3.BCnchen", "M_c3_bcnchen", "M C3 Bcnchen").forEach { escaped ->
            assertEquals("München", DisplayTextDecoder.decodeReleaseTitle(escaped), escaped)
            assertEquals(escaped, DisplayTextDecoder.decode(escaped), "Ordinary labels do not infer filename escapes")
        }
        assertEquals("MÜnchen", DisplayTextDecoder.decodeReleaseTitle("M.C3.9Cnchen"))
        assertEquals("Žalgiris", DisplayTextDecoder.decodeReleaseTitle("C5.BDalgiris"))
        assertEquals("São.Paulo", DisplayTextDecoder.decodeReleaseTitle("S.C3.A3o.Paulo"))
        assertEquals("Österreich", DisplayTextDecoder.decodeReleaseTitle("C3.96sterreich"))
        assertEquals("Euro€cup", DisplayTextDecoder.decodeReleaseTitle("Euro.E2.82.ACcup"))
        assertEquals("Team🏀ball", DisplayTextDecoder.decodeReleaseTitle("Team.F0.9F.8F.80ball"))
        assertEquals("Mäöünchen", DisplayTextDecoder.decodeReleaseTitle("M.C3.A4.C3.B6.C3.BCnchen"))
        assertEquals("Bayern.Über", DisplayTextDecoder.decodeReleaseTitle("Bayern..C3.9Cber"))
        assertEquals("Bayern Über", DisplayTextDecoder.decodeReleaseTitle("Bayern C3.9Cber"))
    }

    @Test
    fun releaseRecoveryPreservesHexadecimalCodesInvalidUtf8AndControlBytes() {
        listOf("AB.C3.BCDEF", "BMW.C3.BC", "C3.BC", "M.ED.A0.80nchen", "M.F4.90.80.80nchen",
            "M.C0.BCnchen", "M.C2.80nchen", "M.C3nchen", "Bayern München + 東京").forEach {
            assertEquals(it, DisplayTextDecoder.decodeReleaseTitle(it), it)
        }
    }

    @Test
    fun percentEncodedUmlautsAccentsAndSymbolsUseUtf8() {
        assertEquals("Bayern München", DisplayTextDecoder.decode("Bayern%20M%C3%BCnchen"))
        assertEquals("ÄÖÜ äöü ß é ç ñ ø Ł €", DisplayTextDecoder.decode(
            "%C3%84%C3%96%C3%9C%20%c3%a4%c3%b6%c3%bc%20%C3%9F%20%C3%A9%20%C3%A7%20%C3%B1%20%C3%B8%20%C5%81%20%E2%82%AC",
        ))
    }

    @Test
    fun nonLatinNamesCombiningMarksAndSupplementaryCharactersSurviveDecoding() {
        listOf("Ολυμπιακός", "Црвена звезда", "日本 東京", "العربية", "हिन्दी", "Mu\u0308nchen", "🏀 🎾 𝄞 𠮷").forEach { text ->
            val escaped = text.encodeToByteArray().joinToString("") { byte ->
                "%${(byte.toInt() and 0xFF).toString(16).padStart(2, '0')}"
            }
            assertEquals(text, DisplayTextDecoder.decode(escaped), text)
            assertEquals(text, DisplayTextDecoder.decode(text), text)
        }
    }

    @Test
    fun htmlAndXmlNamedReferencesKeepTheirFullUnicodeValues() {
        assertEquals("München ÄÖÜ äöü ß São Łódź & < > \" '", DisplayTextDecoder.decode(
            "M&uuml;nchen &Auml;&Ouml;&Uuml; &auml;&ouml;&uuml; &szlig; S&atilde;o &Lstrok;&oacute;d&zacute; &amp; &lt; &gt; &quot; &apos;",
        ))
        assertEquals("≂\u0338 𝔄", DisplayTextDecoder.decode("&NotEqualTilde; &Afr;"))
    }

    @Test
    fun decimalAndHexadecimalReferencesIncludeSupplementaryCodePoints() {
        assertEquals("München München 🏀 🏀", DisplayTextDecoder.decode("M&#252;nchen M&#xFC;nchen &#127936; &#X1F3C0;"))
    }

    @Test
    fun nestedProviderEscapesAreDecodedBeforeTitleCleanup() {
        assertEquals("München", DisplayTextDecoder.decode("M%25C3%25BCnchen"))
        assertEquals("München", DisplayTextDecoder.decode("M&amp;uuml;nchen"))
        assertEquals("München", DisplayTextDecoder.decode("M&#37;C3&#37;BCnchen"))
        assertEquals("München", DisplayTextDecoder.decode("M%26uuml%3Bnchen"))
    }

    @Test
    fun ordinaryUnicodePlusSignsAmpersandsAndPercentagesStayIntact() {
        val title = "Bayern München + Žalgiris — São Paulo & 東京 · 100% / 20% off"
        assertEquals(title, DisplayTextDecoder.decode(title))
        assertEquals("A+B 100%", DisplayTextDecoder.decode("A+B%20100%25"))
    }

    @Test
    fun malformedEscapesAndInvalidUnicodeNeverDestroyTheOriginalText() {
        listOf("M%C3nchen", "%C3%ZZ", "%FF%FE", "%E2%82", "%ED%A0%80", "%F4%90%80%80", "%G0", "%", "&unknown;", "&#0;", "&#xD800;", "&#1114112;", "&#9999999999;").forEach {
            assertEquals(it, DisplayTextDecoder.decode(it), it)
        }
        assertEquals("München %FF 東京", DisplayTextDecoder.decode("M%C3%BCnchen %FF 東京"))
    }
}
