package main

import (
    "context"
    "crypto/sha256"
    "database/sql"
    "encoding/hex"
    "encoding/json"
    "fmt"
    "io"
    "log"
    "net/http"
    "os"
    "path/filepath"
    "strings"
    "sync"
    "time"

    _ "github.com/lib/pq"
)

type IngestGateway struct {
    db              *sql.DB
    llamaKey        string
    geminiKeys      []string
    groqKeys        []string
    cohereKey       string
    geminiModel     string
    groqModel       string
    cohereModel     string
    rerankThreshold float64
    keyMu           sync.Mutex
    geminiIdx       int
    groqIdx         int
}

type QueryRequest struct {
    Query         string   `json:"query"`
    DocIDs        []string `json:"doc_ids,omitempty"`
    AllowExternal bool     `json:"allow_external"`
}

type Claim struct {
    Statement   string   `json:"statement"`
    CitationIDs []string `json:"citation_ids"`
}

type CitationSource struct {
    ChunkID     string  `json:"chunk_id"`
    DocID       string  `json:"doc_id"`
    Filename    string  `json:"filename"`
    PageNumber  int     `json:"page_number"`
    SectionPath string  `json:"section_path"`
    Score       float64 `json:"rerank_score"`
    Excerpt     string  `json:"excerpt"`
}

type WebSource struct {
    Title string `json:"title"`
    URL   string `json:"url"`
}

type QueryResponse struct {
    Status              string           `json:"status"`
    Claims              []Claim          `json:"claims"`
    FallbackText        string           `json:"fallback_text,omitempty"`
    Sources             []CitationSource `json:"sources"`
    WebContext          string           `json:"web_context,omitempty"`
    WebSources          []WebSource      `json:"web_sources,omitempty"`
    AIKnowledge         []string         `json:"ai_knowledge,omitempty"`
    CanApproveExternal  bool             `json:"can_approve_external"`
    ExternalAnchorTopic string           `json:"external_anchor_topic,omitempty"`
    TopScore            float64          `json:"top_rerank_score"`
    GateReason          string           `json:"gate_reason,omitempty"`
}

type DocumentSummary struct {
    DocID      string `json:"doc_id"`
    Filename   string `json:"filename"`
    Status     string `json:"status"`
    ChunkCount int    `json:"chunk_count"`
}

const DeterministicFallback = "INSUFFICIENT_EVIDENCE: Your uploaded documents do not contain enough verified information to answer this query."

func splitKeyPool(envNames ...string) []string {
    var keys []string
    seen := map[string]bool{}
    for _, name := range envNames {
        raw := os.Getenv(name)
        for _, part := range strings.Split(raw, ",") {
            k := strings.TrimSpace(part)
            if k != "" && !seen[k] {
                seen[k] = true
                keys = append(keys, k)
            }
        }
    }
    return keys
}

func (g *IngestGateway) nextGeminiKey() string {
    g.keyMu.Lock()
    defer g.keyMu.Unlock()
    if len(g.geminiKeys) == 0 {
        return ""
    }
    k := g.geminiKeys[g.geminiIdx%len(g.geminiKeys)]
    g.geminiIdx++
    return k
}

func (g *IngestGateway) nextGroqKey() string {
    g.keyMu.Lock()
    defer g.keyMu.Unlock()
    if len(g.groqKeys) == 0 {
        return ""
    }
    k := g.groqKeys[g.groqIdx%len(g.groqKeys)]
    g.groqIdx++
    return k
}

func initDB(db *sql.DB) error {
    ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
    defer cancel()

    ddl := `
    CREATE EXTENSION IF NOT EXISTS vector;
    CREATE TABLE IF NOT EXISTS documents (
        doc_id TEXT PRIMARY KEY,
        filename TEXT NOT NULL,
        content_hash TEXT NOT NULL UNIQUE,
        status TEXT NOT NULL DEFAULT 'INDEXED',
        chunk_count INT NOT NULL DEFAULT 0,
        created_at TIMESTAMPTZ DEFAULT NOW()
    );
    CREATE TABLE IF NOT EXISTS document_chunks (
        chunk_id TEXT PRIMARY KEY,
        doc_id TEXT NOT NULL REFERENCES documents(doc_id) ON DELETE CASCADE,
        page_number INT NOT NULL DEFAULT 1,
        section_path TEXT NOT NULL DEFAULT 'Root',
        content TEXT NOT NULL,
        content_tsv tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
        embedding vector(768) NOT NULL,
        created_at TIMESTAMPTZ DEFAULT NOW()
    );
    CREATE INDEX IF NOT EXISTS idx_chunks_embedding_hnsw ON document_chunks USING hnsw (embedding vector_cosine_ops);
    CREATE INDEX IF NOT EXISTS idx_chunks_tsv_gin ON document_chunks USING GIN (content_tsv);`

    _, err := db.ExecContext(ctx, ddl)
    return err
}

