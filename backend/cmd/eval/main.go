package main

import (
    "bytes"
    "encoding/json"
    "flag"
    "fmt"
    "log"
    "net/http"
    "os"
    "strings"
    "time"
)

type EvalCase struct {
    ID               string   `json:"id"`
    Query            string   `json:"query"`
    ExpectAbstain    bool     `json:"expect_abstain"`
    ExpectedKeywords []string `json:"expected_keywords"`
}

type QueryResp struct {
    Status string `json:"status"`
    Claims []struct {
        Statement   string   `json:"statement"`
        CitationIDs []string `json:"citation_ids"`
    } `json:"claims"`
    Sources []struct {
        ChunkID string `json:"chunk_id"`
        Excerpt string `json:"excerpt"`
    } `json:"sources"`
}

func main() {
    apiURL := flag.String("url", "http://localhost:8080/v1/query", "Gateway /v1/query endpoint")
    suitePath := flag.String("suite", "eval_suite.json", "Path to golden dataset JSON")
    flag.Parse()

    raw, err := os.ReadFile(*suitePath)
    if err != nil {
        log.Fatalf("[ERR] Read suite: %v", err)
    }
    var cases []EvalCase
    if err := json.Unmarshal(raw, &cases); err != nil {
        log.Fatalf("[ERR] Parse suite: %v", err)
    }

    client := &http.Client{Timeout: 45 * time.Second}
    var posTotal, negTotal, trueNeg, falseRefusal, contextHits, citationValid int

    for _, tc := range cases {
        body, _ := json.Marshal(map[string]any{"query": tc.Query, "allow_external": false})
        resp, err := client.Post(*apiURL, "application/json", bytes.NewReader(body))
        if err != nil {
            log.Printf("[ERR] Case %s failed: %v", tc.ID, err)
            continue
        }
        var qr QueryResp
        _ = json.NewDecoder(resp.Body).Decode(&qr)
        resp.Body.Close()

        abstained := qr.Status == "INSUFFICIENT_EVIDENCE"
        if tc.ExpectAbstain {
            negTotal++
            if abstained {
                trueNeg++
            } else {
                log.Printf("[FAIL-ABSTAIN] %s leaked answer: %q", tc.ID, tc.Query)
            }
            continue
        }

        posTotal++
        if abstained {
            falseRefusal++
        }

        joinedCtx := ""
        srcMap := map[string]bool{}
        for _, s := range qr.Sources {
            joinedCtx += " " + strings.ToLower(s.Excerpt)
            srcMap[s.ChunkID] = true
        }
        matchedAll := len(tc.ExpectedKeywords) > 0
        for _, kw := range tc.ExpectedKeywords {
            if !strings.Contains(joinedCtx, strings.ToLower(kw)) {
                matchedAll = false
                break
            }
        }
        if matchedAll {
            contextHits++
        }

        allCitedValid := len(qr.Claims) > 0
        for _, cl := range qr.Claims {
            if len(cl.CitationIDs) == 0 {
                allCitedValid = false
            }
            for _, cid := range cl.CitationIDs {
                if !srcMap[cid] {
                    allCitedValid = false
                }
            }
        }
        if allCitedValid {
            citationValid++
        }
    }

    fmt.Println("=== STRICT CLOSED-DOMAIN RAG EVAL REPORT ===")
    if posTotal > 0 {
        fmt.Printf("Context Recall (Answerable): %.2f%% (%d/%d)\n", 100*float64(contextHits)/float64(posTotal), contextHits, posTotal)
        fmt.Printf("Citation Integrity:          %.2f%% (%d/%d)\n", 100*float64(citationValid)/float64(posTotal), citationValid, posTotal)
        fmt.Printf("False Refusal Rate (FNR):    %.2f%% (%d/%d)\n", 100*float64(falseRefusal)/float64(posTotal), falseRefusal, posTotal)
    }
    if negTotal > 0 {
        fmt.Printf("Abstention Accuracy (TNR):   %.2f%% (%d/%d)\n", 100*float64(trueNeg)/float64(negTotal), trueNeg, negTotal)
    }
}