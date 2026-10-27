# Connecting PDF Scan to Google Drive

PDF Scan can upload each document to your Google Drive as a searchable PDF plus its text
transcript. The APK shared on GitHub doesn't include a Google sign-in key, so Drive is hidden
there. To turn it on, you make your own free key (an "OAuth client") in Google Cloud and
build the app with it. This takes about 15 minutes, once.

What the app can see in your Drive: only the files it created itself (the `drive.file`
permission). It cannot read anything else in your Drive.

---

## What you need

- A Google account
- A computer with this repository and the Android build tools (see "Build" in the README)

---

## Step 1: Create a Google Cloud project

1. Open the [Google Cloud console](https://console.cloud.google.com/) and sign in.
2. At the top, click the project picker and choose **New project**.
3. Name it `PDF Scan` and click **Create**. Make sure the new project is selected at the top.

## Step 2: Turn on the Google Drive API

1. Open [APIs & Services › Library](https://console.cloud.google.com/apis/library).
2. Search for **Google Drive API**, open it and click **Enable**.

## Step 3: Set up the sign-in screen (Google Auth Platform)

1. Open [Google Auth Platform](https://console.cloud.google.com/auth/overview) and click
   **Get started** if it asks.
2. **App information**: app name `PDF Scan`, and your email as the support email.
3. **Audience**: choose **External**.
4. **Contact information**: your email. Agree to the policy and click **Create**.
5. Go to **Data access** › **Add or remove scopes**. Add these three, then **Update** and **Save**:
   - `openid`
   - `email` (`.../auth/userinfo.email`, shows which account is connected)
   - `.../auth/drive.file` (the files this app creates)
6. Go to **Audience** › **Test users** › **Add users** and add your Gmail address, plus the
   address of anyone else who will connect Drive with your build.

> Older consoles call this the "OAuth consent screen". The steps are the same.

## Step 4: Create the client key

1. In Google Auth Platform, open **Clients** › **Create client**.
2. **Application type: Desktop app.** (Not Android: the app signs in through your browser and
   a short-lived local address, which is what desktop clients allow.)
3. Name it `PDF Scan` and click **Create**.
4. Copy the **Client ID** (ends in `.apps.googleusercontent.com`) and the **Client secret**
   (starts with `GOCSPX-`). You can also download them as JSON.

## Step 5: Give the key to the build

In the project folder (next to `settings.gradle`), open or create `local.properties` and add:

```
drive.clientId=1234567890-abcdefg.apps.googleusercontent.com
drive.clientSecret=GOCSPX-xxxxxxxxxxxxxxxxxxxx
```

`local.properties` is git-ignored, so the key never goes to GitHub.

For builds on GitHub Actions instead, add two repository secrets (**Settings › Secrets and
variables › Actions**) named `DRIVE_CLIENT_ID` and `DRIVE_CLIENT_SECRET`.

## Step 6: Build and install

```sh
./gradlew assembleRelease
```

Install `app/build/outputs/apk/release/app-universal-release.apk`. (Release builds need your
signing key in `keystore.properties`; see "Release build and signing" in the README.)

## Step 7: Connect in the app

1. Open **Settings** › **Internet and Google Drive**. This section only says "Google Drive"
   in builds that have the key.
2. Turn on **Allow internet access**.
3. Tap **Connect Google Drive**. Your browser opens Google's sign-in page.
4. Choose your account. If Google says the app isn't verified, tap **Advanced** ›
   **Go to PDF Scan**. That's expected for a personal app.
5. Allow access. The browser shows "Signed in. You can go back to the app."; switch back to PDF Scan.

Settings now shows the connected account. Choose:

- **Upload documents automatically**: every document is uploaded and kept up to date when it changes.
- **Only on Wi-Fi or unmetered networks**: waits for Wi-Fi before uploading.
- **Upload all now**: uploads everything straight away.

Or upload one document from its menu: **More › Upload to Google Drive now**, or tick
**Also upload to Google Drive** in the Export sheet.

Files go to a folder called **PDF Scan** in your Drive: `Title.pdf` and `Title.txt` for
each document.

---

## Keeping it signed in

While the app is in **Testing** (step 3), Google signs you out after **7 days** and you have to
tap Connect again. To stop that:

1. Google Auth Platform › **Audience** › **Publish app** › **Confirm**.
2. Because `drive.file` is a non-sensitive permission, Google doesn't require a review. People
   signing in may still see the "not verified" notice from step 7; they can continue the same way.

## Sharing your Drive-enabled build

Anyone who installs your build signs in with their own Google account and uploads to their own
Drive. While the app is in Testing, add each person as a test user (step 3, up to 100 people).
After publishing, anyone can connect.

## Disconnecting

Settings › **Disconnect**. This revokes the app's access at Google and deletes the sign-in from
the phone. Files already in Drive stay there. You can also remove access from your Google
account at [myaccount.google.com/permissions](https://myaccount.google.com/permissions).

---

## Troubleshooting

| What you see | What to do |
|---|---|
| No Google Drive option in Settings | The build has no key. Check `local.properties` (step 5) and rebuild. |
| "Internet access is off" | Settings › turn on **Allow internet access**. |
| `access_denied` or "app is in testing" | Add your Gmail as a test user (step 3.6), or publish the app. |
| `invalid_client` | The client ID or secret was copied wrongly, or the client isn't a **Desktop app**. |
| `redirect_uri_mismatch` | The client type is wrong: create a **Desktop app** client (step 4). |
| The browser doesn't return to the app | Switch back to PDF Scan yourself; the sign-in finishes in the background. If it timed out, tap Connect again. |
| Signed out after a week | Publish the app (see "Keeping it signed in"). |
| Upload fails | Check the connection and Wi-Fi-only setting. Settings › Diagnostics › Share report shows the error. |

## Privacy

- The sign-in happens in your browser; the app never sees your Google password.
- The long-term sign-in token is encrypted with a key kept in the phone's Android Keystore.
- With **Allow internet access** off, the app makes no connections at all, Drive included.
- Deleting a document in the app doesn't delete its copy in Drive.
