# RAG Industrial Enhancement Design

Date: 2026-06-20
Branch: feature/rag-industrial-enhancement

## Current State

The existing RAG pipeline has three weaknesses:

| Weakness | Current | Target |
|----------|---------|--------|
| Reranker | `0.65×vectorScore + 0.35×keywordScore` linear fusion | Cross-Encoder Reranker (DashScope gte-rerank) + LLM fallback |
| Data cleaning | Only `normalize()` (CRLF→LF, tabs→spaces) | Full pipeline: HTML stripping, dedup, noise filter, PII redaction |
| Keyword retrieval | MySQL FULLTEXT (TF-IDF variant, no Chinese tokenizer) + 2/3-gram tokenizer | jieba tokenizer + BM25 scorer |

## Direction 1: Cross-Encoder Reranker

### New Files
```
backend/src/main/java/com/paiagent/engine/rerank/
  Reranker.java              — interface: rerank(query, List<Candidate>) → List<Candidate>
  DashScopeReranker.java     — Alibaba Bailian Rerank API (gte-rerank model)
  LLMReranker.java           — LLM relevance scoring fallback (DeepSeek/Tongyi)
  RerankerFactory.java       — auto-select available reranker
```

### Modified Files
- `KnowledgeBaseService.java` — insert Reranker call after RRF fusion, before minScore filter
- `RagRetrievalScorer.java` — remove linear fusion formula, keep keywordScore
- `application.yml` — reranker config section
- `RagEmbeddingProperties.java` — add RerankerProperties

### Pipeline
```
dense recall + keyword recall → RRF fusion → Reranker(Cross-Encoder) → minScore filter → topK
```

## Direction 2: Data Cleaning Pipeline

### New Files
```
scripts/cleaning/
  clean_html.py              — BeautifulSoup HTML strip, preserve structure
  deduplicate.py             — SimHash + edit distance dedup
  filter_noise.py            — quality filter (length, garbage, repetition rate)
  redact_pii.py              — PII redaction (ID card, phone, email)
  pipeline.py                — orchestration script, CLI interface

backend/src/main/java/com/paiagent/service/document/
  DataCleaningService.java   — Java orchestration layer
```

### Modified Files
- `DocumentParsingService.java` — call DataCleaningService after parse, before split
- `KnowledgeBaseService.java` — trigger cleaning on document upload
- `application.yml` — cleaning config (enabled, thresholds)

## Direction 3: BM25 + Chinese Tokenizer

### New Files
```
backend/src/main/java/com/paiagent/engine/tokenizer/
  ChineseTokenizer.java      — jieba wrapper with domain dictionary support

backend/src/main/java/com/paiagent/engine/retrieval/
  BM25Scorer.java            — BM25 scoring implementation
  HybridRetriever.java       — dense + BM25 dual-recall coordinator
```

### Modified Files
- `RagRetrievalScorer.java` — replace n-gram with jieba tokenizer
- `KnowledgeBaseService.java` — searchKeywordCandidates() use BM25
- `pom.xml` — add `com.huaban:jieba-analysis` dependency

### Dependencies
- `com.huaban:jieba-analysis` (Maven) — Chinese tokenization
- `beautifulsoup4`, `simhash`, `lxml` (pip) — data cleaning
- DashScope SDK (existing, may need rerank model support)
