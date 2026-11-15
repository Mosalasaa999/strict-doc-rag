package za.org.strictdoc.rag.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import za.org.strictdoc.rag.data.AppDatabase
import java.io.File
import java.util.concurrent.TimeUnit

class DocumentSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("rag_prefs", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("gateway_url", "")?.trimEnd('/') ?: ""
        if (baseUrl.isEmpty()) return Result.retry()

        val dao = AppDatabase.getDatabase(applicationContext).documentDao()
        val pending = dao.getPendingDocuments()
        if (pending.isEmpty()) return Result.success()

        var anyRetry = false
        for (doc in pending) {
            val file = File(doc.localFilePath)
            if (!file.exists()) {
                dao.updateSyncResult(doc.localId, "FAILED", "", 0)
                continue
            }

            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("doc_id", doc.localId)
                    .addFormDataPart("file", doc.filename, file.asRequestBody("application/octet-stream".toMediaType()))
                    .build()

                val req = Request.Builder()
                    .url("$baseUrl/v1/documents")
                    .post(body)
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string() ?: "{}")
                        val remoteId = json.optString("doc_id", doc.localId)
                        val chunks = json.optInt("chunk_count", 0)
                        dao.updateSyncResult(doc.localId, "SYNCED", remoteId, chunks)
                    } else {
                        anyRetry = true
                    }
                }
            } catch (_: Exception) {
                anyRetry = true
            }
        }
        return if (anyRetry) Result.retry() else Result.success()
    }
}