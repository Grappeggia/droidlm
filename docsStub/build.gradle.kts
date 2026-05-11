plugins {
    id("com.android.application")
}

android {
    namespace = "com.google.android.apps.docs.editors.docs"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.google.android.apps.docs.editors.docs"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "e2e-stub"
    }
}
