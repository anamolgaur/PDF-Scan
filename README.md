# PDF Scan

A private document scanner for Android. Scanning, editing, text recognition and search all
run on the phone. No ads, no telemetry, no account. Internet access is off by default and is
used only if you choose to link Google Drive.

## Features

**Capture**
- A camera laid out like a phone camera app, with two modes, PHOTO and SCAN.
- Photo mode is manual: tap to focus and meter on any spot, an exposure slider (EV, remembered
  between sessions; tap the value to reset), and flash off / auto / on / torch.
- Scan mode adds live page detection that only accepts a real sheet of paper: a solid,
  four-cornered, roughly rectangular light shape that stands out from what it lies on. With
  Auto on it captures once the page has held steady for about a second, then waits at least
  five seconds, and for the page to change, before the next capture.
- Each shot goes straight into the document as a plain photo; crop, filters and mark-up come
  afterwards in the page preview. "Straighten and filter while capturing" in Settings does that
  as the page is taken instead.
- Torch toggle. The phone's own camera app is still available as a fallback.

**Books**
- Page detection also works by outline, so a white page on a white desk, a bedsheet or under
  uneven light is found, not only a light page on a dark surface.
- Page preview › More › "Split book spread into two pages": finds the fold from the shadow
  along the spine and makes two pages from one photo.
- Adjust › Book page: a Curve slider (with which side the spine is on) flattens text lines
  that bend near the binding.

**Edit an existing PDF**
- Open a PDF (from "On this phone", or "Open with PDF Scan" from another app) and tap the pen
  icon. Its pages open in the same editor as scans: crop, filters, brightness and contrast,
  clean up, mark-up, signatures, rotate, reorder, delete, add pages. Its own text is kept.
- The document's More menu then has "Save over …" to replace the original PDF (the previous
  version stays in the recycle bin for 30 days), or export a new copy as usual.
- Pages you only mark up, sign, turn, reorder, copy or remove are saved over the original's own
  pages: the mark-up is laid on top and the original text, drawings and fonts stay exactly as
  they were (sharp and selectable). The original file's bytes are kept and the changes are
  appended, the way desktop PDF editors save.
- Crop, filters, brightness, clean up or book-curve correction can only be shown as a picture,
  so when any page uses them the PDF is written as a searchable scan-style PDF instead.

**Appearance and privacy**
- Settings › Appearance: Maroon, or AMOLED black (true black with maroon and cream text, easy
  on OLED screens at night). The camera keeps its own dark look.
- Settings › Privacy › "Lock the app": fingerprint, face or the phone's PIN/pattern when the app
  is opened or comes back from the background.
- Lock single folders: long-press a folder chip › Lock. Its documents stay hidden until you
  unlock it; it locks again when you leave the app.
- "Hide from recent apps and screenshots" blanks the app in the recent-apps view.

**More camera modes**
- ID CARD: photograph the front, then the back; both are straightened and placed at real size
  on one A4 page, ready to print.
- QR: reads QR codes and barcodes on the phone, no internet. Links, Wi-Fi details, UPI payment
  codes and contacts get the matching button (Open link, Open in a UPI app...), plus Copy and Share.

**Working with many pages**
- Long-press a page and drag it to reorder.
- Tap "Select pages" (or long-press then tap others) to rotate, filter, move, save as pictures,
  delete, copy to a new document, share or print several pages at once. "Apply a filter to
  every page" is in the document's More menu.
- Print any document or selection through Android's print screen.
- "Save pages as pictures" writes JPGs to a folder you choose.
- Combine scans: long-press a document › "Combine with other scans…".
- Settings › Library › Original photos: shows the space they use, can remove them automatically
  after 30 or 90 days, or "Free up space now". Edits already made are kept in the pages.

**Page preview**
- Tap a page to see it full screen as it will appear in the PDF. Swipe between pages, pinch or
  double-tap to zoom, drag around a zoomed page.
- Toolbar on every page: Crop, Filters (applied on the spot), Adjust, Clean up, Mark up, Sign,
  Rotate, Delete. More: back to the original photo, copy the page's text.
- After taking photos the preview opens on the first new one.

