package eu.hxreborn.biometricapplock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import eu.hxreborn.biometricapplock.App
import eu.hxreborn.biometricapplock.prefs.Prefs
import java.security.MessageDigest
import kotlin.concurrent.thread

private const val LOCK_CMD_TAG = "BiometricAppLock"
private const val LOCK_CMD_LOCK = "eu.hxreborn.biometricapplock.action.LOCK"
private const val LOCK_CMD_UNLOCK = "eu.hxreborn.biometricapplock.action.UNLOCK"

// Any app can send these broadcasts, so this token is the only protection.
// Change it and never publish the real value in a public repository.
private const val LOCK_CMD_TOKEN = "RQm3GotdXBA9bgN0"

/**
 * Extras:
 *  - token:    secret token (required, must match LOCK_CMD_TOKEN)
 *  - pkg:      package name (required)
 *  - activity: one or more activities separated by comma (optional).
 *              Names starting with "." are expanded with the package name.
 *              Without it, the whole app is locked/unlocked.
 *  - user:     user id (optional, default 0)
 *
 * Example:
 *  am broadcast -a eu.hxreborn.biometricapplock.action.LOCK \
 *      --es token "<token>" \
 *      --es pkg com.example.app \
 *      --es activity ".MainActivity,.SettingsActivity,com.example.app.other.FooActivity"
 */
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
        if (!isValidToken(intent.getStringExtra("token"))) return ignore("invalid token")
        val pkg = intent.getStringExtra("pkg")?.trim().orEmpty()
        if (!isValidPackage(pkg)) return ignore("invalid pkg '$pkg'")
        val user = intent.userId() ?: return ignore("invalid user")

        // empty set = whole app. If the extra was given but nothing valid came out of it,
        // abort instead of silently falling back to locking/unlocking the whole app.
        val rawActivity = intent.getStringExtra("activity")
        val requested = parseActivities(pkg, rawActivity)
        if (!rawActivity.isNullOrBlank() && requested.isEmpty()) {
            return ignore("no valid activity in '$rawActivity'")
        }

        val pending = goAsync()
        val app = App.from(context)
        thread {
            try {
                // on a cold start the Xposed service may not be bound yet
                app.awaitService(3_000)
                handle(context, app, lock, pkg, user, requested)
            } catch (t: Throwable) {
                Log.w(LOCK_CMD_TAG, "lock command failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private fun parseActivities(
        pkg: String,
        raw: String?,
    ): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        val result = linkedSetOf<String>()
        for (part in raw.split(',')) {
            val name = part.trim()
            if (name.isEmpty()) continue
            val full = if (name.startsWith(".")) pkg + name else name
            if (isValidClass(full)) result += full else ignore("invalid activity '$name'")
        }
        return result
    }

    private fun handle(
        context: Context,
        app: App,
        lock: Boolean,
        pkg: String,
        user: Int,
        requested: Set<String>,
    ) {
        // The package manager of this process only sees user 0, so existence
        // can only be verified there.
        var activities = requested
        if (user == 0) {
            val declared = declaredActivities(context, pkg) ?: return ignore("not installed: $pkg")
            if (activities.isNotEmpty()) {
                val missing = activities.filterNot { it in declared }
                missing.forEach { ignore("$pkg has no activity $it") }
                activities = activities - missing.toSet()
                if (activities.isEmpty()) return ignore("none of the activities exist in $pkg")
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

        if (activities.isEmpty()) {
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
            overrides.setListedActivities(key, current.listedActivities + activities)
            saveLocked(locked + key)
        } else {
            if (!current.lockListedActivities) {
                return ignore("$key has no locked activity list")
            }
            // unlocking only makes sense for activities that are currently in the locked list
            val toRemove = activities.filter { it in current.listedActivities }.toSet()
            (activities - toRemove).forEach { ignore("$it is not in the locked list of $key") }
            if (toRemove.isEmpty()) return

            val remaining = current.listedActivities - toRemove
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

    private fun isValidToken(given: String?): Boolean =
        given != null &&
            MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), LOCK_CMD_TOKEN.toByteArray(Charsets.UTF_8))

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
