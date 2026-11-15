package za.org.strictdoc.rag

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import za.org.strictdoc.rag.data.AppDatabase
import za.org.strictdoc.rag.data.DocumentRecord
import za.org.strictdoc.rag.data.SavedAnswerRecord
import za.org.strictdoc.rag.sync.DocumentSyncWorker
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var pillVaultStatus: TextView
    private lateinit var rvChat: RecyclerView
    private lateinit var tvEmptyHint: View
    private lateinit var etQuery: EditText
    private lateinit var chatAdapter: ChatAdapter

    private val turns = mutableListOf<ChatTurn>()
    private val selectedDocIds = mutableSetOf<String>()
    private var queryAllDocs = true

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val docPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) queueDocumentLocally(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pillVaultStatus = findViewById(R.id.pillVaultStatus)
        rvChat = findViewById(R.id.rvChat)
        tvEmptyHint = findViewById(R.id.tvEmptyHint)
        etQuery = findViewById(R.id.etQuery)

        chatAdapter = ChatAdapter(
            items = turns,
            onCitationClick = { showCitationSheet(it) },
            onWebLinkClick = { url ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                    Toast.makeText(this, "Cannot open link", Toast.LENGTH_SHORT).show()
                }
            },
            onApproveExternal = { turn -> runQueryTurn(turn.query, allowExternal = true, existingTurn = turn) },
            onSaveTurn = { saveTurnOffline(it) }
        )

        rvChat.layoutManager = LinearLayoutManager(this)
        rvChat.adapter = chatAdapter

        findViewById<Button>(R.id.btnSettings).setOnClickListener { showGatewayUrlDialog() }
        findViewById<Button>(R.id.btnSaved).setOnClickListener { showSavedAnswersDialog() }
        pillVaultStatus.setOnClickListener { showVaultBottomSheet() }
        findViewById<Button>(R.id.btnVaultAttach).setOnClickListener { showVaultBottomSheet() }

        findViewById<Button>(R.id.btnSend).setOnClickListener {
            val q = etQuery.text.toString().trim()
            if (q.isNotEmpty()) {
                etQuery.setText("")
                // Automatic safety reset: every new question starts strictly in Docs-Only mode
                runQueryTurn(q, allowExternal = false, existingTurn = null)
            }
        }

        refreshVaultPill()
    }

    private fun refreshVaultPill() {
        lifecycleScope.launch(Dispatchers.IO) {
            val docs = AppDatabase.getDatabase(this@MainActivity).documentDao().getAllDocuments()
            val synced = docs.count { it.syncState == "SYNCED" }
            val queued = docs.count { it.syncState == "QUEUED" }
            withContext(Dispatchers.Main) {
                val scopeTag = if (queryAllDocs || selectedDocIds.isEmpty()) "All" else "${selectedDocIds.size} selected"
                pillVaultStatus.text = when {
                    synced > 0 && queued == 0 -> "Vault: $synced ready ($scopeTag)"
                    synced > 0 && queued > 0 -> "Vault: $synced ready • $queued queued"
                    queued > 0 -> "Vault: $queued queued • Tap to sync"
                    else -> "Vault: Empty • Tap to add PDFs"
                }
                pillVaultStatus.setTextColor(Color.parseColor(if (synced > 0) "#34D399" else "#FBBF24"))
            }
        }
    }

    private fun showVaultBottomSheet() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_vault, null)
        sheet.setContentView(view)

        val btnAdd = view.findViewById<Button>(R.id.btnVaultAdd)
        val btnSync = view.findViewById<Button>(R.id.btnVaultSync)
        val cbAll = view.findViewById<CheckBox>(R.id.cbSelectAll)
        val rvDocs = view.findViewById<RecyclerView>(R.id.rvVaultDocs)

        rvDocs.layoutManager = LinearLayoutManager(this)
        cbAll.isChecked = queryAllDocs

        fun reloadSheetList() {
            lifecycleScope.launch(Dispatchers.IO) {
                val docs = AppDatabase.getDatabase(this@MainActivity).documentDao().getAllDocuments()
                withContext(Dispatchers.Main) {
                    rvDocs.adapter = VaultAdapter(
                        docs = docs,
                        selectedRemoteIds = selectedDocIds,
                        selectAllMode = { queryAllDocs },
                        onToggleChanged = {
                            queryAllDocs = false
                            cbAll.isChecked = false
                            refreshVaultPill()
                        }
                    )
                }
            }
        }

        cbAll.setOnCheckedChangeListener { _, checked ->
            queryAllDocs = checked
            if (checked) selectedDocIds.clear()
            reloadSheetList()
            refreshVaultPill()
        }

        btnAdd.setOnClickListener {
            sheet.dismiss()
            docPicker.launch(arrayOf("application/pdf", "text/plain", "text/markdown"))
        }

        btnSync.setOnClickListener {
            val baseUrl = getSharedPreferences("rag_prefs", MODE_PRIVATE).getString("gateway_url", "") ?: ""
            if (baseUrl.isEmpty()) {
                sheet.dismiss()
                showGatewayUrlDialog()
            } else {
                enqueueSyncWorker()
                Toast.makeText(this, "Syncing documents to cloud...", Toast.LENGTH_SHORT).show()
                rvDocs.postDelayed({ reloadSheetList(); refreshVaultPill() }, 2500)
            }
        }

        reloadSheetList()
        sheet.show()
    }

    private fun showCitationSheet(src: CitationSourceItem) {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_citation, null)
        view.findViewById<TextView>(R.id.tvCiteTitle).text = src.filename
        view.findViewById<TextView>(R.id.tvCiteMeta).text = "Page ${src.pageNumber} • ${src.sectionPath} • [${src.chunkId}]"
        view.findViewById<TextView>(R.id.tvCiteScore).text = "Relevance Match: ${"%.1f".format(src.score * 100)}%"
        view.findViewById<TextView>(R.id.tvCiteExcerpt).text = src.excerpt
        sheet.setContentView(view)
        sheet.show()
    }

    private fun runQueryTurn(query: String, allowExternal: Boolean, existingTurn: ChatTurn?) {
        val baseUrl = getSharedPreferences("rag_prefs", MODE_PRIVATE).getString("gateway_url", "")?.trimEnd('/') ?: ""
        if (baseUrl.isEmpty()) {
            showGatewayUrlDialog()
            return
        }

        tvEmptyHint.visibility = View.GONE
        val turn = existingTurn ?: ChatTurn(
            id = UUID.randomUUID().toString(),
            query = query,
            status = "LOADING",
            docBody = "Searching your uploaded documents..."
        ).also {
            turns.add(it)
            chatAdapter.notifyItemInserted(turns.size - 1)
            rvChat.scrollToPosition(turns.size - 1)
        }

        if (existingTurn != null) {
            turn.status = "LOADING"
            turn.usedExternal = true
            turn.canApproveExternal = false
            chatAdapter.notifyItemChanged(turns.indexOf(turn))
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val payloadObj = JSONObject()
                    .put("query", query)
                    .put("allow_external", allowExternal)

                if (!queryAllDocs && selectedDocIds.isNotEmpty()) {
                    payloadObj.put("doc_ids", JSONArray(selectedDocIds.toList()))
                }

                val req = Request.Builder()
                    .url("$baseUrl/v1/query")
                    .post(payloadObj.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val bodyStr = httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                    resp.body?.string() ?: "{}"
                }

                val json = JSONObject(bodyStr)
                val status = json.optString("status", "INSUFFICIENT_EVIDENCE")
                val claims = json.optJSONArray("claims")
                val docSb = StringBuilder()

                if (claims != null && claims.length() > 0) {
                    for (i in 0 until claims.length()) {
                        val c = claims.getJSONObject(i)
                        val cids = c.getJSONArray("citation_ids")
                        val citeTags = (0 until cids.length()).joinToString(", ") { cids.getString(it) }
                        docSb.append("• ").append(c.getString("statement")).append(" [$citeTags]\n\n")
                    }
                } else {
                    docSb.append(json.optString("fallback_text", "No verified answer found in your documents."))
                }

                val sourceItems = mutableListOf<CitationSourceItem>()
                val srcArr = json.optJSONArray("sources")
                if (srcArr != null) {
                    for (i in 0 until srcArr.length()) {
                        val s = srcArr.getJSONObject(i)
                        sourceItems.add(
                            CitationSourceItem(
                                chunkId = s.getString("chunk_id"),
                                filename = s.getString("filename"),
                                pageNumber = s.getInt("page_number"),
                                sectionPath = s.optString("section_path", "Root"),
                                score = s.optDouble("rerank_score", 0.0),
                                excerpt = s.getString("excerpt")
                            )
                        )
                    }
                }

                val webLinks = mutableListOf<WebSourceItem>()
                val webArr = json.optJSONArray("web_sources")
                if (webArr != null) {
                    for (i in 0 until webArr.length()) {
                        val ws = webArr.getJSONObject(i)
                        webLinks.add(WebSourceItem(ws.optString("title"), ws.optString("url")))
                    }
                }

                val aiList = mutableListOf<String>()
                val aiArr = json.optJSONArray("ai_knowledge")
                if (aiArr != null) {
                    for (i in 0 until aiArr.length()) {
                        aiList.add(aiArr.getString(i))
                    }
                }

                turn.status = status
                turn.docBody = docSb.toString().trim()
                turn.sources = sourceItems
                turn.canApproveExternal = json.optBoolean("can_approve_external", false)
                turn.anchorTopic = json.optString("external_anchor_topic", "your documents")
                turn.webContext = json.optString("web_context", "")
                turn.webSources = webLinks
                turn.aiPoints = aiList
                turn.usedExternal = allowExternal

                withContext(Dispatchers.Main) {
                    val idx = turns.indexOf(turn)
                    if (idx >= 0) chatAdapter.notifyItemChanged(idx)
                    rvChat.scrollToPosition(turns.size - 1)
                }
            } catch (e: Exception) {
                turn.status = "ERROR"
                turn.docBody = "Could not reach gateway: ${e.message}\n(If Render just woke up, wait 20s and retry.)"
                withContext(Dispatchers.Main) {
                    val idx = turns.indexOf(turn)
                    if (idx >= 0) chatAdapter.notifyItemChanged(idx)
                }
            }
        }
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
                refreshVaultPill()
                Toast.makeText(this@MainActivity, "Added $filename to Vault", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enqueueSyncWorker() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val req = OneTimeWorkRequestBuilder<DocumentSyncWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(this).enqueueUniqueWork("rag_doc_sync", ExistingWorkPolicy.REPLACE, req)
    }

    private fun saveTurnOffline(turn: ChatTurn) {
        lifecycleScope.launch(Dispatchers.IO) {
            val rec = SavedAnswerRecord(
                id = turn.id,
                queryText = turn.query,
                status = turn.status,
                usedExternal = turn.usedExternal,
                formattedBody = turn.toOfflineSummary(),
                savedTimestampUtc = System.currentTimeMillis()
            )
            AppDatabase.getDatabase(this@MainActivity).savedAnswerDao().insertAnswer(rec)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Saved offline in VeritasVault", Toast.LENGTH_SHORT).show()
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
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(picked.queryText)
                            .setMessage(picked.formattedBody)
                            .setPositiveButton("Close", null)
                            .show()
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }

    private fun showGatewayUrlDialog() {
        val prefs = getSharedPreferences("rag_prefs", MODE_PRIVATE)
        val input = EditText(this).apply {
            hint = "https://veritasvault-gateway.onrender.com"
            setText(prefs.getString("gateway_url", ""))
        }
        AlertDialog.Builder(this)
            .setTitle("Cloud Gateway URL")
            .setMessage("Paste your Render HTTPS URL:")
            .setView(input)
            .setPositiveButton("Save & Sync") { _, _ ->
                prefs.edit().putString("gateway_url", input.text.toString().trim()).apply()
                enqueueSyncWorker()
                refreshVaultPill()
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