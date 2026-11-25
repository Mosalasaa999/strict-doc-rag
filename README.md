# VeritasVault — Strict Closed-Domain RAG

A strict, closed-domain Retrieval-Augmented Generation system built as a native **Android Kotlin app** backed by a **Go 1.22 API gateway** and **PostgreSQL (`pgvector` + `tsvector`)**.

## How It Works

1. **Strict Docs-Only Mode (Default):**
   - Add PDF, Markdown, or TXT files to your **Document Vault** (`Room` offline queue + `WorkManager` background sync).
   - Scope queries to **All Documents** or check specific contracts/agreements inside the Vault sheet.
   - Answers are generated strictly from your uploaded documents with tappable **`[Filename • p.X]`** citation popups showing the exact source excerpt.
   - If the answer is missing from your files, the engine stops deterministically (`INSUFFICIENT_EVIDENCE`).

2. **Approval-Gated External Lookup:**
   - Nothing outside your documents is ever used unless you tap **Approve Aligned Web + AI**.
   - When approved, results remain strictly anchored to your documents' scope and are separated into three color-coded layers:
     - **Green:** Facts verified from your uploaded documents
     - **Blue:** Aligned Live Web results with clickable URLs
     - **Purple:** Aligned AI background knowledge
   - Automatically resets back to **Strict Docs-Only Mode** on your next question.

3. **Offline Saved Threads:**
   - Save any answer (including citations and approved external sections) directly on your device for offline reading.

## Repository Structure

- `android-app/` — Native Kotlin client (`MainActivity.kt`, `ChatAdapter.kt`, `VaultAdapter.kt`, `LocalDocumentStore.kt`, `DocumentSyncWorker.kt`)
- `backend/cmd/gateway/` — Go 1.22 `net/http` RAG gateway (native PDF layout parsing, structure-aware chunking, hybrid RRF vector + BM25 search, cross-scoring rerank cutoff gate, strict JSON citation validator)
- `backend/cmd/eval/` — Automated evaluation CLI (Context Recall, Citation Integrity, Abstention Accuracy, False Refusal Rate)
- `backend/migrations/` — `pgvector` + `tsvector` schema
- `.github/workflows/build-apk.yml` — Automated Android APK build on GitHub Actions
- `Dockerfile` & `render.yaml` — Render deployment configuration

## Cloud Setup (Free Tier)

1. **Database (Neon PostgreSQL):** Create a free project at `neon.tech` and copy your `DATABASE_URL` (`postgres://...`).
2. **Backend (Render):** Connect this repository to Render (`Docker` runtime) and configure these environment variables:
   - `DATABASE_URL` — Neon PostgreSQL connection string
   - `GEMINI_API_KEYS` — Comma-separated Gemini API key(s) for PDF parsing, embeddings (`gemini-embedding-001`), strict extraction (`gemini-4-hydrogen`), and Google Search grounding
   - `GROQ_API_KEYS` — Comma-separated Groq API key(s) for fast relevance reranking
   - *(Optional)* `GEMINI_MODEL` (defaults to `gemini-4-hydrogen`), `LLAMA_CLOUD_API_KEY`, `COHERE_API_KEY`
3. **Android App:** Open the **Actions** tab on GitHub, download the latest **`strictdoc-rag-debug-apk`** artifact, install it on your Android device, tap **API**, paste your `https://<your-app>.onrender.com` URL, and tap **Save & Sync**.

## Automated Evaluation (Phase 5)

```bash
cd backend
go run ./cmd/eval -url https://<your-app>.onrender.com/v1/query -suite eval_suite.json
```
