from __future__ import annotations

import argparse
import os
from typing import Any

import requests


DEFAULT_BASE_URL = os.getenv("PAIAGENT_BASE_URL", "http://localhost:8084/api")
DEFAULT_USERNAME = os.getenv("PAIAGENT_USERNAME", "admin")
DEFAULT_PASSWORD = os.getenv("PAIAGENT_PASSWORD", "admin123")
DEFAULT_TIMEOUT_SECONDS = float(os.getenv("PAIAGENT_TIMEOUT_SECONDS", "30"))


class PaiAgentApiError(RuntimeError):
    pass


def normalize_base_url(value: str) -> str:
    return value.rstrip("/")


def add_common_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help="PaiAgent API base URL")
    parser.add_argument("--username", default=DEFAULT_USERNAME, help="Login username")
    parser.add_argument("--password", default=DEFAULT_PASSWORD, help="Login password")
    parser.add_argument(
        "--timeout",
        type=float,
        default=DEFAULT_TIMEOUT_SECONDS,
        help="HTTP timeout in seconds",
    )


def request_json(
    session: requests.Session,
    method: str,
    url: str,
    *,
    timeout: float,
    **kwargs: Any,
) -> dict[str, Any]:
    try:
        response = session.request(method, url, timeout=timeout, **kwargs)
    except requests.RequestException as exc:
        raise PaiAgentApiError(f"Request failed: {method} {url}: {exc}") from exc

    try:
        payload = response.json()
    except ValueError as exc:
        raise PaiAgentApiError(
            f"Response is not JSON: {method} {url}: HTTP {response.status_code}: {response.text[:500]}"
        ) from exc

    if response.status_code >= 400:
        message = payload.get("message") if isinstance(payload, dict) else None
        raise PaiAgentApiError(
            f"HTTP {response.status_code}: {message or response.text[:500]}"
        )

    if not isinstance(payload, dict):
        raise PaiAgentApiError(f"Unexpected JSON payload type: {type(payload).__name__}")

    if payload.get("code") != 200:
        raise PaiAgentApiError(str(payload.get("message") or payload))

    return payload


def login(
    session: requests.Session,
    *,
    base_url: str,
    username: str,
    password: str,
    timeout: float,
) -> str:
    payload = request_json(
        session,
        "POST",
        f"{normalize_base_url(base_url)}/auth/login",
        timeout=timeout,
        json={"username": username, "password": password},
    )

    token = (payload.get("data") or {}).get("token")
    if not token:
        raise PaiAgentApiError("Login response does not include access token")

    session.headers.update({"Authorization": f"Bearer {token}"})
    return token


def upload_document(
    session: requests.Session,
    *,
    base_url: str,
    kb_id: int,
    file_name: str,
    content: str,
    timeout: float,
) -> dict[str, Any]:
    payload = request_json(
        session,
        "POST",
        f"{normalize_base_url(base_url)}/knowledge-bases/{kb_id}/documents",
        timeout=timeout,
        json={"fileName": file_name, "content": content},
    )
    return payload.get("data") or {}


def execute_workflow(
    session: requests.Session,
    *,
    base_url: str,
    flow_id: int,
    input_data: str,
    timeout: float,
) -> dict[str, Any]:
    payload = request_json(
        session,
        "POST",
        f"{normalize_base_url(base_url)}/workflows/{flow_id}/execute",
        timeout=timeout,
        json={"inputData": input_data},
    )
    return payload.get("data") or {}
