# Offline PDF Studio (Native Android)

A 100% offline, privacy-first Android PDF manipulation studio built with Kotlin, Jetpack Compose, Material 3, `PdfRenderer`, and `PdfBox-Android`.

## Architecture & Features

- **Language & Framework**: Kotlin 2.1+, Jetpack Compose with Material 3.
- **Rendering Engine**: Hardware-accelerated `android.graphics.pdf.PdfRenderer` (zero cloud dependencies, streaming page-by-page).
- **Manipulation Engine**: `com.tom-roush:pdfbox-android` (Apache PDFBox port for Android) running on `Dispatchers.IO`.
- **Target SDK**: API 36 (Android 16), Min SDK 26 (Android 8.0).
- **Privacy Guarantee**: **Zero network permissions** declared in `AndroidManifest.xml`. All processing stays strictly on-device.

---

## 32 Comprehensive Offline PDF Features

### 📂 Group 1: Organize & Edit Pages (1-10)
1. **Merge Documents**: Concatenate 2 or more PDF files into a single document with streaming buffer.
2. **Split PDF**: Extract specific pages or separate custom page ranges (`1-3, 4-6`).
3. **Reorder & Delete**: Visual drag-and-drop thumbnail grid to reorganize or remove pages.
4. **Rotate Pages**: Rotate all or selected pages by 90°, 180°, or 270°.
5. **Reverse Page Order**: Flip document order from last to first page with 1 tap.
6. **Duplicate Pages**: Clone designated pages within the PDF.
7. **Insert Blank Pages**: Add blank pages at specific positions for duplex/booklet printing.
8. **Crop Margins**: Trim white borders and adjust CropBox bounds.
9. **2-Up / Booklet Sheet**: Place 2 pages side-by-side or stacked per sheet.
10. **Flatten Form & Annotations**: Permanently bake form fields and markups into static content.

### 🖼️ Group 2: Convert & Export (11-15)
11. **Images to PDF**: Convert JPG, PNG, and camera photos into a clean PDF document.
12. **PDF to Images**: Export PDF pages as high-resolution PNG or JPG images.
13. **Extract Embedded Images**: Scan and extract all pictures/graphics inside a PDF.
14. **Extract Text**: Extract all text content from PDF pages to a `.txt` file.
15. **Text to PDF**: Type or paste notes and convert them directly into a formatted PDF.

### 🔒 Group 3: Security & Privacy (16-20)
16. **Protect with Password**: Encrypt PDF with standard 128-bit user & owner password.
17. **Unlock / Decrypt**: Permanently strip password protection from an unlocked PDF.
18. **Edit Metadata**: Modify PDF Title, Author, Subject, and Keywords.
19. **Sanitize & Scrub**: Strip all hidden author info, software history, and tracking metadata.
20. **Repair Corrupted PDF**: Rebuild damaged cross-reference tables and recover pages.

### 🎨 Group 4: Design & Stamps (21-25)
21. **Add Watermark**: Apply diagonal or horizontal text watermarks with custom opacity.
22. **Add Page Numbers**: Insert customizable page numbers (`Page {n} of {total}`) in headers or footers.
23. **Header & Footer**: Add customized running header and footer text across all pages.
24. **Stamp Document**: Apply CONFIDENTIAL, APPROVED, DRAFT, COPY, or custom status stamps.
25. **Sign PDF**: Draw your digital signature with your finger and place it on any page.

### ⚡ Group 5: Optimize & Color (26-29)
26. **Compress PDF**: Reduce file size via on-device image downsampling (Light, Balanced, Aggressive).
27. **Black & White (Grayscale)**: Convert colorful documents into monochrome to save print ink and size.
28. **Night Mode (Invert Colors)**: Invert background and text for comfortable night reading.
29. **Delete Specific Pages**: Remove designated pages by entering page numbers.

### 📖 Group 6: Read & Inspect (30-32)
30. **View & Read PDF**: Fast, hardware-accelerated offline document reader with zoom and paging.
31. **Bookmarks / Outline**: Inspect and navigate document chapter bookmarks.
32. **Compare 2 PDFs**: Side-by-side structural comparison of two PDF files.