func (g *IngestGateway) HandleDocuments(w http.ResponseWriter, r *http.Request) {
    w.Header().Set("Content-Type", "application/json")
    w.Header().Set("Access-Control-Allow-Origin", "*")

    switch r.Method {
    case http.MethodGet:
        if g.db == nil {
            json.NewEncoder(w).Encode([]DocumentSummary{})
            return
        }
        rows, err := g.db.Query(`SELECT doc_id, filename, status, chunk_count FROM documents ORDER BY created_at DESC LIMIT 50`)
        if err != nil {
            log.Printf("[ERR] List documents: %v", err)
            http.Error(w, `{"error":"database query failed"}`, http.StatusInternalServerError)
            return
        }
        defer rows.Close()
        docs := []DocumentSummary{}
        for rows.Next() {
            var d DocumentSummary
            if err := rows.Scan(&d.DocID, &d.Filename, &d.Status, &d.ChunkCount); err == nil {
                docs = append(docs, d)
            }
        }
        json.NewEncoder(w).Encode(docs)

    case http.MethodPost:
        if g.db == nil {
            http.Error(w, `{"error":"DATABASE_URL not configured"}`, http.StatusServiceUnavailable)
            return
        }
        if err := r.ParseMultipartForm(20 << 20); err != nil {
            http.Error(w, `{"error":"invalid multipart payload"}`, http.StatusBadRequest)
            return
        }
        file, header, err := r.FormFile("file")
        if err != nil {
            http.Error(w, `{"error":"missing file field"}`, http.StatusBadRequest)
            return
        }
        defer file.Close()

        rawBytes, err := io.ReadAll(file)
        if err != nil || len(rawBytes) == 0 {
            http.Error(w, `{"error":"failed to read uploaded file"}`, http.StatusBadRequest)
            return
        }

        sum := sha256.Sum256(rawBytes)
        contentHash := hex.EncodeToString(sum[:])
        docID := r.FormValue("doc_id")
        if docID == "" {
            docID = "DOC-" + contentHash[:12]
        }

        var existingID string
        var existingChunks int
        err = g.db.QueryRow(`SELECT doc_id, chunk_count FROM documents WHERE content_hash = $1`, contentHash).Scan(&existingID, &existingChunks)
        if err == nil {
            json.NewEncoder(w).Encode(map[string]any{
                "status":      "ALREADY_INDEXED",
                "doc_id":      existingID,
                "chunk_count": existingChunks,
            })
            return
        }

        ext := strings.ToLower(filepath.Ext(header.Filename))
        pages, err := g.ParseDocument(r.Context(), header.Filename, ext, rawBytes)
        if err != nil {
            log.Printf("[ERR] Layout parse failed for %s: %v", header.Filename, err)
            http.Error(w, fmt.Sprintf(`{"error":"layout parsing failed: %v"}`, err), http.StatusBadGateway)
            return
        }

        chunks := BuildStructureChunks(docID, header.Filename, pages, 450)
        if len(chunks) == 0 {
            http.Error(w, `{"error":"no extractable text found"}`, http.StatusUnprocessableEntity)
            return
        }

        embeddings := make([]string, len(chunks))
        for i, ch := range chunks {
            vec, err := g.EmbedText(r.Context(), ch.Content, "RETRIEVAL_DOCUMENT")
            if err != nil {
                log.Printf("[ERR] Embedding chunk %s: %v", ch.ChunkID, err)
                http.Error(w, fmt.Sprintf(`{"error":"embedding API failure: %v"}`, err), http.StatusBadGateway)
                return
            }
            embeddings[i] = formatPgVector(vec)
        }

        tx, err := g.db.BeginTx(r.Context(), nil)
        if err != nil {
            http.Error(w, `{"error":"tx begin failed"}`, http.StatusInternalServerError)
            return
        }
        defer tx.Rollback()

        _, err = tx.ExecContext(r.Context(), `
            INSERT INTO documents (doc_id, filename, content_hash, status, chunk_count)
            VALUES ($1, $2, $3, 'INDEXED', $4)
            ON CONFLICT (doc_id) DO NOTHING
        `, docID, header.Filename, contentHash, len(chunks))
        if err != nil {
            log.Printf("[ERR] Document insert: %v", err)
            http.Error(w, `{"error":"document insert failed"}`, http.StatusInternalServerError)
            return
        }

        for i, ch := range chunks {
            _, err = tx.ExecContext(r.Context(), `
                INSERT INTO document_chunks (chunk_id, doc_id, page_number, section_path, content, embedding)
                VALUES ($1, $2, $3, $4, $5, $6::vector)
                ON CONFLICT (chunk_id) DO NOTHING
            `, ch.ChunkID, docID, ch.PageNumber, ch.SectionPath, ch.Content, embeddings[i])
            if err != nil {
                log.Printf("[ERR] Chunk insert %s: %v", ch.ChunkID, err)
                http.Error(w, `{"error":"chunk insert failed"}`, http.StatusInternalServerError)
                return
            }
        }

        if err := tx.Commit(); err != nil {
            http.Error(w, `{"error":"tx commit failed"}`, http.StatusInternalServerError)
            return
        }

        w.WriteHeader(http.StatusCreated)
        json.NewEncoder(w).Encode(map[string]any{
            "status":      "INDEXED",
            "doc_id":      docID,
            "filename":    header.Filename,
            "chunk_count": len(chunks),
        })

    default:
        http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
    }
}

