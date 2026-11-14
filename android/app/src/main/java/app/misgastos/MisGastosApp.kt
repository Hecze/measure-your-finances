package app.misgastos

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.misgastos.core.Api
import app.misgastos.core.Secrets
import app.misgastos.core.Storage
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class MisGastosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        scheduleSync(this)
    }

    companion object {
        @Volatile private var api: Api? = null

        /** El motor es único para toda la app (actividad y tareas en segundo plano). */
        fun api(ctx: Context): Api = api ?: synchronized(this) {
            api ?: Api(FileStorage(File(ctx.filesDir, "data.json")), EncryptedSecrets(ctx.applicationContext), Locale.getDefault().language)
                .also { api = it }
        }

        /** Lee el correo cada 6 horas, solo con internet. Silencioso: no notifica nada. */
        fun scheduleSync(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

/** Guarda el estado en el almacenamiento privado de la app, de forma atómica. */
class FileStorage(private val file: File) : Storage {
    private val mails = app.misgastos.core.MailDir(File(file.parentFile, "mail"))
    override fun saveMail(id: String, html: String) = mails.save(id, html)
    override fun loadMail(id: String): String? = mails.load(id)
    override fun hasMail(id: String): Boolean = mails.has(id)
    override fun load(): String? = if (file.exists()) file.readText() else null
    override fun save(json: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

/** Contraseña de Gmail y API key de IA, cifradas con el Keystore de Android. */
class EncryptedSecrets(ctx: Context) : Secrets {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        "secrets",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        ctx,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun get(name: String): String? = prefs.getString(name, null)
    override fun set(name: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(name) else putString(name, value) }.apply()
    }
}
