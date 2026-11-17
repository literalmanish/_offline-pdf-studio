package com.offlinepdf.studio

import com.offlinepdf.studio.core.util.StorageFolder
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StorageScannerTest {

    @Test
    fun testStorageFolderModelInvariants() {
        val folder = StorageFolder(
            id = "/storage/emulated/0/Download",
            name = "Downloads",
            path = "/storage/emulated/0/Download",
            files = listOf(File("test1.pdf"), File("test2.pdf"))
        )

        assertEquals("Downloads", folder.name)
        assertEquals(2, folder.files.size)
        assertEquals("/storage/emulated/0/Download", folder.id)
    }

    @Test
    fun testEmptyStorageFolder() {
        val emptyFolder = StorageFolder(
            id = "/storage/emulated/0/Documents",
            name = "Documents",
            path = "/storage/emulated/0/Documents",
            files = emptyList()
        )

        assertEquals(0, emptyFolder.files.size)
        assertTrue(emptyFolder.files.isEmpty())
    }
}
