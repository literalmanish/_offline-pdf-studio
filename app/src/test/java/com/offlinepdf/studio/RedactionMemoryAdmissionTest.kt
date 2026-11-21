package com.offlinepdf.studio

import com.offlinepdf.studio.core.engine.internal.PdfBoxEngineImpl
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.filter.FilterFactory
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * Focused unit test verifying the memory admission calculation, boundary conditions,
 * and overflow-safe arithmetic for lossless redaction rasterization.
 */
class RedactionMemoryAdmissionTest {

    class TrackingByteArrayOutputStream(initialCapacity: Int) : ByteArrayOutputStream(initialCapacity) {
        val capacityHistory = mutableListOf<Int>()
        private var lastCapacity = initialCapacity

        init {
            capacityHistory.add(initialCapacity)
        }

        override fun write(b: Int) {
            super.write(b)
            checkCapacity()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            super.write(b, off, len)
            checkCapacity()
        }

        private fun checkCapacity() {
            val cap = buf.size
            if (cap != lastCapacity) {
                capacityHistory.add(cap)
                lastCapacity = cap
            }
        }
    }

    @Test
    fun testStandardA4AdmissionBudgetCalculation() {
        // Standard A4 at 200 DPI: (595.28 * 200/72) = 1653 x 2338 pixels (~3.86 MP)
        val w = 1653
        val h = 2338
        val pixels = w.toLong() * h.toLong()
        assertEquals(3_864_714L, pixels)

        val javaBytes = PdfBoxEngineImpl.calculateRequiredJavaBytes(w, h)
        val nativeBytes = PdfBoxEngineImpl.calculateRequiredBitmapBytes(w, h)

        // Native bitmap: 4 bytes/pixel (ARGB_8888)
        assertEquals(15_458_856L, nativeBytes)
        assertEquals(14.74f, nativeBytes / (1024f * 1024f), 0.05f)

        // Java heap: 16 bytes/pixel + 16 MiB base structures
        // (3,864,714 * 16) + (16 * 1024 * 1024) = 61,835,424 + 16,777,216 = 78,612,640 bytes (~74.97 MiB)
        val expectedJava = (pixels * 16L) + (16L * 1024L * 1024L)
        assertEquals(expectedJava, javaBytes)
        assertEquals(78_612_640L, javaBytes)
        assertEquals(74.97f, javaBytes / (1024f * 1024f), 0.05f)
    }

    @Test
    fun testLargeFormatA1AdmissionBudgetCalculation() {
        // Capped A1 sheet at max dimension 2880: 2034 x 2880 (~5.86 MP)
        val w = 2034
        val h = 2880
        val pixels = w.toLong() * h.toLong()
        assertEquals(5_857_920L, pixels)

        val javaBytes = PdfBoxEngineImpl.calculateRequiredJavaBytes(w, h)
        val nativeBytes = PdfBoxEngineImpl.calculateRequiredBitmapBytes(w, h)

        assertEquals(23_431_680L, nativeBytes)
        assertEquals(22.35f, nativeBytes / (1024f * 1024f), 0.05f)

        // (5,857,920 * 16) + (16 * 1024 * 1024) = 93,726,720 + 16,777,216 = 110,503,936 bytes (~105.38 MiB)
        assertEquals(110_503_936L, javaBytes)
        assertEquals(105.38f, javaBytes / (1024f * 1024f), 0.05f)
    }

    @Test
    fun testBoundaryConditionsZeroAndNegativeDimensions() {
        // Zero dimensions should not fail or produce negative values
        assertEquals(PdfBoxEngineImpl.PDFBOX_WORKING_BYTES, PdfBoxEngineImpl.calculateRequiredJavaBytes(0, 0))
        assertEquals(0L, PdfBoxEngineImpl.calculateRequiredBitmapBytes(0, 0))

        // Negative dimensions should be treated safely
        assertEquals(PdfBoxEngineImpl.PDFBOX_WORKING_BYTES, PdfBoxEngineImpl.calculateRequiredJavaBytes(-100, 200))
        assertEquals(PdfBoxEngineImpl.PDFBOX_WORKING_BYTES, PdfBoxEngineImpl.calculateRequiredJavaBytes(100, -200))
        assertEquals(0L, PdfBoxEngineImpl.calculateRequiredBitmapBytes(-100, 200))
        assertEquals(0L, PdfBoxEngineImpl.calculateRequiredBitmapBytes(100, -200))
    }

    @Test
    fun testOverflowSafeArithmeticOnExtremeInputs() {
        // Normal large image: 10,000 x 10,000 = 100 MP -> valid Long
        val largeW = 10_000
        val largeH = 10_000
        val largeJava = PdfBoxEngineImpl.calculateRequiredJavaBytes(largeW, largeH)
        assertTrue("Large image calculation must be positive", largeJava > 0L)

        // Extreme dimensions that would overflow 32-bit signed Int or 64-bit Long multiplication
        val extremeW = Int.MAX_VALUE
        val extremeH = Int.MAX_VALUE
        val javaMax = PdfBoxEngineImpl.calculateRequiredJavaBytes(extremeW, extremeH)
        val bitmapMax = PdfBoxEngineImpl.calculateRequiredBitmapBytes(extremeW, extremeH)

        assertEquals("Extreme dimensions must safely clamp to Long.MAX_VALUE without negative overflow", Long.MAX_VALUE, javaMax)
        assertEquals("Extreme dimensions must safely clamp to Long.MAX_VALUE without negative overflow", Long.MAX_VALUE, bitmapMax)
    }

