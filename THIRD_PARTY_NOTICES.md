# Third-party notices

PDF Scan's own code is licensed under the Apache License 2.0 ([LICENSE](LICENSE)) and its
music under CC BY 4.0 ([LICENSE-MUSIC.txt](LICENSE-MUSIC.txt)). The app also bundles the
open-source components below, each under its own license.

| Component | Used for | License |
|---|---|---|
| [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) 4.7.0 | Android wrapper around the OCR engine (Gradle dependency) | Apache License 2.0 |
| [Tesseract OCR](https://github.com/tesseract-ocr/tesseract) | On-device text recognition (native library inside Tesseract4Android) | Apache License 2.0 |
| [Leptonica](https://github.com/DanBloomberg/leptonica) | Image handling for Tesseract (native library inside Tesseract4Android) | BSD 2-Clause style ("Leptonica license") |
| libjpeg and libpng | Image codecs used by Leptonica (native libraries inside Tesseract4Android) | IJG license / libpng license |
| [tessdata_fast `eng.traineddata`](https://github.com/tesseract-ocr/tessdata_fast) | English recognition model, committed at `app/src/main/assets/tessdata/` | Apache License 2.0 |
| [ZXing core](https://github.com/zxing/zxing) 3.5.3 | QR code and barcode reading on the phone (Gradle dependency, pure Java) | Apache License 2.0 |
| [tessdata_best `eng.traineddata`](https://github.com/tesseract-ocr/tessdata_best) | Optional high-accuracy English model, downloaded on request, not bundled | Apache License 2.0 |
| tessdata_fast models for other languages | Downloaded on request from the same repository, not bundled | Apache License 2.0 |

The Apache License 2.0 text: https://www.apache.org/licenses/LICENSE-2.0

The Leptonica license: https://github.com/DanBloomberg/leptonica/blob/master/leptonica-license.txt

None of these components send data anywhere. The app's own PDF writer, image processing and
Google Drive client are written from scratch and use only the Android framework and the Java
standard library.

The background music (sixteen tracks, shipped as MP3s in `app/src/main/assets/music`) is original, written for this app and synthesised in code
(`ChiptuneSynth`, `GothicSynth`, `Soundtrack`, `GothicClassics`, `GothicNocturnes`). The gothic tracks are inspired by the style of
classic castle-crawler game music but do not reuse any existing melody. The test fixtures in `desktop-tests/src/test/resources/fixtures` were generated for
this project with LibreOffice, reportlab, python-docx, openpyxl, python-pptx and qpdf.

Fonts in `app/src/main/res/font`: DM Sans (Copyright 2014 The DM Sans Project Authors) and
Sora (Copyright 2019 The Sora Project Authors), both under the SIL Open Font License 1.1
(`third_party/OFL-DMSans.txt`, `third_party/OFL-Sora.txt`). The files are static, subsetted
instances of the variable fonts from github.com/google/fonts.
