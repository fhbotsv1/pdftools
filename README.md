# PDF Tools

An Android PDF utility application built with Kotlin and Jetpack Compose.

## App sections

- **Favorites** — quick access to starred PDF tools
- **Create** — Images to PDF, Text to PDF, Web to PDF, Word to PDF
- **Edit** — Merge PDF, Split PDF, Reorder / Rotate Pages, Cut Page, Compress PDF, Extract Images, PDF to Text
- **Annotate** — Add Watermark, Add Page Number
- **Secure** — Add Password, Remove Password

## Technical stack

- Kotlin
- Jetpack Compose
- Material 3
- AndroidX
- Minimum Android version: Android 8.0 (API 26)
- Compile SDK: 36
- JDK: 17
- Target ABI: arm64-v8a

## Development approach

The app is developed incrementally. The dashboard and navigation are established first. Each PDF operation will then be implemented and tested independently with real PDF files. File access will use Android's Storage Access Framework so users choose the documents they want to process without broad storage permissions.

## Security and privacy

PDF processing should happen on-device wherever practical. Password removal will only be supported when the user can provide the document's existing password or otherwise has legitimate access. The app will not attempt to bypass unknown passwords or encryption.
