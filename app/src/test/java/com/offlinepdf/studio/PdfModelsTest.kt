package com.offlinepdf.studio

import com.offlinepdf.studio.core.engine.model.*
import org.junit.Assert.*
import org.junit.Test

class PdfModelsTest {

    @Test
    fun testProcessingProgressPercentageCalculation() {
        val p1 = ProcessingProgress(current = 5, total = 10, message = "Halfway")
        assertEquals(0.5f, p1.percentage, 0.001f)

        val pZeroTotal = ProcessingProgress(current = 0, total = 0, message = "Starting")
        assertEquals(0f, pZeroTotal.percentage, 0.001f)

        val pComplete = ProcessingProgress(current = 10, total = 10, message = "Done")
        assertEquals(1.0f, pComplete.percentage, 0.001f)

        val pOver = ProcessingProgress(current = 15, total = 10, message = "Over")
        assertEquals(1.0f, pOver.percentage, 0.001f)
    }

    @Test
    fun testCompressionLevels() {
        val low = CompressionLevel.LOW
        val med = CompressionLevel.MEDIUM
        val high = CompressionLevel.HIGH

        assertTrue("Low compression should have highest image quality", low.imageQuality > med.imageQuality)
        assertTrue("Medium compression should have higher image quality than high", med.imageQuality > high.imageQuality)
        assertTrue("Low compression max dimension should exceed high compression", low.maxDimension > high.maxDimension)
    }

    @Test
    fun testStandardStamps() {
        for (stamp in StandardStamp.entries) {
            assertFalse("Stamp label must not be blank", stamp.label.isBlank())
            assertTrue("Stamp color hex must be non-zero", stamp.colorHex != 0L)
        }
    }

    @Test
    fun testComparisonReportInvariants() {
        val report = PdfComparisonReport(
            nameA = "DocA.pdf",
            nameB = "DocB.pdf",
            sizeA = 1024L,
            sizeB = 2048L,
            pageCountA = 3,
            pageCountB = 3,
            versionA = "1.4",
            versionB = "1.7",
            titleA = "Report",
            titleB = "Report Final",
            authorA = "Alice",
            authorB = "Bob",
            isEncryptedA = false,
            isEncryptedB = false,
            textLengthA = 500,
            textLengthB = 520
        )

        assertEquals("DocA.pdf", report.nameA)
        assertEquals("DocB.pdf", report.nameB)
        assertEquals(3, report.pageCountA)
        assertEquals(3, report.pageCountB)
        assertFalse(report.isEncryptedA)
    }
}
