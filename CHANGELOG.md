# Changelog

All notable changes to **Offline PDF Studio** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [2.0.0] - 2026-11-17

### Added
- **36 Comprehensive Offline PDF Tools**:
  - **Organize & Edit**: Merge, Split, Reorder & Delete, Rotate Pages, Reverse Order, Duplicate Pages, Insert Blank Pages, Crop Margins, 2-Up Sheet, Flatten Forms & Annotations.
  - **Convert & Export**: Images to PDF, PDF to Images, Extract Embedded Images, Extract Text, Text to PDF.
  - **Security & Privacy**: 128-bit Password Encryption, Password Unlock/Decrypt, Sensitive Data Redaction, Metadata Editor, Privacy Sanitizer, Corrupted PDF Repair.
  - **Design & Branding**: Custom Watermark, Page Numbering, Legal Bates Stamping, Binding Margin Padding, Running Header & Footer, Document Stamps, Finger Signature Canvas.
  - **Optimize & Color**: 3-Level PDF Compression, B&W Grayscale Converter, Night Mode Inverter, Page Deleter, Comprehensive Document Analytics.
  - **Read & Inspect**: High-speed Native PDF Viewer, Bookmarks / Table of Contents Navigator, Dual PDF Comparator.
- **1-Click Direct Download**:
  - Direct on-device download to Android's public `Downloads/OfflinePDFStudio/` folder via `MediaStore.Downloads` API (zero permissions needed).
  - Storage Access Framework `CreateDocument` for custom folder saving.
  - Batch "Direct Download All" for multi-file operations.
- **Enhanced Compose UI**:
  - Real-time search across all 36 tools.
  - Category filter chips (All, Organize, Convert, Security, Design, Optimize, Read).
  - Material 3 dynamic styling with progress indicators.

### Changed
- Bumped Android `versionCode` to `2` and `versionName` to `"2.0.0"`.
- Upgraded GitHub Actions build pipeline to automatically publish official GitHub Releases with attached APK and AAB assets when Git tags are pushed.

---

## [1.0.0] - 2026-11-12

### Added
- Initial project scaffold targeting Android 16 (API 36).
- Core 5-tool engine: Merge, Split, Reorder, Compress, Viewer.
- Apache PDFBox-Android integration with streaming page-by-page memory safety.
- Zero network permissions security policy.
