package eu.hxreborn.biometricapplock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import eu.hxreborn.biometricapplock.App
import eu.hxreborn.biometricapplock.prefs.Prefs
import kotlin.concurrent.thread

private const val LOCK_CMD_TAG = "BiometricAppLock"
private const val LOCK_CMD_LOCK = "eu.hxreborn.biometricapplock.action.LOCK"
private const val LOCK_CMD_UNLOCK = "eu.hxreborn.biometricapplock.action.UNLOCK"

class LockCommandReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val lock =
            when (intent.action) {
                LOCK_CMD_LOCK -> true
                LOCK_CMD_UNLOCK -> false
                else -> return
            }
        val pkg = intent.getStringExtra("pkg")?.trim().orEmpty()
        if (!isValidPackage(pkg)) return ignore("invalid pkg '$pkg'")
        val user = intent.userId() ?: return ignore("invalid user")
        val rawActivity = intent.getStringExtra("activity")?.trim()?.takeIf { it.isNotEmpty() }
        val activity = if (rawActivity?.startsWith(".") == true) pkg + rawActivity else rawActivity
        if (activity != null && !isValidClass(activity)) return ignore("invalid activity")

        val pending = goAsync()
        val app = App.from(context)
        thread {
            try {
                // on a cold start the Xposed service may not be bound yet
                app.awaitService(3_000)
                handle(context, app, lock, pkg, user, activity)
            } catch (t: Throwable) {
                Log.w(LOCK_CMD_TAG, "lock command failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handle(
        context: Context,
        app: App,
        lock: Boolean,
        pkg: String,
        user: Int,
        activity: String?,
    ) {
        if (lock && user == 0) {
            val declared = declaredActivities(context, pkg) ?: return ignore("not installed: $pkg")
            if (activity != null && activity !in declared) {
                return ignore("$pkg has no activity $activity")
            }
        }
        val key = "$pkg:$user"
        val prefs = app.prefsRepository
        val overrides = app.appOverridesRepository
        val locked = Prefs.parseLockedPackages(prefs.read(Prefs.LOCKED_PACKAGES))
        val current = overrides.current(key)

        fun saveLocked(packages: Set<String>) =
            prefs.save(Prefs.LOCKED_PACKAGES, Prefs.serializeLockedPackages(packages))

        fun clearActivities() {
            overrides.setListedActivities(key, emptySet())
            overrides.setLockListedActivities(key, false)
        }

        if (activity == null) {
            if (lock) {
                if (current.lockListedActivities) clearActivities()
                saveLocked(locked + key)
            } else {
                saveLocked(locked - key)
                if (current.lockListedActivities) clearActivities()
            }
            return
        }

        if (lock) {
            if (!current.lockListedActivities && current.listedActivities.isNotEmpty()) {
                return ignore("$key uses 'allow checked' activities, clear them in the app first")
            }
            overrides.setLockListedActivities(key, true)
            overrides.setListedActivities(key, current.listedActivities + activity)
            saveLocked(locked + key)
        } else {
            if (!current.lockListedActivities || activity !in current.listedActivities) {
                return ignore("$activity is not in the locked list of $key")
            }
            val remaining = current.listedActivities - activity
            if (remaining.isEmpty()) {
                saveLocked(locked - key)
                clearActivities()
            } else {
                overrides.setListedActivities(key, remaining)
            }
        }
    }

    private fun declaredActivities(
        context: Context,
        pkg: String,
    ): Set<String>? =
        try {
            val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong())
            context.packageManager
                .getPackageInfo(pkg, flags)
                .activities
                ?.mapTo(mutableSetOf()) { it.name }
                .orEmpty()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    private fun Intent.userId(): Int? {
        if (!hasExtra("user")) return 0
        val asInt = getIntExtra("user", -1)
        if (asInt >= 0) return asInt
        return getStringExtra("user")?.toIntOrNull()?.takeIf { it >= 0 }
    }

    private fun isValidPackage(name: String) =
        '.' in name &&
            name.first().isLetter() &&
            name.all { it.isLetterOrDigit() || it == '.' || it == '_' }

    private fun isValidClass(name: String) =
        !name.startsWith('.') &&
            !name.endsWith('.') &&
            name.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '$' }

    private fun ignore(reason: String) {
        Log.w(LOCK_CMD_TAG, "lock command ignored: $reason")
    }
}
