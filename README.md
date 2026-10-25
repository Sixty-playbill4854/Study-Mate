# MK StudyMate
Native Android (Kotlin + Jetpack Compose + Room). minSdk 26, targetSdk 34.

## Build (needs JDK 17, Android SDK 34, Gradle 8.9+)
    gradle wrapper --gradle-version 8.9
    ./gradlew assembleDebug assembleRelease
APKs: app/build/outputs/apk/debug/app-debug.apk, app/build/outputs/apk/release/app-release.apk

## Build without installing anything
Push this folder to a GitHub repo -> Actions tab -> "Build APK" -> download artifact "MKStudyMate-apk".

## Install
Copy the APK to the phone, open it, allow "Install unknown apps". Or: adb install app-debug.apk
