package com.offlinepdf.studio

import com.offlinepdf.studio.core.engine.model.ToolCatalog
import com.offlinepdf.studio.core.engine.model.ToolCategory
import org.junit.Assert.*
import org.junit.Test

class ToolCatalogTest {

    @Test
    fun testToolCatalogContainsExpectedToolCount() {
        val tools = ToolCatalog.allTools
        // Offline PDF Studio advertises a suite of 36 essential tools
        assertTrue("Catalog should contain at least 30 tools", tools.size >= 30)
        assertEquals(36, tools.size)
    }

    @Test
    fun testToolIdsAreStrictlyUnique() {
        val tools = ToolCatalog.allTools
        val ids = tools.map { it.id }
        val uniqueIds = ids.toSet()
        assertEquals("All tool IDs must be strictly unique", ids.size, uniqueIds.size)
    }

    @Test
    fun testToolAttributesAreNonEmpty() {
        val tools = ToolCatalog.allTools
        for (tool in tools) {
            assertFalse("Tool ID should not be blank: ${tool.id}", tool.id.isBlank())
            assertFalse("Tool title should not be blank: ${tool.id}", tool.title.isBlank())
            assertFalse("Tool description should not be blank: ${tool.id}", tool.description.isBlank())
            assertFalse("Tool icon name should not be blank: ${tool.id}", tool.iconName.isBlank())
            assertNotNull("Tool category must not be null: ${tool.id}", tool.category)
        }
    }

    @Test
    fun testAllCategoriesAreRepresented() {
        val tools = ToolCatalog.allTools
        val categoriesInCatalog = tools.map { it.category }.toSet()
        for (category in ToolCategory.entries) {
            assertTrue("Category $category should be represented in catalog", categoriesInCatalog.contains(category))
        }
    }

    @Test
    fun testCriticalToolsExistInCatalog() {
        val tools = ToolCatalog.allTools.associateBy { it.id }
        assertTrue("Duplicate tool must exist", tools.containsKey("duplicate"))
        assertTrue("Compare tool must exist", tools.containsKey("compare"))
        assertTrue("Redact tool must exist", tools.containsKey("redact"))
        assertTrue("Text to PDF tool must exist", tools.containsKey("text_to_pdf"))
        assertTrue("Rotate tool must exist", tools.containsKey("rotate"))
        assertTrue("Merge tool must exist", tools.containsKey("merge"))
        assertTrue("Split tool must exist", tools.containsKey("split"))
        assertTrue("Compress tool must exist", tools.containsKey("compress"))
    }
}
