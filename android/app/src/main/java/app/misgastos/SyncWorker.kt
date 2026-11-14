package app.misgastos

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Lectura periódica del correo en segundo plano (y luego IA, si está configurada). */
class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val api = MisGastosApp.api(applicationContext)
        if (!api.hasEmail()) return Result.success()
        return try {
            api.syncEmail()
            runCatching { api.aiRun() }
            Result.success()
        } catch (e: Exception) {
            if (e.message?.startsWith("auth") == true) Result.failure() else Result.retry()
        }
    }
}
