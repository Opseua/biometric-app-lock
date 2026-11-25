package eu.hxreborn.biometricapplock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import eu.hxreborn.biometricapplock.App
import eu.hxreborn.biometricapplock.prefs.Prefs

class LockCommandReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pkg = intent.getStringExtra("pkg")?.trim().orEmpty()
        if (pkg.isEmpty()) return
        val entry = "$pkg:${intent.getIntExtra("user", 0)}"
        val repo = (context.applicationContext as App).prefsRepository
        val current = Prefs.parseLockedPackages(repo.read(Prefs.LOCKED_PACKAGES))
        val updated =
            when (intent.action) {
                "eu.hxreborn.biometricapplock.action.LOCK" -> current + entry
                "eu.hxreborn.biometricapplock.action.UNLOCK" -> current - entry
                else -> return
            }
        repo.save(Prefs.LOCKED_PACKAGES, Prefs.serializeLockedPackages(updated))
    }
}
