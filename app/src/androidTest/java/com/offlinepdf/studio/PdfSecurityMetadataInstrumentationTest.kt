package com.offlinepdf.studio

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offlinepdf.studio.core.engine.internal.PdfBoxEngineImpl
import com.offlinepdf.studio.core.engine.model.DocumentMetadata
import com.offlinepdf.studio.core.engine.model.EngineResult
import com.offlinepdf.studio.core.util.FileStorageManager
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class PdfSecurityMetadataInstrumentationTest {

    private lateinit var context: Context
    private lateinit var storageManager: FileStorageManager
    private lateinit var engine: PdfBoxEngineImpl
    private lateinit var testDocsDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storageManager = FileStorageManager(context)
        engine = PdfBoxEngineImpl(context, storageManager)
        testDocsDir = File(context.filesDir, "test_sec_meta").apply {
            if (!exists()) mkdirs()
        }
    }

    @After
    fun tearDown() {
        testDocsDir.deleteRecursively()
        File(context.cacheDir, "pdf_processing").deleteRecursively()
    }

    // 1. protectPdf - Success
    @Test
    fun test01_protectPdf_success() = runBlocking {
        val src = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "to_protect.pdf"), 2)
        val password = "SecretPassword123"

        val result = engine.protectPdf(
            sourceUri = Uri.fromFile(src),
            userPassword = password,
            ownerPassword = password,
            outputBaseName = "test_protected"
        )

        assertTrue("Protect must succeed", result is EngineResult.Success)
        val protectedFile = (result as EngineResult.Success).data

        // Verify document is encrypted
        PDDocument.load(protectedFile, password).use { doc ->
            assertTrue("Document must be marked encrypted", doc.isEncrypted)
            assertEquals("Page count must be preserved", 2, doc.numberOfPages)
        }
    }

    // 2. unlockPdf - Success
    @Test
    fun test02_unlockPdf_success() = runBlocking {
        val password = "UnlockMe456"
        val encrypted = PdfEngineTestFixtures.createEncryptedPdf(File(testDocsDir, "encrypted.pdf"), password)

        val result = engine.unlockPdf(
            sourceUri = Uri.fromFile(encrypted),
            password = password,
            outputBaseName = "test_unlocked"
        )

        assertTrue("Unlock must succeed", result is EngineResult.Success)
        val unlockedFile = (result as EngineResult.Success).data

        // Load without password must now succeed
        PDDocument.load(unlockedFile).use { doc ->
            assertFalse("Unlocked document must not be encrypted", doc.isEncrypted)
            assertEquals(1, doc.numberOfPages)
        }
    }

    // 3. unlockPdf - Incorrect password failure
    @Test
    fun test03_unlockPdf_incorrectPasswordFails() = runBlocking {
        val encrypted = PdfEngineTestFixtures.createEncryptedPdf(File(testDocsDir, "encrypted_wrong.pdf"), "RealPassword")

        val result = engine.unlockPdf(
            sourceUri = Uri.fromFile(encrypted),
            password = "WrongPassword",
            outputBaseName = "test_unlocked_fail"
        )

        assertTrue("Unlock with wrong password must return Error", result is EngineResult.Error)
        val error = result as EngineResult.Error
        assertTrue("Error user message must mention password", error.userMessage.contains("password", ignoreCase = true))
    }

    // 4. getMetadata
    @Test
    fun test04_getMetadata() = runBlocking {
        val src = PdfEngineTestFixtures.createPdfWithMetadata(
            File(testDocsDir, "meta_src.pdf"),
            title = "Annual Financial Report",
            author = "Finance Dept",
            subject = "Fiscal Q4",
            keywords = "finance, q4, audit",
            creator = "Offline PDF Studio"
        )

        val metadata = engine.getMetadata(Uri.fromFile(src))
        assertEquals("Annual Financial Report", metadata.title)
        assertEquals("Finance Dept", metadata.author)
        assertEquals("Fiscal Q4", metadata.subject)
        assertEquals("finance, q4, audit", metadata.keywords)
        assertEquals("Offline PDF Studio", metadata.creator)
    }

    // 5. editMetadata
    @Test
    fun test05_editMetadata() = runBlocking {
        val src = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "edit_meta_src.pdf"), 1)
        val newMeta = DocumentMetadata(
            title = "Updated Title",
            author = "Security Officer",
            subject = "Confidential",
            keywords = "secure, classified",
            creator = "Offline Engine"
        )

        val result = engine.editMetadata(
            sourceUri = Uri.fromFile(src),
            metadata = newMeta,
            outputBaseName = "test_meta_updated"
        )

        assertTrue("Edit metadata must succeed", result is EngineResult.Success)
        val updatedFile = (result as EngineResult.Success).data

        val readBack = engine.getMetadata(Uri.fromFile(updatedFile))
        assertEquals("Updated Title", readBack.title)
        assertEquals("Security Officer", readBack.author)
        assertEquals("Confidential", readBack.subject)
        assertEquals("secure, classified", readBack.keywords)
        assertEquals("Offline Engine", readBack.creator)
    }

    // 6. sanitizeMetadata
    @Test
    fun test06_sanitizeMetadata() = runBlocking {
        val src = PdfEngineTestFixtures.createPdfWithMetadata(
            File(testDocsDir, "sanitize_src.pdf"),
            title = "Sensitive Tracked Document",
            author = "Secret Agent"
        )

        val result = engine.sanitizeMetadata(
            sourceUri = Uri.fromFile(src),
            outputBaseName = "test_sanitized"
        )

        assertTrue("Sanitize metadata must succeed", result is EngineResult.Success)
        val sanitizedFile = (result as EngineResult.Success).data

        val readBack = engine.getMetadata(Uri.fromFile(sanitizedFile))
        assertTrue("Title must be empty after sanitization", readBack.title.isBlank())
        assertTrue("Author must be empty after sanitization", readBack.author.isBlank())

        PDDocument.load(sanitizedFile).use { doc ->
            assertNull("Catalog metadata must be null", doc.documentCatalog.metadata)
        }
    }

    // 7. flattenPdf
    @Test
    fun test07_flattenPdf() = runBlocking {
        val src = PdfEngineTestFixtures.createPdfWithAcroForm(File(testDocsDir, "acroform_src.pdf"))

        val result = engine.flattenPdf(
            sourceUri = Uri.fromFile(src),
            outputBaseName = "test_flattened"
        )

        assertTrue("Flatten must succeed", result is EngineResult.Success)
        val flattenedFile = (result as EngineResult.Success).data

        PDDocument.load(flattenedFile).use { doc ->
            val acroForm = doc.documentCatalog.acroForm
            // Flatten removes interactive fields or leaves 0 fields
            assertTrue("AcroForm must be null or have 0 fields after flattening", acroForm == null || acroForm.fields.isEmpty())
        }
    }

    // 8. extractBookmarks
    @Test
    fun test08_extractBookmarks() = runBlocking {
        val titles = listOf("Chapter 1: Overview", "Chapter 2: Architecture", "Chapter 3: Verification")
        val src = PdfEngineTestFixtures.createPdfWithOutline(File(testDocsDir, "bookmarks_src.pdf"), titles)

        val extracted = engine.extractBookmarks(Uri.fromFile(src))
        assertEquals("Must extract 3 bookmark titles", 3, extracted.size)
        assertEquals("Chapter 1: Overview", extracted[0])
        assertEquals("Chapter 2: Architecture", extracted[1])
        assertEquals("Chapter 3: Verification", extracted[2])
    }

    // 9. comparePdfs
    @Test
    fun test09_comparePdfs() = runBlocking {
        val docA = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "cmpA.pdf"), 2)
        val docB = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "cmpB.pdf"), 5)

        val (countA, countB) = engine.comparePdfs(Uri.fromFile(docA), Uri.fromFile(docB))
        assertEquals(2, countA)
        assertEquals(5, countB)
    }

    // 10. compareDocumentsDetailed
    @Test
    fun test10_compareDocumentsDetailed() = runBlocking {
        val docA = PdfEngineTestFixtures.createPdfWithMetadata(
            File(testDocsDir, "detA.pdf"),
            title = "Report V1",
            author = "Author A"
        )
        val docB = PdfEngineTestFixtures.createPdfWithMetadata(
            File(testDocsDir, "detB.pdf"),
            title = "Report V2",
            author = "Author B"
        )

        val report = engine.compareDocumentsDetailed(Uri.fromFile(docA), Uri.fromFile(docB))
        assertEquals("Report V1", report.titleA)
        assertEquals("Report V2", report.titleB)
        assertEquals("Author A", report.authorA)
        assertEquals("Author B", report.authorB)
        assertEquals(1, report.pageCountA)
        assertEquals(1, report.pageCountB)
        assertFalse(report.isEncryptedA)
        assertFalse(report.isEncryptedB)
    }

    // 11. getDocumentStats
    @Test
    fun test11_getDocumentStats() = runBlocking {
        val src = PdfEngineTestFixtures.createPdfWithEmbeddedImage(File(testDocsDir, "stats_src.pdf"), 100, 100)

        val stats = engine.getDocumentStats(Uri.fromFile(src))
        assertEquals("Page count must be 1", 1, stats.pageCount)
        assertEquals("Image count must be 1", 1, stats.imageCount)
        assertFalse("Document is not encrypted", stats.isEncrypted)
        assertTrue("File size must be positive", stats.fileSizeBytes > 0)
    }

    // 12. Security operations: Source preservation and cleanup verification
    @Test
    fun test12_security_sourcePreservationAndCleanup() = runBlocking {
        val src = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "source_sec_check.pdf"), 2)
        val origLen = src.length()
        val origBytes = src.readBytes()

        val result = engine.protectPdf(
            sourceUri = Uri.fromFile(src),
            userPassword = "TempPassword123",
            ownerPassword = "TempPassword123",
            outputBaseName = "test_sec_check_out"
        )
        assertTrue(result is EngineResult.Success)

        // Verify source file is preserved intact
        assertEquals("Source file length must remain intact", origLen, src.length())
        assertArrayEquals("Source bytes must remain unchanged", origBytes, src.readBytes())

        // Verify temp directory cleaned up
        val procDir = File(context.cacheDir, "pdf_processing")
        val orphaned = procDir.listFiles { _, name -> name.startsWith("enc_") } ?: emptyArray()
        assertEquals("Temporary processing files must be cleaned up", 0, orphaned.size)
    }
}
