# PaiAgent Scripts

This directory contains local development and benchmark helpers.

## Python Dependencies

The Python scripts require `requests`:

```powershell
python -m pip install requests
```

## Shared Options

The RAG helper scripts use the same API options:

```powershell
--base-url http://localhost:8084/api
--username admin
--password admin123
--timeout 30
```

You can also set environment variables:

```powershell
$env:PAIAGENT_BASE_URL = "http://localhost:8084/api"
$env:PAIAGENT_USERNAME = "admin"
$env:PAIAGENT_PASSWORD = "admin123"
$env:PAIAGENT_TIMEOUT_SECONDS = "30"
$env:PAIAGENT_KB_ID = "3"
```

## Upload Demo Knowledge Documents

Preview without sending requests:

```powershell
python scripts/upload_kb_docs.py --dry-run --kb-id 3
python scripts/upload_noise_docs.py --dry-run --kb-id 3
```

Upload documents:

```powershell
python scripts/upload_kb_docs.py --kb-id 3
python scripts/upload_noise_docs.py --kb-id 3
```

## Evaluate A RAG Workflow

Run retrieval-only evaluation:

```powershell
python scripts/evaluate_rag.py --flow-id 1 --dataset scripts/rag_eval_dataset.csv
```

Retrieval-only mode does not read `OPENAI_API_KEY`. LLM-as-a-judge scoring is enabled only when `JUDGE_API_KEY` is set or `--judge-key` is provided.

Enable LLM-as-a-judge scoring with an OpenAI-compatible endpoint:

```powershell
$env:JUDGE_API_KEY = "<your_api_key>"
python scripts/evaluate_rag.py --flow-id 1 --judge-model gpt-4o-mini
```
