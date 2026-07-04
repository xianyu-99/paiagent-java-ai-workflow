from __future__ import annotations

import argparse
import csv
import json
import os
from pathlib import Path
from typing import Any

import requests

from paiagent_api import (
    PaiAgentApiError,
    add_common_args,
    execute_workflow,
    login,
    normalize_base_url,
)


DEFAULT_DATASET = Path(__file__).with_name("rag_eval_dataset.csv")
DEFAULT_JUDGE_BASE_URL = os.getenv("JUDGE_BASE_URL", "https://api.openai.com/v1")
DEFAULT_JUDGE_KEY = os.getenv("JUDGE_API_KEY", "")
DEFAULT_JUDGE_MODEL = os.getenv("JUDGE_MODEL", "gpt-4o-mini")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="PaiAgent RAG benchmark tool")
    add_common_args(parser)
    parser.add_argument("--flow-id", "--flow_id", type=int, required=True, help="RAG workflow ID")
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET, help="Evaluation CSV path")
    parser.add_argument("--judge-key", "--judge_key", default=DEFAULT_JUDGE_KEY, help="LLM judge API key; defaults to JUDGE_API_KEY")
    parser.add_argument("--judge-model", "--judge_model", default=DEFAULT_JUDGE_MODEL, help="LLM judge model")
    parser.add_argument("--judge-base-url", default=DEFAULT_JUDGE_BASE_URL, help="OpenAI-compatible judge API base URL")
    return parser.parse_args()


def parse_jsonish(value: Any) -> Any:
    if not isinstance(value, str):
        return value

    try:
        return json.loads(value)
    except json.JSONDecodeError:
        return value


def find_rag_output(result: dict[str, Any]) -> dict[str, Any]:
    for node_result in result.get("nodeResults", []) or []:
        node_name = str(node_result.get("nodeName") or "").lower()
        node_id = str(node_result.get("nodeId") or "").lower()
        if "rag" not in node_name and "rag" not in node_id:
            continue

        output = parse_jsonish(node_result.get("output", {}))
        return output if isinstance(output, dict) else {}

    return {}


def extract_answer(result: dict[str, Any]) -> str:
    output_data = parse_jsonish(result.get("outputData", ""))
    if isinstance(output_data, dict):
        for key in ("answer", "output", "response", "text"):
            value = output_data.get(key)
            if isinstance(value, str):
                return value
        return json.dumps(output_data, ensure_ascii=False)

    return str(output_data or "")


def has_expected_keywords(rag_output: dict[str, Any], expected_keywords: list[str]) -> bool:
    chunks = rag_output.get("retrievedChunks")
    if not isinstance(chunks, list):
        return False

    keywords = [keyword.strip() for keyword in expected_keywords if keyword.strip()]
    if not keywords:
        return False

    for chunk in chunks:
        if not isinstance(chunk, dict):
            continue
        content = f"{chunk.get('content', '')} {chunk.get('contextContent', '')}"
        if all(keyword in content for keyword in keywords):
            return True

    return False


def get_expected_keywords(row: dict[str, Any]) -> list[str]:
    values: list[str] = []
    first_value = row.get("Expected_Chunk_Keywords")
    if isinstance(first_value, str):
        values.extend(first_value.split(","))

    extra_values = row.get(None)
    if isinstance(extra_values, list):
        values.extend(str(value) for value in extra_values)

    return [value.strip() for value in values if value and value.strip()]


def evaluate_llm_answer(
    *,
    question: str,
    expected_answer: str,
    actual_answer: str,
    judge_api_key: str,
    judge_model: str,
    judge_base_url: str,
    timeout: float,
) -> int:
    if not judge_api_key:
        return 0

    prompt = f"""请作为一名严格的评分裁判，评估以下回答是否准确地解答了用户的问题。
满分 5 分，最低 1 分。只输出一个数字，不要输出任何其他内容。

[用户问题]: {question}
[期望答案/事实基准]: {expected_answer}
[实际回答]: {actual_answer}

[评分 (1-5)]:
"""

    response = requests.post(
        f"{normalize_base_url(judge_base_url)}/chat/completions",
        headers={"Authorization": f"Bearer {judge_api_key}"},
        json={
            "model": judge_model,
            "messages": [{"role": "user", "content": prompt}],
            "temperature": 0.0,
        },
        timeout=timeout,
    )
    response.raise_for_status()
    score_text = response.json()["choices"][0]["message"]["content"].strip()
    return max(1, min(5, int(score_text[:1])))


def main() -> None:
    args = parse_args()
    session = requests.Session()

    print("正在登录获取 Token...")
    login(
        session,
        base_url=args.base_url,
        username=args.username,
        password=args.password,
        timeout=args.timeout,
    )

    total_queries = 0
    recalled_queries = 0
    judged_queries = 0
    total_score = 0
    total_latency = 0

    print(f"开始评测工作流 ID: {args.flow_id}")
    print(f"测试集: {args.dataset}")

    with args.dataset.open("r", encoding="utf-8-sig", newline="") as file:
        reader = csv.DictReader(file)
        for row in reader:
            question = row["Question"]
            expected_answer = row["Expected_Answer"]
            expected_keywords = get_expected_keywords(row)

            print(f"\n[Query]: {question}")
            total_queries += 1

            try:
                result = execute_workflow(
                    session,
                    base_url=args.base_url,
                    flow_id=args.flow_id,
                    input_data=question,
                    timeout=args.timeout,
                )
                duration = int(result.get("duration", 0) or 0)
                total_latency += duration

                rag_output = find_rag_output(result)
                if has_expected_keywords(rag_output, expected_keywords):
                    recalled_queries += 1
                    print("[OK] Recall: Hit keywords")
                else:
                    print("[MISS] Recall: Keywords not found")

                actual_answer = extract_answer(result)
                score = evaluate_llm_answer(
                    question=question,
                    expected_answer=expected_answer,
                    actual_answer=actual_answer,
                    judge_api_key=args.judge_key,
                    judge_model=args.judge_model,
                    judge_base_url=args.judge_base_url,
                    timeout=args.timeout,
                )
                if args.judge_key:
                    judged_queries += 1
                    total_score += score
                    print(f"回答评分: {score} / 5")
                else:
                    print("未提供 Judge API Key，跳过大模型评分。")
                print(f"执行耗时: {duration} ms")

            except (PaiAgentApiError, requests.RequestException, KeyError, ValueError) as exc:
                print(f"测试执行异常: {exc}")

    if total_queries == 0:
        print("测试集为空。")
        return

    recall_rate = recalled_queries / total_queries * 100
    avg_latency = total_latency / total_queries

    print("\n" + "=" * 40)
    print("          RAG 评测结果报告          ")
    print("=" * 40)
    print(f"测试总数 (Total Queries)    : {total_queries}")
    print(f"召回率 (Top-K Recall)       : {recall_rate:.1f}%")
    if judged_queries:
        print(f"平均回答准确率 (Accuracy)   : {total_score / judged_queries:.2f} / 5.0")
    print(f"平均响应延迟 (Avg Latency)  : {avg_latency:.0f} ms")
    print("=" * 40)


if __name__ == "__main__":
    main()
