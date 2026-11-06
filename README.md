# BookCheef Android

Native Android application for the BookCheef platform.

## Current foundation

- Kotlin + Jetpack Compose + Material 3
- Email/password registration and sign-in through the same Supabase project as the website
- Catalog loading from the public `book_catalog` view
- Book content retrieval through the `get_book_content(target_book_id)` RPC
- Downloaded book JSON stored in the app's private files for offline reading
- GitHub Actions workflow builds a debug APK artifact

## Supabase configuration

Create `local.properties` in the repository root (do not commit it):

```properties
SUPABASE_URL=https://YOUR_PROJECT.supabase.co
SUPABASE_ANON_KEY=YOUR_PUBLIC_PUBLISHABLE_OR_ANON_KEY
```

Use the same Supabase project and public anon/publishable key as the BookCheef website. Never use a service-role or secret key in a mobile app.

For GitHub Actions, add repository Actions secrets named `SUPABASE_URL` and `SUPABASE_ANON_KEY`. The APK build can run without them, but the app cannot connect to Supabase until configured.

## Build

Open the project in Android Studio (JDK 17, Android SDK 35), sync Gradle, then run the `app` configuration.

The GitHub Actions workflow `.github/workflows/build-android.yml` builds the APK after each push to `main`, uploads it as an artifact, and publishes/replaces the latest APK at the GitHub Release tag `android-latest`. The stable download URL used by the website is `https://github.com/KDNMGames/BookCheef-Android/releases/latest/download/BookCheef.apk`.

## Important

This is the initial application foundation, not yet a feature-for-feature replacement of the website. Advanced reader formatting, interactive branches, collections, favorites, reading history, ratings, child mode, Pro access and promo-code flows still need to be implemented and tested. Offline downloads currently cache the protected book-content response as JSON.
