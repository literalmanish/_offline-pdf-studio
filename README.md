# Offline PDF Studio (Native Android)

A 100% offline, privacy-first Android PDF manipulation studio built with Kotlin, Jetpack Compose, Material 3, `PdfRenderer`, and `PdfBox-Android`.

## Architecture & Features

- **Framework**: Kotlin 2.1+, Jetpack Compose with Material 3.
- **Rendering Engine**: Hardware-accelerated `android.graphics.pdf.PdfRenderer` (zero cloud dependencies, streaming page-by-page).
- **Manipulation Engine**: `com.tom-roush:pdfbox-android` (Apache PDFBox port for Android) running on `Dispatchers.IO`.
- **Target SDK**: API 36 (Android 16), Min SDK 26 (Android 8.0).
- **Privacy Guarantee**: **Zero network permissions** declared in `AndroidManifest.xml`. All processing stays strictly on-device.

### Core Tools Implemented

1. **Merge Documents**: Concatenate 2 or more PDF documents into one single file using streaming temp-file buffers.
2. **Split PDF**: Split documents into distinct chapters or parts using syntax like `1-3, 4-6`.
3. **Reorder & Delete Pages**: Visual grid of rendered low-res page thumbnails with rotation, reordering, and deletion toggles.
4. **Compress PDF**: On-device image downsampling for embedded high-resolution bitmaps with Low, Balanced, and Aggressive presets.
5. **PDF Viewer**: Native page-by-page viewer with prev/next navigation, also registers as an `ACTION_VIEW` handler for opening PDF files from other apps.

## Opening in Android Studio

1. Open Android Studio (Ladybug or newer).
2. Select **Open** and choose this directory: `c:\Users\MANISH KUMAR SAH\Downloads\new bakchodi`.
3. Allow Gradle to sync dependencies and Version Catalog (`gradle/libs.versions.toml`).
4. Run on an emulator or connected physical Android device (API 26+).
