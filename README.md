# Offline PDF Studio (Native Android v2.1.1)

A 100% offline, privacy-first Android PDF manipulation studio built with Kotlin, Jetpack Compose, Material 3, `PdfRenderer`, and `PdfBox-Android`.

## 🌟 What's New in v2.1.0
- **Modern Dark UI/UX Redesign**: Premium dark theme inspired by iLovePDF dark specifications (`#151515` canvas, `#090909` tool cards, `#292831` navigation dock, and `#FFA9B6` soft pink active accents).
- **5-Tab Persistent Bottom Navigation**: Seamless switching between **Home**, **Files** (storage dashboard & category filters), **Tools** (responsive 3-column tools grid with instant search), **Scanner** (camera & gallery scanning), and **Settings** (privacy badge, cache cleaner).
- **1-Click Direct Download**: Direct saving of processed files to phone's public `Downloads/OfflinePDFStudio/` folder alongside instant preview and share actions.
- **Full 36 Offline Tool Suite**: Complete, robust native tooling operating strictly on `Dispatchers.IO` with zero network permissions.

## Architecture & Features

- **Language & Framework**: Kotlin 2.1+, Jetpack Compose with Material 3.
- **Rendering Engine**: Hardware-accelerated `android.graphics.pdf.PdfRenderer` (zero cloud dependencies, streaming page-by-page).
- **Manipulation Engine**: `com.tom-roush:pdfbox-android` (Apache PDFBox port for Android) running on `Dispatchers.IO`.
- **Target SDK**: API 36 (Android 16), Min SDK 26 (Android 8.0).
- **Privacy Guarantee**: **Zero network permissions** declared in `AndroidManifest.xml`. All processing stays strictly on-device.

---

## 36 Comprehensive Offline PDF Features

### 📂 Group 1: Organize & Edit Pages (1–10)
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

### 🖼️ Group 2: Convert & Export (11–15)
11. **Images to PDF**: Convert JPG, PNG, and camera photos into a clean PDF document.
12. **PDF to Images**: Export PDF pages as high-resolution PNG or JPG images.
13. **Extract Embedded Images**: Scan and extract all pictures/graphics inside a PDF.
14. **Extract Text**: Extract all text content from PDF pages to a `.txt` file.
15. **Text to PDF**: Type or paste notes and convert them directly into a formatted PDF.

### 🔒 Group 3: Security & Privacy (16–21)
16. **Protect with Password**: Encrypt PDF with standard 128-bit user & owner password.
17. **Unlock / Decrypt**: Permanently strip password protection from an unlocked PDF.
18. **Redact Sensitive Info**: Draw permanent blackout boxes over personal data, phone numbers, or credit cards.
19. **Edit Metadata**: Modify PDF Title, Author, Subject, and Keywords.
20. **Sanitize & Scrub**: Strip all hidden author info, software history, and tracking metadata.
21. **Repair Corrupted PDF**: Rebuild damaged cross-reference tables and recover pages.

### 🎨 Group 4: Design & Stamps (22–27)
22. **Add Watermark**: Apply diagonal or horizontal text watermarks with custom opacity.
23. **Add Page Numbers**: Insert customizable page numbers (`Page {n} of {total}`) in headers or footers.
24. **Bates Legal Stamping**: Sequential Bates identification codes (e.g. `DOC-0001`, `DOC-0002`) across pages.
25. **Add Binding Margins**: Add left or right padding for hole-punching and comb binding.
26. **Header & Footer**: Add customized running header and footer text across all pages.
27. **Stamp Document**: Apply CONFIDENTIAL, APPROVED, DRAFT, COPY, or custom status stamps.
28. **Sign PDF**: Draw your digital signature with your finger and place it on any page.

### ⚡ Group 5: Optimize & Color (29–33)
29. **Compress PDF**: Reduce file size via on-device image downsampling (Light, Balanced, Aggressive).
30. **Black & White (Grayscale)**: Convert colorful documents into monochrome to save print ink and size.
31. **Night Mode (Invert Colors)**: Invert background and text for comfortable night reading.
32. **Delete Specific Pages**: Remove designated pages by entering page numbers.
33. **Document Statistics**: Detailed analytics of total pages, word count, character count, embedded images, and PDF version.

### 📖 Group 6: Read & Inspect (34–36)
34. **View & Read PDF**: Fast, hardware-accelerated offline document reader with zoom and paging.
35. **Bookmarks / Outline**: Inspect and navigate document chapter bookmarks.
36. **Compare 2 PDFs**: Side-by-side structural comparison of two PDF files.
