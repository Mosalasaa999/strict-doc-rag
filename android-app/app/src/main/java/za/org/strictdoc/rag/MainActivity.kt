package za.org.strictdoc.rag

import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import za.org.strictdoc.rag.data.AppDatabase
import za.org.strictdoc.rag.data.DocumentRecord
import za.org.strictdoc.rag.data.SavedAnswerRecord
import za.org.strictdoc.rag.sync.DocumentSyncWorker
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var tvCorpusStatus: TextView
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvAnswerOutput: TextView
    private lateinit var etQuery: EditText
    private lateinit var btnApproveExternal: Button
    private lateinit var btnSaveAnswer: Button

    private var lastQuery: String = ""
    private var lastStatus: String = ""
    private var lastUsedExternal: Boolean = false
    private var lastFormattedText: String = ""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val docPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) queueDocumentLocally(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvCorpusStatus = findViewById(R.id.tvCorpusStatus)
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        tvAnswerOutput = findViewById(R.id.tvAnswerOutput)
        etQuery = findViewById(R.id.etQuery)
        btnApproveExternal = findViewById(R.id.btnApproveExternal)
        btnSaveAnswer = findViewById(R.id.btnSaveAnswer)

        findViewById<Button>(R.id.btnSettings).setOnClickListener { showGatewayUrlDialog() }
        findViewById<Button>(R.id.btnSaved).setOnClickListener { showSavedAnswersDialog() }
        findViewById<Button>(R.id.btnUploadDoc).setOnClickListener {
            docPicker.launch(arrayOf("application/pdf", "text/plain", "text/markdown"))
        }
        findViewById<Button>(R.id.btnRefreshDocs).setOnClickListener {
            enqueueSyncWorker()
            refreshCorpusStatus()
        }
        findViewById<Button>(R.id.btnAsk).setOnClickListener {
            val q = etQuery.text.toString().trim()
            if (q.isNotEmpty()) {
                // Automatic safety reset to Strict Mode on every new question
                executeQuery(q, allowExternal = false)
            }
        }
        btnApproveExternal.setOnClickListener {
            if (lastQuery.isNotEmpty()) {
                executeQuery(lastQuery, allowExternal = true)
            }
        }
        btnSaveAnswer.setOnClickListener { saveCurrentAnswerOffline() }

        refreshCorpusStatus()
    }

    private fun queueDocumentLocally(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val filename = resolveFilename(uri)
            val localId = "DOC-" + UUID.randomUUID().toString().take(8)
            val dir = File(filesDir, "pending_docs").apply { mkdirs() }
            val destFile = File(dir, "${localId}_$filename")

            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }

            val record = DocumentRecord(
                localId = localId,
                filename = filename,
                localFilePath = destFile.absolutePath,
                remoteDocId = "",
                chunkCount = 0,
                deviceTimestampUtc = System.currentTimeMillis(),
                syncState = "QUEUED"
            )
            AppDatabase.getDatabase(this@MainActivity).documentDao().insertDocument(record)

            withContext(Dispatchers.Main) {
                enqueueSyncWorker()
                refreshCorpusStatus()
                Toast.makeText(this@MainActivity, "Queued $filename", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enqueueSyncWorker() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val req = OneTimeWorkRequestBuilder<DocumentSyncWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(this).enqueueUniqueWork("rag_doc_sync", ExistingWorkPolicy.REPLACE, req)
    }

    private fun refreshCorpusStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            val docs = AppDatabase.getDatabase(this@MainActivity).documentDao().getAllDocuments()
            val synced = docs.count { it.syncState == "SYNCED" }
            val queued = docs.count { it.syncState == "QUEUED" }
            val names = docs.take(3).joinToString(", ") { "${it.filename} (${it.syncState})" }
            withContext(Dispatchers.Main) {
                tvCorpusStatus.text = "Corpus: $synced synced | $queued queued" +
                    if (names.isNotEmpty()) "\nRecent: $names" else ""
            }
        }
    }

    private fun executeQuery(query: String, allowExternal: Boolean) {
        val baseUrl = getSharedPreferences("rag_prefs", MODE_PRIVATE).getString("gateway_url", "")?.trimEnd('/') ?: ""
        if (baseUrl.isEmpty()) {
            showGatewayUrlDialog()
            return
        }

        lastQuery = query
        lastUsedExternal = allowExternal
        btnApproveExternal.visibility = View.GONE
        btnSaveAnswer.visibility = View.GONE

        tvStatusBadge.text = if (allowExternal) "FETCHING APPROVED EXTERNAL..." else "STRICT MODE: CHECKING DOCS..."
        tvStatusBadge.setBackgroundColor(Color.parseColor("#1E293B"))
        tvAnswerOutput.text = "Searching your uploaded documents..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val payload = JSONObject()
                    .put("query", query)
                    .put("allow_external", allowExternal)
                    .toString()

                val req = Request.Builder()
                    .url("$baseUrl/v1/query")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()

                val bodyStr = httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                    resp.body?.string() ?: "{}"
                }

                val json = JSONObject(bodyStr)
                val status = json.optString("status", "INSUFFICIENT_EVIDENCE")
                val canApprove = json.optBoolean("can_approve_external", false)
                val anchor = json.optString("external_anchor_topic", "your documents")
                val topScore = json.optDouble("top_rerank_score", 0.0)

                val ssb = SpannableStringBuilder()

                // LAYER 1: STRICT DOCUMENTS (GREEN)
                appendColoredHeading(ssb, "[FROM YOUR DOCUMENTS]\n", "#34D399")
                val claims = json.optJSONArray("claims")
                if (claims != null && claims.length() > 0) {
                    for (i in 0 until claims.length()) {
                        val c = claims.getJSONObject(i)
                        val cids = c.getJSONArray("citation_ids")
                        val citeList = (0 until cids.length()).map { cids.getString(it) }
                        ssb.append("• ").append(c.getString("statement"))
                            .append("  [").append(citeList.joinToString(", ")).append("]\n\n")
                    }
                } else {
                    ssb.append(json.optString("fallback_text", "No verified match in your documents.")).append("\n\n")
                }

                // LAYER 2: APPROVED LIVE WEB (BLUE)
                val webText = json.optString("web_context", "")
                if (webText.isNotEmpty()) {
                    appendColoredHeading(ssb, "[APPROVED EXTERNAL • LIVE WEB]\n", "#60A5FA")
                    ssb.append(webText).append("\n")
                    val webSources = json.optJSONArray("web_sources")
                    if (webSources != null && webSources.length() > 0) {
                        for (i in 0 until webSources.length()) {
                            val ws = webSources.getJSONObject(i)
                            ssb.append("  ↗ ${ws.optString("title")}: ${ws.optString("url")}\n")
                        }
                    }
                    ssb.append("\n")
                }

                // LAYER 3: APPROVED ALIGNED AI KNOWLEDGE (PURPLE)
                val aiPoints = json.optJSONArray("ai_knowledge")
                if (aiPoints != null && aiPoints.length() > 0) {
                    appendColoredHeading(ssb, "[APPROVED EXTERNAL • ALIGNED AI KNOWLEDGE]\n", "#C084FC")
                    for (i in 0 until aiPoints.length()) {
                        ssb.append("• ").append(aiPoints.getString(i)).append("\n")
                    }
                    ssb.append("\n")
                }

                // VERIFIED DOCUMENT EXCERPTS
                val sources = json.optJSONArray("sources")
                if (sources != null && sources.length() > 0) {
                    appendColoredHeading(ssb, "--- DOCUMENT EXCERPTS (Top Match: ${"%.2f".format(topScore)}) ---\n", "#94A3B8")
                    for (i in 0 until sources.length()) {
                        val s = sources.getJSONObject(i)
                        ssb.append("[${s.getString("chunk_id")}] ${s.getString("filename")} p.${s.getInt("page_number")}\n")
                            .append("   \"${s.getString("excerpt").replace("\n", " ")}\"\n\n")
                    }
                }

                lastStatus = status
                lastFormattedText = ssb.toString()

                withContext(Dispatchers.Main) {
                    tvStatusBadge.text = when {
                        allowExternal -> "EXTERNAL APPROVED (AUTO-RESETS NEXT QUERY)"
                        status == "ANSWERED" -> "STRICT MODE: VERIFIED FROM DOCS"
                        else -> "STRICT MODE: NOT IN DOCUMENTS"
                    }
                    tvStatusBadge.setBackgroundColor(
                        Color.parseColor(
                            when {
                                allowExternal -> "#6D28D9"
                                status == "ANSWERED" -> "#059669"
                                else -> "#D97706"
                            }
                        )
                    )
                    tvAnswerOutput.text = ssb
                    btnSaveAnswer.visibility = View.VISIBLE
                    if (canApprove) {
                        btnApproveExternal.text = "Approve Aligned External Lookup ($anchor)"
                        btnApproveExternal.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatusBadge.text = "ERROR"
                    tvStatusBadge.setBackgroundColor(Color.parseColor("#DC2626"))
                    tvAnswerOutput.text = "Query failed: ${e.message}"
                }
            }
        }
    }

    private fun appendColoredHeading(ssb: SpannableStringBuilder, text: String, hexColor: String) {
        val start = ssb.length
        ssb.append(text)
        ssb.setSpan(ForegroundColorSpan(Color.parseColor(hexColor)), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun saveCurrentAnswerOffline() {
        if (lastQuery.isEmpty() || lastFormattedText.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val rec = SavedAnswerRecord(
                id = UUID.randomUUID().toString(),
                queryText = lastQuery,
                status = lastStatus,
                usedExternal = lastUsedExternal,
                formattedBody = lastFormattedText,
                savedTimestampUtc = System.currentTimeMillis()
            )
            AppDatabase.getDatabase(this@MainActivity).savedAnswerDao().insertAnswer(rec)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Saved offline on device", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSavedAnswersDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val items = AppDatabase.getDatabase(this@MainActivity).savedAnswerDao().getAllSaved()
            withContext(Dispatchers.Main) {
                if (items.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No saved answers yet", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val titles = items.map {
                    val tag = if (it.usedExternal) "[Docs+Ext]" else "[Docs Only]"
                    "$tag ${it.queryText}"
                }.toTypedArray()

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Saved Offline Answers")
                    .setItems(titles) { _, idx ->
                        val picked = items[idx]
                        etQuery.setText(picked.queryText)
                        tvStatusBadge.text = "OFFLINE SAVED (${picked.status})"
                        tvStatusBadge.setBackgroundColor(Color.parseColor("#334155"))
                        tvAnswerOutput.text = picked.formattedBody
                        btnApproveExternal.visibility = View.GONE
                        btnSaveAnswer.visibility = View.GONE
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }

    private fun showGatewayUrlDialog() {
        val prefs = getSharedPreferences("rag_prefs", MODE_PRIVATE)
        val input = EditText(this).apply {
            hint = "https://strictdoc-rag-gateway.onrender.com"
            setText(prefs.getString("gateway_url", ""))
        }
        AlertDialog.Builder(this)
            .setTitle("Go RAG Gateway URL")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("gateway_url", input.text.toString().trim()).apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resolveFilename(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) return c.getString(idx)
        }
        return "document.pdf"
    }
}