**Edit a page** (Crop · Adjust · Clean up · Mark up)
- Crop: automatic edges with draggable corners, perspective correction, rotate.
- Adjust: filters (Original, Auto colour, Greyscale, Scan, B&W), glare reduction,
  brightness, contrast and sharpening, with a live preview.
- Clean up: an eraser brush that paints over smudges, stains or marks with the surrounding
  paper colour.
- Mark up: pen, highlighter, box, circle, arrow in three colours and three widths, and
  signatures. Draw a signature once, keep it on the phone, then place it on any page; drag to
  move and pinch to resize.
- Each page's original photo is kept, so every edit can be changed later without losing quality.

**Home screen**
- Two sections: Scans (documents made in the app) and On this phone (documents found on the
  device), with counts. On this phone can be filtered by type (PDF, Word, Excel, PowerPoint,
  Text) and shows folder, date and size; long-press for Open, View text, or Copy into Scans.
- Documents on the phone show their cover: the first page of a PDF (a book cover, a lecture's
  title slide), or the preview picture or first picture inside Word, PowerPoint, Excel and
  OpenDocument files. Each row has its own "Filetype:" line.
- Search filters the open section.

**Organise**
- Folders: long-press a scan › Move to folder. Folder chips on the Scans tab filter the list.
- Pin to top, and sort by recent, name, page count or size.
- Recycle bin: deleted documents and pages stay for 30 days and can be put back (a restored
  page returns to its place in its document).
- Back up the whole library (pages, originals, text, folders, signatures) to one zip file, and
  restore it later; restoring adds documents and never replaces anything.
- Share into the app: choose PDF Scan in any app's share menu (WhatsApp, Gmail, gallery, file
  manager) or "Open with" for PDFs. Share out: a document's More › Share PDF.
- Import PDFs and images, several at once. A PDF's own text is kept, so it is searchable
  straight away without text recognition.
- Reorder, rotate, delete, split a document at any page, copy a page to a new document, merge.

**Text and search**
- On-device text recognition (Tesseract OCR). English is built in. Settings › Text › Choose
  languages offers one-time downloads (about 1-4 MB each) that then work offline:
  - Indian: Hindi, Marathi (also for Konkani), Bengali, Tamil, Telugu, Kannada, Malayalam,
    Gujarati, Punjabi, Odia, Assamese, Urdu, Nepali, Sanskrit
  - Chinese (Simplified and Traditional), Japanese, Korean
  - European: French, German, Spanish, Italian, Portuguese, Dutch, Swedish, Norwegian, Danish,
    Finnish, Polish, Czech, Slovak, Hungarian, Romanian, Croatian, Greek, Russian, Ukrainian,
    Bulgarian, Turkish
- A download asks first and works even with "Allow internet access" off, for that one file.
- Searchable PDFs keep text in every script (an invisible Unicode text layer), and search
  handles Chinese and Japanese, which are written without spaces.
- A document's More › "Recognise text again" re-reads older pages after adding a language.
- Every document keeps a plain-text transcript that updates automatically.
- Library search across titles and the text of every page, with page numbers and snippets.

**Search the whole phone (optional)**
- Switch on "Make documents on this phone searchable" in Settings. After you allow file
  access, the app finds every PDF, Word, Excel, PowerPoint, OpenDocument, RTF, text and CSV
  file on the phone and SD card and indexes its text.
- Runs in the background with a progress notification (with a Stop button) until it is done.
  After that, new and changed documents (a download, a saved attachment) are indexed within a
  couple of minutes, even when the app isn't open. Scanned PDFs without a text layer go through
  on-device text recognition (first 30 pages).
- Library search shows them under "On this phone" with page, slide or sheet numbers and a
  snippet, each with its cover and file type.
- Tapping one opens it inside the app: PDFs page by page (swipe, pinch to zoom, jump to the
  page that matched), Word, Excel, PowerPoint and text files as their text. "Open in another
  app" is in the menu.
