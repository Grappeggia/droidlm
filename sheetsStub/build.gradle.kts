plugins {
    id("com.android.application")
}

android {
    namespace = "com.google.android.apps.docs.editors.sheets"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.google.android.apps.docs.editors.sheets"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "e2e-stub"
    }
}
