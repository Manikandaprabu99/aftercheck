# AfterCheck release rules. Room, WorkManager, DataStore and Compose ship their own consumer rules.

# WorkManager instantiates workers reflectively by class name.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Keep line numbers for readable crash traces (the APK is open source anyway).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
