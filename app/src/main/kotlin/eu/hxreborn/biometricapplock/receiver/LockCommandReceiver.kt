diff --git a/app/src/main/AndroidManifest.xml b/app/src/main/AndroidManifest.xml
index 2065045..aab949c 100644
--- a/app/src/main/AndroidManifest.xml
+++ b/app/src/main/AndroidManifest.xml
@@ -70,6 +70,16 @@
             </intent-filter>
         </receiver>
 
+        <receiver
+            android:name=".receiver.LockCommandReceiver"
+            android:exported="true"
+            android:permission="android.permission.DUMP">
+            <intent-filter>
+                <action android:name="eu.hxreborn.biometricapplock.action.LOCK" />
+                <action android:name="eu.hxreborn.biometricapplock.action.UNLOCK" />
+            </intent-filter>
+        </receiver>
+
         <provider
             android:name="androidx.core.content.FileProvider"
             android:authorities="${applicationId}.fileprovider"
diff --git a/app/src/main/kotlin/eu/hxreborn/biometricapplock/receiver/LockCommandReceiver.kt b/app/src/main/kotlin/eu/hxreborn/biometricapplock/receiver/LockCommandReceiver.kt
new file mode 100644
index 0000000..bac48ec
--- /dev/null
+++ b/app/src/main/kotlin/eu/hxreborn/biometricapplock/receiver/LockCommandReceiver.kt
@@ -0,0 +1,27 @@
+package eu.hxreborn.biometricapplock.receiver
+
+import android.content.BroadcastReceiver
+import android.content.Context
+import android.content.Intent
+import eu.hxreborn.biometricapplock.App
+import eu.hxreborn.biometricapplock.prefs.Prefs
+
+class LockCommandReceiver : BroadcastReceiver() {
+    override fun onReceive(
+        context: Context,
+        intent: Intent,
+    ) {
+        val pkg = intent.getStringExtra("pkg")?.trim().orEmpty()
+        if (pkg.isEmpty()) return
+        val entry = "$pkg:${intent.getIntExtra("user", 0)}"
+        val repo = (context.applicationContext as App).prefsRepository
+        val current = Prefs.parseLockedPackages(repo.read(Prefs.LOCKED_PACKAGES))
+        val updated =
+            when (intent.action) {
+                "eu.hxreborn.biometricapplock.action.LOCK" -> current + entry
+                "eu.hxreborn.biometricapplock.action.UNLOCK" -> current - entry
+                else -> return
+            }
+        repo.save(Prefs.LOCKED_PACKAGES, Prefs.serializeLockedPackages(updated))
+    }
+}