    @Test
    fun testTheoreticalStoredBlockExpansionCalculation() {
        // Theoretical calculation based on RFC 1950 (zlib wrapper) and RFC 1951 (stored/uncompressed Deflate blocks BTYPE=00).
        // Minimal framing overhead for uncompressed Deflate blocks:
        // - Zlib header: 2 bytes
        // - Block headers: 5 bytes per 65,535 bytes
        // - Adler-32 trailer: 4 bytes
        // Note: This stored-block arithmetic serves as a theoretical reference model for non-compressible stream expansion;
        // it does not claim that every runtime Deflater implementation will choose non-compressed blocks exclusively.
        val w = 1653
        val h = 2338
        val rawRgbBytes = w.toLong() * h.toLong() * 3L // 11,594,142 bytes
        val initialBaosCapacity = rawRgbBytes / 2L      // 5,797,071 bytes (1.5 B/px)
        val firstDoublingCapacity = initialBaosCapacity * 2L // 11,594,142 bytes (3.0 B/px)

        val uncompressedBlocks = Math.ceil(rawRgbBytes.toDouble() / 65535.0).toLong()
        val minDeflateOverhead = 2L + 4L + (5L * uncompressedBlocks)
        val theoreticalDeflateSize = rawRgbBytes + minDeflateOverhead

        assertTrue("Theoretical Deflate size must exceed uncompressed size", theoreticalDeflateSize > rawRgbBytes)
        assertTrue("Theoretical Deflate size must exceed first doubling capacity of BAOS", theoreticalDeflateSize > firstDoublingCapacity)

        val excessBytes = theoreticalDeflateSize - firstDoublingCapacity
        assertTrue("Theoretical Deflate expansion exceeds first doubling capacity by at least 800 bytes", excessBytes >= 800L)
    }

    @Test
    fun testHostRuntimeDeflaterBehavior() {
        // Practical test: Run actual PDFBox 2.0.27.0 FlateFilter with deterministic high-entropy bytes
        val n = 300_000
        val rng = Random(42L)
        val rawInput = ByteArray(n)
        rng.nextBytes(rawInput)

        val initialCapacity = n / 2
        val trackingBaos = TrackingByteArrayOutputStream(initialCapacity)

        val filter = FilterFactory.INSTANCE.getFilter(COSName.FLATE_DECODE)
        val inStream = ByteArrayInputStream(rawInput)
        filter.encode(inStream, trackingBaos, COSDictionary(), 0)

        val compressedLen = trackingBaos.size()
        val history = trackingBaos.capacityHistory

        assertTrue("Compressed output must be non-empty", compressedLen > 0)
        assertTrue("BAOS capacity must grow beyond initial N/2", history.size >= 2)
        assertEquals("Initial capacity in history must be N/2", initialCapacity, history.first())
    }

    @Test
    fun testJvmConstructorCompatibility_EngineResultError() {
        val testEx = java.io.IOException("Test exception")
        val testMsg = "Test user message"

        // Verify runtime bytecode reflects both 2-arg and 3-arg JVM constructors via @JvmOverloads
        val errorClass = com.offlinepdf.studio.core.engine.model.EngineResult.Error::class.java
        val twoArgCtor = errorClass.getConstructor(Throwable::class.java, String::class.java)
        assertNotNull("JVM 2-argument constructor must exist in bytecode for binary compatibility", twoArgCtor)

        val threeArgCtor = errorClass.getConstructor(Throwable::class.java, String::class.java, java.io.File::class.java)
        assertNotNull("JVM 3-argument constructor must exist in bytecode", threeArgCtor)

        // Verify generated copy() and copy$default() have the original 2-argument JVM signatures
        val copyMethod = errorClass.getMethod("copy", Throwable::class.java, String::class.java)
        assertNotNull("JVM copy(Throwable, String) must exist with original 2-arg signature", copyMethod)

        val copyDefaultMethod = errorClass.getMethod("copy\$default", errorClass, Throwable::class.java, String::class.java, Int::class.javaPrimitiveType, Any::class.java)
        assertNotNull("JVM copy\$default must exist with original parameters", copyDefaultMethod)

        // Instantiate via 2-argument constructor and verify default values
        val errorFromTwoArg = twoArgCtor.newInstance(testEx, testMsg)
        assertEquals(testEx, errorFromTwoArg.exception)
        assertEquals(testMsg, errorFromTwoArg.userMessage)
        assertEquals(null, errorFromTwoArg.committedFile)
        assertFalse("isPostPublicationFailure must be false for 2-arg constructor", errorFromTwoArg.isPostPublicationFailure)

        // Instantiate via 3-argument constructor and verify committed file tracking
        val dummyFile = java.io.File("dummy_committed.pdf")
        val errorFromThreeArg = threeArgCtor.newInstance(testEx, testMsg, dummyFile)
        assertEquals(testEx, errorFromThreeArg.exception)
        assertEquals(testMsg, errorFromThreeArg.userMessage)
        assertEquals(dummyFile, errorFromThreeArg.committedFile)
        assertTrue("isPostPublicationFailure must be true when committedFile is provided", errorFromThreeArg.isPostPublicationFailure)

        // Verify copy() operates solely on primary properties and does not carry the body property committedFile
        val copied = errorFromThreeArg.copy(testEx, "New message")
        assertEquals("New message", copied.userMessage)
        assertEquals(null, copied.committedFile)
        assertFalse("Generated copy does not carry body property committedFile", copied.isPostPublicationFailure)
    }
}
