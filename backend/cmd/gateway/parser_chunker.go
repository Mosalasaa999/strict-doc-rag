package main

import (
    "bytes"
    "context"
    "encoding/json"
    "fmt"
    "io"
    "mime/multipart"
    "net/http"
    "strings"
    "time"
)

type ParsedPage struct {
    PageNumber int
    Markdown   string
}

type StructuredChunk struct {
    ChunkID     string
    PageNumber  int
    SectionPath string
    Content     string
}

func (g *IngestGateway) ParseDocument(ctx context.Context, filename, ext string, data []byte) ([]ParsedPage, error) {
    if ext == ".txt" || ext == ".md" {
        return []ParsedPage{{PageNumber: 1, Markdown: string(data)}}, nil
    }
    if g.llamaKey == "" {
        return nil, fmt.Errorf("LLAMA_CLOUD_API_KEY required for %s layout parsing", ext)
    }

    var body bytes.Buffer
    writer := multipart.NewWriter(&body)
    part, err := writer.CreateFormFile("file", filename)
    if err != nil {
        return nil, err
    }
    if _, err := part.Write(data); err != nil {
        return nil, err
    }
    _ = writer.WriteField("result_type", "markdown")
    _ = writer.Close()

    req, err := http.NewRequestWithContext(ctx, http.MethodPost, "https://api.cloud.llamaindex.ai/api/v1/parsing/upload", &body)
    if err != nil {
        return nil, err
    }
    req.Header.Set("Authorization", "Bearer "+g.llamaKey)
    req.Header.Set("Content-Type", writer.FormDataContentType())

    client := &http.Client{Timeout: 60 * time.Second}
    resp, err := client.Do(req)
    if err != nil {
        return nil, err
    }
    defer resp.Body.Close()
    if resp.StatusCode >= 300 {
        b, _ := io.ReadAll(resp.Body)
        return nil, fmt.Errorf("llamaparse upload %d: %s", resp.StatusCode, string(b))
    }

    var job struct {
        ID string `json:"id"`
    }
    if err := json.NewDecoder(resp.Body).Decode(&job); err != nil {
        return nil, err
    }

    for i := 0; i < 45; i++ {
        select {
        case <-ctx.Done():
            return nil, ctx.Err()
        case <-time.After(2 * time.Second):
        }
        sReq, _ := http.NewRequestWithContext(ctx, http.MethodGet, "https://api.cloud.llamaindex.ai/api/v1/parsing/job/"+job.ID, nil)
        sReq.Header.Set("Authorization", "Bearer "+g.llamaKey)
        sResp, err := client.Do(sReq)
        if err != nil {
            continue
        }
        var st struct {
            Status string `json:"status"`
        }
        _ = json.NewDecoder(sResp.Body).Decode(&st)
        sResp.Body.Close()

        if st.Status == "SUCCESS" {
            break
        }
        if st.Status == "ERROR" || st.Status == "CANCELED" {
            return nil, fmt.Errorf("llamaparse job %s status: %s", job.ID, st.Status)
        }
    }

    mReq, _ := http.NewRequestWithContext(ctx, http.MethodGet, "https://api.cloud.llamaindex.ai/api/v1/parsing/job/"+job.ID+"/result/markdown", nil)
    mReq.Header.Set("Authorization", "Bearer "+g.llamaKey)
    mResp, err := client.Do(mReq)
    if err != nil {
        return nil, err
    }
    defer mResp.Body.Close()

    var mdRes struct {
        Markdown string `json:"markdown"`
    }
    if err := json.NewDecoder(mResp.Body).Decode(&mdRes); err != nil {
        return nil, err
    }

    rawPages := strings.Split(mdRes.Markdown, "\n---\n")
    pages := make([]ParsedPage, 0, len(rawPages))
    for idx, p := range rawPages {
        trimmed := strings.TrimSpace(p)
        if trimmed != "" {
            pages = append(pages, ParsedPage{PageNumber: idx + 1, Markdown: trimmed})
        }
    }
    return pages, nil
}

func BuildStructureChunks(docID, filename string, pages []ParsedPage, maxWords int) []StructuredChunk {
    var out []StructuredChunk
    chunkSeq := 0
    h1, h2, h3 := "", "", ""

    sectionLabel := func() string {
        parts := []string{}
        for _, h := range []string{h1, h2, h3} {
            if h != "" {
                parts = append(parts, h)
            }
        }
        if len(parts) == 0 {
            return "Root"
        }
        return strings.Join(parts, " > ")
    }

    emit := func(page int, sec string, buf string) {
        text := strings.TrimSpace(buf)
        if text == "" {
            return
        }
        chunkSeq++
        header := fmt.Sprintf("[Document: %s | Section: %s | Page: %d]\n", filename, sec, page)
        out = append(out, StructuredChunk{
            ChunkID:     fmt.Sprintf("%s_P%d_C%d", docID, page, chunkSeq),
            PageNumber:  page,
            SectionPath: sec,
            Content:     header + text,
        })
    }

    for _, pg := range pages {
        lines := strings.Split(pg.Markdown, "\n")
        var curBuf strings.Builder
        var tableBuf strings.Builder
        var tableHeader string
        inTable := false

        flushPara := func() {
            if curBuf.Len() > 0 {
                emit(pg.PageNumber, sectionLabel(), curBuf.String())
                curBuf.Reset()
            }
        }

        for _, line := range lines {
            trim := strings.TrimSpace(line)

            if strings.HasPrefix(trim, "|") && strings.HasSuffix(trim, "|") {
                if !inTable {
                    flushPara()
                    inTable = true
                    tableHeader = line
                }
                if len(strings.Fields(tableBuf.String())) > maxWords && tableHeader != "" {
                    emit(pg.PageNumber, sectionLabel()+" (Table)", tableBuf.String())
                    tableBuf.Reset()
                    tableBuf.WriteString(tableHeader + "\n")
                }
                tableBuf.WriteString(line + "\n")
                continue
            } else if inTable {
                emit(pg.PageNumber, sectionLabel()+" (Table)", tableBuf.String())
                tableBuf.Reset()
                tableHeader = ""
                inTable = false
            }

            if strings.HasPrefix(trim, "# ") {
                flushPara()
                h1 = strings.TrimPrefix(trim, "# ")
                h2, h3 = "", ""
                continue
            } else if strings.HasPrefix(trim, "## ") {
                flushPara()
                h2 = strings.TrimPrefix(trim, "## ")
                h3 = ""
                continue
            } else if strings.HasPrefix(trim, "### ") {
                flushPara()
                h3 = strings.TrimPrefix(trim, "### ")
                continue
            }

            curBuf.WriteString(line + "\n")
            if len(strings.Fields(curBuf.String())) >= maxWords {
                flushPara()
            }
        }

        if inTable && tableBuf.Len() > 0 {
            emit(pg.PageNumber, sectionLabel()+" (Table)", tableBuf.String())
        }
        flushPara()
    }
    return out
}