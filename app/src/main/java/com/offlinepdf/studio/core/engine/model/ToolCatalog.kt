package com.offlinepdf.studio.core.engine.model

object ToolCatalog {
    val allTools: List<StudioTool> = listOf(
        // Group 1: Organize & Edit Pages (1-10)
        StudioTool(
            id = "merge",
            title = "Merge Documents",
            description = "Combine multiple PDF files into one single document",
            category = ToolCategory.ORGANIZE,
            iconName = "CallMerge",
            badge = "Popular"
        ),
        StudioTool(
            id = "split",
            title = "Split PDF",
            description = "Extract pages or separate specific chapter ranges",
            category = ToolCategory.ORGANIZE,
            iconName = "CallSplit",
            badge = "Essential"
        ),
        StudioTool(
            id = "reorder",
            title = "Reorder & Delete",
            description = "Visual thumbnail grid to reorganize or remove pages",
            category = ToolCategory.ORGANIZE,
            iconName = "ViewAgenda"
        ),
        StudioTool(
            id = "rotate",
            title = "Rotate Pages",
            description = "Rotate all or selected pages by 90°, 180°, or 270°",
            category = ToolCategory.ORGANIZE,
            iconName = "RotateRight"
        ),
        StudioTool(
            id = "reverse",
            title = "Reverse Page Order",
            description = "Flip whole document order from last to first page",
            category = ToolCategory.ORGANIZE,
            iconName = "SwapVert"
        ),
        StudioTool(
            id = "duplicate",
            title = "Duplicate Pages",
            description = "Clone designated pages within your PDF",
            category = ToolCategory.ORGANIZE,
            iconName = "ContentCopy"
        ),
        StudioTool(
            id = "blank_pages",
            title = "Insert Blank Pages",
            description = "Add blank pages at specific positions for duplex printing",
            category = ToolCategory.ORGANIZE,
            iconName = "NoteAdd"
        ),
        StudioTool(
            id = "crop",
            title = "Crop Margins",
            description = "Trim white margins or adjust page borders",
            category = ToolCategory.ORGANIZE,
            iconName = "Crop"
        ),
        StudioTool(
            id = "nup",
            title = "2-Up / Booklet Sheet",
            description = "Combine 2 pages per sheet for printing",
            category = ToolCategory.ORGANIZE,
            iconName = "Dashboard"
        ),
        StudioTool(
            id = "flatten",
            title = "Flatten Form & Annotations",
            description = "Convert form fields and markups into permanent content",
            category = ToolCategory.ORGANIZE,
            iconName = "LayersClear"
        ),

        // Group 2: Convert & Export (11-15)
        StudioTool(
            id = "images_to_pdf",
            title = "Images to PDF",
            description = "Convert JPG, PNG, and camera photos into a clean PDF",
            category = ToolCategory.CONVERT,
            iconName = "PhotoLibrary",
            badge = "Popular"
        ),
        StudioTool(
            id = "pdf_to_images",
            title = "PDF to Images",
            description = "Export all PDF pages as high-resolution PNG or JPG images",
            category = ToolCategory.CONVERT,
            iconName = "Image"
        ),
        StudioTool(
            id = "extract_images",
            title = "Extract Embedded Images",
            description = "Rip and save all embedded pictures from inside a PDF",
            category = ToolCategory.CONVERT,
            iconName = "Filter"
        ),
        StudioTool(
            id = "extract_text",
            title = "Extract Text",
            description = "Extract all text content from PDF pages to a text file",
            category = ToolCategory.CONVERT,
            iconName = "Article"
        ),
        StudioTool(
            id = "text_to_pdf",
            title = "Text to PDF",
            description = "Type or paste notes and convert them into a styled PDF",
            category = ToolCategory.CONVERT,
            iconName = "TextFields"
        ),

        // Group 3: Security & Privacy (16-20)
        StudioTool(
            id = "protect",
            title = "Protect with Password",
            description = "Encrypt your PDF with standard 128-bit security password",
            category = ToolCategory.SECURITY,
            iconName = "Lock",
            badge = "Security"
        ),
        StudioTool(
            id = "unlock",
            title = "Unlock / Remove Password",
            description = "Permanently remove password protection from an unlocked PDF",
            category = ToolCategory.SECURITY,
            iconName = "LockOpen"
        ),
        StudioTool(
            id = "edit_metadata",
            title = "Edit Metadata",
            description = "Update PDF Title, Author, Subject, and Keywords",
            category = ToolCategory.SECURITY,
            iconName = "Badge"
        ),
        StudioTool(
            id = "sanitize",
            title = "Sanitize & Scrub Privacy",
            description = "Remove all hidden author info, software history, and metadata",
            category = ToolCategory.SECURITY,
            iconName = "CleaningServices",
            badge = "Privacy"
        ),
        StudioTool(
            id = "repair",
            title = "Repair Damaged PDF",
            description = "Rebuild damaged cross-reference tables and recover contents",
            category = ToolCategory.SECURITY,
            iconName = "Build"
        ),

        // Group 4: Design & Stamps (21-25)
        StudioTool(
            id = "watermark",
            title = "Add Watermark",
            description = "Apply diagonal or horizontal custom text watermark",
            category = ToolCategory.BRANDING,
            iconName = "BrandingWatermark",
            badge = "Design"
        ),
        StudioTool(
            id = "page_numbers",
            title = "Add Page Numbers",
            description = "Insert customizable page numbers in header or footer",
            category = ToolCategory.BRANDING,
            iconName = "FormatListNumbered"
        ),
        StudioTool(
            id = "header_footer",
            title = "Header & Footer",
            description = "Add customized running header and footer text across all pages",
            category = ToolCategory.BRANDING,
            iconName = "Title"
        ),
        StudioTool(
            id = "stamp",
            title = "Stamp Document",
            description = "Apply CONFIDENTIAL, APPROVED, DRAFT, or custom stamps",
            category = ToolCategory.BRANDING,
            iconName = "Approval"
        ),
        StudioTool(
            id = "sign",
            title = "Sign PDF",
            description = "Draw your digital signature with your finger and place it on pages",
            category = ToolCategory.BRANDING,
            iconName = "Draw",
            badge = "Sign"
        ),

        // Group 5: Optimize & Color (26-29)
        StudioTool(
            id = "compress",
            title = "Compress PDF",
            description = "Reduce document file size with on-device image downsampling",
            category = ToolCategory.OPTIMIZE,
            iconName = "Compress",
            badge = "Popular"
        ),
        StudioTool(
            id = "grayscale",
            title = "Black & White (Grayscale)",
            description = "Convert colorful documents into monochrome black and white",
            category = ToolCategory.OPTIMIZE,
            iconName = "Contrast"
        ),
        StudioTool(
            id = "invert_colors",
            title = "Night Mode (Invert Colors)",
            description = "Invert background and text for comfortable night reading",
            category = ToolCategory.OPTIMIZE,
            iconName = "DarkMode"
        ),
        StudioTool(
            id = "delete_pages",
            title = "Delete Specific Pages",
            description = "Remove designated pages by typing page numbers",
            category = ToolCategory.OPTIMIZE,
            iconName = "DeleteSweep"
        ),

        // Group 6: Read & Inspect (30-32)
        StudioTool(
            id = "viewer",
            title = "View & Read PDF",
            description = "Fast, hardware-accelerated offline document reader",
            category = ToolCategory.VIEW,
            iconName = "Visibility",
            badge = "Fast"
        ),
        StudioTool(
            id = "bookmarks",
            title = "Table of Contents / Bookmarks",
            description = "Inspect and read chapter outlines and navigation bookmarks",
            category = ToolCategory.VIEW,
            iconName = "Bookmarks"
        ),
        StudioTool(
            id = "compare",
            title = "Compare 2 PDFs",
            description = "Inspect two PDF versions side-by-side for differences",
            category = ToolCategory.VIEW,
            iconName = "Compare"
        )
    )
}