- Long-press (or the reader's menu) › "Delete from phone" deletes the file. A copy stays in the
  recycle bin for 30 days and can be put back where it was.
- The text index is a private SQLite full-text database. Files are only read, never changed or
  uploaded; a file is deleted only when you choose Delete.

**Background music**
- Sixteen original tracks, shipped as MP3 files (`app/src/main/assets/music`, about 6 MB),
  played in a fixed order as one journey that slows down and darkens into gothic castle music,
  then leads back to the start:
  1. Crimson Index · 2. Lantern Hall · 3. Moonlit Corridor · 4. Clocktower Waltz ·
     5. Cathedral of Ash · 6. Bloodmoon Gate · 7. Night Stair (8-bit)
  8. Moonlit Corridor (harpsichord) · 9. Clocktower Waltz (harpsichord) · 10. Crypt of Bells ·
     11. Requiem of Candles · 12. Cathedral of Ash (pipe organ) · 13. Masquerade of Thorns ·
     14. Night Stair (gothic) · 15. Raven Spire (gothic)
  16. Ashen Dawn (8-bit epilogue)
- Each loops seamlessly and tracks crossfade into each other. Settings › Music › "Save the
  MP3s to a folder" copies them to your phone.
- The music is composed as code (`Soundtrack`, `GothicClassics`, `GothicNocturnes`,
  `ChiptuneSynth`) and rendered to the MP3s with `tools/make-music.sh`.
- Sounds: 12.5/25/50% pulse waves, triangle bass, noise drums, fast arpeggio chords, a plucked
  pulse and echo for the 8-bit tracks; an additive pipe organ, a plucked-string harpsichord, an
  FM bell, timpani and reverb for the gothic ones.
- Continues from the same track and the same spot every time the app is opened, and counts
  the opens. Keeps playing while you use the camera, pick files, export or open a document in
  another app; pauses when you leave the app (unless "Keep playing after leaving the app" is on)
  and for calls or other apps' audio.
- Media notification and lock-screen controls (previous, play/pause, next, stop).
- Settings: on/off, keep playing after leaving, play the whole journey or repeat one track,
  track list, volume.

**Export**
- PDF + transcript to a folder (`Title.pdf` and `Title.txt` side by side), PDF only, or
  transcript only.
- Searchable PDFs: a real invisible text layer, so text can be selected and searched in any reader.
- Size presets: High quality, Balanced, Small (black-and-white scans become crisp 1-bit images).
- Password protection with AES-256 (PDF 2.0 standard security, revision 6).

**Google Drive (optional, needs your own setup)**
- Only in builds that include a Google Drive client ID (see "Google Drive setup"). The shared
  APK doesn't, so the Drive option is simply not shown there.
- Turn on "Allow internet access" in Settings, then "Connect Google Drive".
- Each document uploads to a "PDF Scan" folder as a searchable PDF plus its transcript, and is
  updated in place when it changes. Upload automatically or on demand, optionally only on Wi-Fi.
- The app asks only for the `drive.file` scope, so it can see just the files it created.

**Diagnostics**
- Crashes and failed actions are written to a private log on the phone (never sent anywhere).
  Settings › Diagnostics › Share report sends it when you choose to.

## Privacy

- Permissions: camera (asked the first time you scan); internet and network state (used only
  for Google Drive and for downloading extra text-recognition languages when you tap Download); all-files access and notifications (asked only if you turn on phone-wide
  search); foreground service for that background indexing.
- While "Allow internet access" is off, which is the default, the app makes no network
  connections. Network code is in `DriveClient` and `Languages`, which check that setting before
  every request. Cleartext HTTP is disabled.
- Google sign-in happens in your browser. The long-term token is encrypted with a key held in
  the Android Keystore. Disconnecting revokes it at Google and deletes it from the phone.
- Documents and signatures live in the app's private storage, and Android backups are disabled.

## Requirements

- Android 10 (API 29) or newer, on an ARM phone (arm64-v8a or armeabi-v7a).
- To build: JDK 17 or newer and the Android SDK (platform 34, build-tools 34).

## Build

```sh
./gradlew assembleDebug          # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :desktop-tests:test     # unit tests: detection, filters, PDF writer and reader, Office text, search, Drive, backup, music
./gradlew connectedDebugAndroidTest  # device tests on a connected phone or emulator (GitHub Actions runs them on Android 10 and 14)
```

Without Android Studio, point Gradle at the SDK with `export ANDROID_HOME=/path/to/sdk` or a
`local.properties` file containing `sdk.dir=/path/to/sdk`. That file is ignored by git.

Every push to `main` runs the tests and builds the APK on GitHub Actions.

## Google Drive setup

Full step-by-step guide, including sharing and troubleshooting: [DRIVE_SETUP.md](DRIVE_SETUP.md). In short:

The app builds and works without this; only the Drive link needs it. You need your own free
Google Cloud OAuth client:

1. In the [Google Cloud console](https://console.cloud.google.com/), create a project and
   enable the **Google Drive API**.
2. Configure the **OAuth consent screen** (External). Add the scopes `openid`, `email` and
   `.../auth/drive.file`. Add your Google account as a test user. While the app is in
   "Testing", Google expires sign-ins after 7 days; set the publishing status to
   "In production" to avoid that. Because `drive.file` is a non-sensitive scope, Google does
   not require an app review for it.
3. Under **Credentials**, create an **OAuth client ID** of type **Desktop app**. The app signs
   in through the browser and a loopback redirect to `127.0.0.1`, which is what desktop
   clients allow.
4. Put the values in `local.properties` (never committed):

   ```
   drive.clientId=1234567890-abc.apps.googleusercontent.com
   drive.clientSecret=GOCSPX-...
   ```

   Or set the environment variables `DRIVE_CLIENT_ID` and `DRIVE_CLIENT_SECRET`. For GitHub
   Actions builds, add them as repository secrets with those names.
5. Build, then in the app open Settings, turn on "Allow internet access", and tap
   "Connect Google Drive".

Google documents that a desktop client's "secret" can't be kept confidential inside an
installed app. Security comes from PKCE and the loopback redirect. Keep it out of the public
repository anyway.

## Release build and signing

```sh
./gradlew assembleRelease        # APKs in app/build/outputs/apk/release/
```

This gives `app-universal-release.apk`, which installs on every Android 10+ device (phones,
tablets, Chromebooks, emulators); share that one. There are also smaller per-processor APKs:
`arm64-v8a` (almost every phone from recent years), `armeabi-v7a` (older 32-bit phones),
`x86_64` and `x86`. Release builds are shrunk with R8.

They are signed with your own key. Put a `keystore.properties` file (git-ignored) next to
`settings.gradle`:

```
storeFile=/full/path/to/pdfscan-release.jks
storePassword=...
keyAlias=pdfscan
keyPassword=...
```

Without it, release APKs are unsigned and won't install. Keep the key file and password backed
up and out of the repository: every update must be signed with the same key, or Android
refuses to install it over the existing app.

## Installing

For family and friends: send them `PdfScan.apk` (the universal build) or a GitHub release link.
It needs Android 10 or newer.

Copy the APK to the phone and open it. Android will ask you to allow installs from that source.
A new version installs over the old one and keeps all documents.

To publish a release build, create your own signing key and add a `signingConfigs` block to
`app/build.gradle`. Never commit the keystore.

## How it works

Plain Java and the Android framework. No AndroidX UI libraries; layouts are built in code.

| File | Role |
|---|---|
| `MainActivity` | Library, search, import, status line |
| `ViewerActivity` | One document: pages, page menu, export sheet, transcript, Drive upload |
| `PagePreviewActivity`, `PageSwiper` | Full-screen page preview with swipe, zoom and the editing toolbar |
| `CameraActivity`, `CameraOverlay` | Camera2: photo and scan modes, tap to focus, exposure, flash, live detection, auto-capture |
| `PageEditorActivity`, `CropView`, `PreviewView`, `SignaturePad` | Crop, Adjust, Clean up, Mark up |
| `ScanMath` | Page detection (Otsu threshold, largest region, corner extremes) |
| `ImageOps` | Filters, glare reduction, brightness/contrast, sharpening, paper-colour fill |
| `ImageProcessor`, `Marks` | Decoding, perspective warp, page rendering, drawing mark-up |
| `PdfWriter` | Dependency-free PDF writer: JPEG and 1-bit pages, text layer, AES-256 |
| `PdfExport` | Size presets and OCR words for `PdfWriter` |
| `Ocr`, `Indexer`, `Transcript`, `SearchCore` | Text recognition, transcripts, full-text search |
| `DriveCore`, `DriveClient`, `DriveAccount`, `DriveSync` | OAuth with PKCE, Drive REST, token storage, uploads |
| `DocStore`, `PageMeta`, `Signatures`, `Prefs` | Storage, per-page settings, signatures, settings |
| `PdfText`, `Glyphs` | PDF text-layer extractor: object streams, filters, encryption, fonts, ToUnicode |
| `OfficeText` | Word, Excel, PowerPoint, OpenDocument, RTF, text; best effort for old .doc/.xls/.ppt |
| `PhoneFiles`, `FileIndexService`, `DeviceIndex`, `PhoneFileProvider`, `PhoneWatchJob` | Phone-wide index: access, background service, SQLite FTS4, opening results, live updates |
| `TrashActivity`, `Backup` | Recycle bin, library backup and restore |
| `ShareInActivity`, `ShareProvider` | Receiving shared files, sharing PDFs out |
| `Languages`, `Diag` | Optional OCR language downloads, private problem log |
| `Covers` | Cover pictures for phone documents (PDF first page, embedded Office previews) |
| `ChiptuneSynth`, `GothicSynth`, `Soundtrack`, `GothicClassics`, `GothicNocturnes` | The composer: tracks as code, rendered to MP3 by `tools/make-music.sh` |
| `Mp3Loops` | Decodes the bundled MP3s and cuts exact loops for playback |
| `DocReaderActivity` | Opens phone documents and shared PDFs inside the app |
| `LanguagesActivity` | Downloading and choosing text-recognition languages |
| `MusicService`, `MusicPlayer`, `PdfScanApp` | Background playback, crossfades, resume position, media notification, settings |
| `Ui`, `StatusBus` | Maroon-and-cream widgets, background status messages |

Storage layout in the app's private files directory:

```
docs/<id>/title.txt
docs/<id>/p<seq>.jpg          finished page (shown and exported)
docs/<id>/p<seq>.src          original photo, for re-editing
docs/<id>/p<seq>.json         crop, look, eraser strokes, mark-up, recognised words
docs/<id>/transcript.txt      text-only transcript of the whole document
docs/<id>/drive.json          Drive file ids, when uploaded
docs/<id>/info.json           folder and pin
trash/<id>/                   recycle bin: the document or page, and trash.json
signatures.json               saved signatures (vector strokes)
```

## Limitations

- Live detection needs the page's outline to be visible; a page that blends completely into
  its surface on several sides is not found.
- Book curve flattening is a simple correction from one slider, not a full 3D page model.
- Glare reduction recovers washed-out text, but not text that is completely blown out to white.
- Handwriting is not recognised well. Settings › Text › Choose languages › "High-accuracy
  English" downloads a larger model that does noticeably better on neat handwriting and poor
  photos (slower).
- Text kept from an imported PDF has no word positions; in an exported searchable PDF it is
  placed line by line down the page.
- Deleting a document in the app does not delete its copy in Google Drive.
- Phone-wide search reads files up to 150 MB (PDFs up to 60 MB). Password-protected PDFs are
  skipped. Old binary .doc/.xls/.ppt files are read on a best-effort basis.
- Android allows background work to be paused by battery savers; indexing resumes the next
  time the app is opened.

## License

PDF Scan is open source.

- **Code**: [Apache License 2.0](LICENSE). Copyright 2026 Anamol Gaur. You may use, change and
  share it, including in other apps, as long as you keep the copyright and license notices
  (see [NOTICE](NOTICE)) and mark files you change.
- **Music**: the sixteen soundtrack tracks in `app/src/main/assets/music`, and the compositions
  behind them, are [CC BY 4.0](LICENSE-MUSIC.txt). Reuse them anywhere with credit:
  *"Track title" from the PDF Scan soundtrack by Anamol Gaur, CC BY 4.0.*
- **Bundled third-party parts** (Tesseract, Leptonica, the English OCR model, DM Sans and Sora
  fonts) keep their own open-source licenses; see
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Contributions are welcome. Unless you say otherwise, anything you contribute is licensed under
the same terms (Apache 2.0 for code, CC BY 4.0 for music), as section 5 of the Apache License
describes.

The name "PDF Scan" and the app icon aren't covered by a trademark license; if you publish a
modified version, please give it a different name and package id.