func (g *IngestGateway) HandleQuery(w http.ResponseWriter, r *http.Request) {
    w.Header().Set("Content-Type", "application/json")
    w.Header().Set("Access-Control-Allow-Origin", "*")

    if r.Method != http.MethodPost {
        http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
        return
    }
    if g.db == nil {
        http.Error(w, `{"error":"DATABASE_URL not configured"}`, http.StatusServiceUnavailable)
        return
    }

    var req QueryRequest
    if err := json.NewDecoder(r.Body).Decode(&req); err != nil || strings.TrimSpace(req.Query) == "" {
        http.Error(w, `{"error":"invalid JSON or empty query"}`, http.StatusBadRequest)
        return
    }

    resp, err := g.ExecuteClosedDomainRAG(r.Context(), req)
    if err != nil {
        log.Printf("[ERR] RAG pipeline error: %v", err)
        http.Error(w, fmt.Sprintf(`{"error":"rag pipeline failure: %v"}`, err), http.StatusBadGateway)
        return
    }
    json.NewEncoder(w).Encode(resp)
}

func main() {
    port := os.Getenv("PORT")
    if port == "" {
        port = "8080"
    }

    var db *sql.DB
    if dsn := os.Getenv("DATABASE_URL"); dsn != "" {
        var err error
        db, err = sql.Open("postgres", dsn)
        if err != nil {
            log.Fatalf("[ERR] DB open: %v", err)
        }
        if err := initDB(db); err != nil {
            log.Fatalf("[ERR] DB schema init: %v", err)
        }
        log.Println("[BOOT] PostgreSQL pgvector + tsvector schema verified")
    } else {
        log.Println("[BOOT] Running without DATABASE_URL (healthcheck mode only)")
    }

    geminiModel := os.Getenv("GEMINI_MODEL")
    if geminiModel == "" {
        geminiModel = "gemini-2.5-flash"
    }
    groqModel := os.Getenv("GROQ_MODEL")
    if groqModel == "" {
        groqModel = "llama-3.3-70b-versatile"
    }
    cohereModel := os.Getenv("COHERE_RERANK_MODEL")
    if cohereModel == "" {
        cohereModel = "rerank-v5.0-fast"
    }

    geminiPool := splitKeyPool("GEMINI_API_KEYS", "GEMINI_API_KEY")
    groqPool := splitKeyPool("GROQ_API_KEYS", "GROQ_API_KEY")

    gateway := &IngestGateway{
        db:              db,
        llamaKey:        os.Getenv("LLAMA_CLOUD_API_KEY"),
        geminiKeys:      geminiPool,
        groqKeys:        groqPool,
        cohereKey:       os.Getenv("COHERE_API_KEY"),
        geminiModel:     geminiModel,
        groqModel:       groqModel,
        cohereModel:     cohereModel,
        rerankThreshold: 0.35,
    }

    http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
        w.Header().Set("Content-Type", "application/json")
        json.NewEncoder(w).Encode(map[string]any{
            "status":      "ONLINE",
            "system":      "VeritasVault Closed-Domain RAG API",
            "version":     "1.1.0",
            "gemini_pool": len(geminiPool),
            "groq_pool":   len(groqPool),
        })
    })
    http.HandleFunc("/v1/documents", gateway.HandleDocuments)
    http.HandleFunc("/v1/query", gateway.HandleQuery)

    log.Printf("[BOOT] VeritasVault Gateway listening on :%s (Gemini keys: %d, Groq keys: %d)", port, len(geminiPool), len(groqPool))
    log.Fatal(http.ListenAndServe(":"+port, nil))
}