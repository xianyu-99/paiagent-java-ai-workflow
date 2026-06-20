#!/usr/bin/env node

const DEFAULT_BACKEND_URL = "http://localhost:8085";
const DEFAULT_USERNAME = "admin";
const DEFAULT_PASSWORD = "admin123";

const repeatFaqQuestions = [
  "请假申请超过 3 天需要谁审批？",
  "公司 VPN 证书过期怎么办？",
  "差旅报销需要哪些发票材料？",
  "新员工电脑如何申请？",
  "客户工单响应 SLA 是多久？",
];

const uncachedQuestionBases = [
  "请假申请超过三天审批流程",
  "公司 VPN 证书过期处理",
  "差旅报销材料要求",
  "新员工电脑申请流程",
  "客户工单 SLA 规则",
];

function parseArgs(argv) {
  const args = {
    backendUrl: DEFAULT_BACKEND_URL,
    username: DEFAULT_USERNAME,
    password: DEFAULT_PASSWORD,
    workflowId: null,
    total: 120,
    concurrency: 12,
    scenario: "repeat-faq",
    jsonOnly: false,
  };

  const aliases = {
    "--backend-url": "backendUrl",
    "--username": "username",
    "--password": "password",
    "--workflow-id": "workflowId",
    "--total": "total",
    "--concurrency": "concurrency",
    "--scenario": "scenario",
  };

  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (token === "--json-only") {
      args.jsonOnly = true;
      continue;
    }
    const key = aliases[token];
    if (!key) {
      throw new Error(`Unknown argument: ${token}`);
    }
    const value = argv[i + 1];
    if (value == null || value.startsWith("--")) {
      throw new Error(`Missing value for ${token}`);
    }
    args[key] = value;
    i += 1;
  }

  args.backendUrl = String(args.backendUrl).replace(/\/+$/, "");
  args.total = Math.max(1, Number.parseInt(args.total, 10));
  args.concurrency = Math.max(1, Number.parseInt(args.concurrency, 10));
  args.workflowId = args.workflowId == null || args.workflowId === ""
    ? null
    : Number.parseInt(args.workflowId, 10);

  if (!Number.isFinite(args.total) || !Number.isFinite(args.concurrency)) {
    throw new Error("total and concurrency must be valid integers");
  }
  if (args.workflowId != null && !Number.isFinite(args.workflowId)) {
    throw new Error("workflow-id must be a valid integer");
  }
  if (!["repeat-faq", "mostly-uncached"].includes(args.scenario)) {
    throw new Error("scenario must be repeat-faq or mostly-uncached");
  }
  return args;
}

async function jsonFetch(url, options = {}) {
  const response = await fetch(url, options);
  const text = await response.text();
  let body;
  try {
    body = text ? JSON.parse(text) : null;
  } catch (error) {
    throw new Error(`Invalid JSON from ${url}: ${text.slice(0, 200)}`);
  }
  if (!response.ok) {
    throw new Error(`HTTP ${response.status} from ${url}: ${text.slice(0, 300)}`);
  }
  return body;
}

function assertResult(result, operation) {
  if (!result || result.code !== 200) {
    throw new Error(`${operation} failed: code=${result?.code}, message=${result?.message}`);
  }
}

function parseFlowData(flowData) {
  if (!flowData) {
    return null;
  }
  if (typeof flowData === "string") {
    try {
      return JSON.parse(flowData);
    } catch {
      return null;
    }
  }
  return flowData;
}

function isRetrievalOnlyWorkflow(workflow) {
  const flow = parseFlowData(workflow.flowData);
  const nodes = Array.isArray(flow?.nodes) ? flow.nodes : [];
  return nodes.some((node) => {
    const data = node?.data ?? {};
    return data.retrievalOnly === true || data.label === "RAG retrieval only";
  });
}

async function findRetrievalWorkflowId(args, headers) {
  if (args.workflowId != null) {
    return args.workflowId;
  }

  const list = await jsonFetch(`${args.backendUrl}/api/workflows`, { headers });
  assertResult(list, "List workflows");
  const workflows = Array.isArray(list.data) ? list.data : [];

  for (const workflow of workflows) {
    if (isRetrievalOnlyWorkflow(workflow)) {
      return workflow.id;
    }
  }

  for (const workflow of workflows) {
    const detail = await jsonFetch(`${args.backendUrl}/api/workflows/${workflow.id}`, { headers });
    assertResult(detail, `Get workflow ${workflow.id}`);
    if (isRetrievalOnlyWorkflow(detail.data)) {
      return workflow.id;
    }
  }

  throw new Error("Cannot find a RAG retrieval-only workflow. Pass --workflow-id explicitly.");
}

function questionFor(scenario, index, runId) {
  if (scenario === "repeat-faq") {
    return repeatFaqQuestions[index % repeatFaqQuestions.length];
  }
  const base = uncachedQuestionBases[index % uncachedQuestionBases.length];
  return `${base}，压测批次 ${runId}，样本 ${index}`;
}

function percentile(sortedValues, percentileValue) {
  if (sortedValues.length === 0) {
    return null;
  }
  const index = Math.min(sortedValues.length - 1, Math.ceil(sortedValues.length * percentileValue) - 1);
  return sortedValues[index];
}

function buildSummary(results, startedAt, endedAt, args, workflowId) {
  const successLatencies = results
    .filter((result) => result.ok)
    .map((result) => result.ms)
    .sort((left, right) => left - right);
  const failures = results.filter((result) => !result.ok);
  const durationMs = Math.round(endedAt - startedAt);

  return {
    workflowId,
    scenario: args.scenario,
    total: args.total,
    concurrency: args.concurrency,
    success: successLatencies.length,
    failed: failures.length,
    successRate: Number((successLatencies.length / args.total).toFixed(4)),
    p50: percentile(successLatencies, 0.5),
    p95: percentile(successLatencies, 0.95),
    p99: percentile(successLatencies, 0.99),
    min: successLatencies[0] ?? null,
    max: successLatencies.at(-1) ?? null,
    durationMs,
    throughputRps: Number((args.total / (durationMs / 1000)).toFixed(2)),
    failureSamples: failures.slice(0, 5),
  };
}

async function runBenchmark(args) {
  const login = await jsonFetch(`${args.backendUrl}/api/auth/login`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ username: args.username, password: args.password }),
  });
  assertResult(login, "Login");

  const token = login.data?.token;
  if (!token) {
    throw new Error("Login response misses token");
  }

  const headers = {
    authorization: `Bearer ${token}`,
    "content-type": "application/json",
  };
  const workflowId = await findRetrievalWorkflowId(args, headers);
  const runId = Date.now().toString(36);
  const results = [];
  let next = 0;
  const startedAt = performance.now();

  async function worker() {
    while (true) {
      const index = next;
      next += 1;
      if (index >= args.total) {
        return;
      }

      const inputData = questionFor(args.scenario, index, runId);
      const requestStartedAt = performance.now();
      try {
        const result = await jsonFetch(`${args.backendUrl}/api/workflows/${workflowId}/execute`, {
          method: "POST",
          headers,
          body: JSON.stringify({ inputData }),
        });
        const ms = Math.round(performance.now() - requestStartedAt);
        const ok = result.code === 200 && result.data?.status === "SUCCESS";
        results.push({
          ok,
          ms,
          status: result.data?.status,
          code: result.code,
          message: result.message,
          errorMessage: result.data?.errorMessage,
        });
      } catch (error) {
        results.push({
          ok: false,
          ms: Math.round(performance.now() - requestStartedAt),
          error: String(error?.message ?? error),
        });
      }
    }
  }

  await Promise.all(Array.from({ length: args.concurrency }, () => worker()));
  const endedAt = performance.now();
  return buildSummary(results, startedAt, endedAt, args, workflowId);
}

function printSummary(summary, jsonOnly) {
  if (!jsonOnly) {
    console.log("RAG retrieval-only benchmark");
    console.log(`workflowId: ${summary.workflowId}`);
    console.log(`scenario: ${summary.scenario}`);
    console.log(`requests: ${summary.total}, concurrency: ${summary.concurrency}`);
    console.log(`success: ${summary.success}, failed: ${summary.failed}, successRate: ${(summary.successRate * 100).toFixed(2)}%`);
    console.log(`latency(ms): p50=${summary.p50}, p95=${summary.p95}, p99=${summary.p99}, min=${summary.min}, max=${summary.max}`);
    console.log(`durationMs: ${summary.durationMs}, throughputRps: ${summary.throughputRps}`);
    if (summary.failureSamples.length > 0) {
      console.log("failureSamples:");
      console.log(JSON.stringify(summary.failureSamples, null, 2));
    }
    console.log("");
  }
  console.log(JSON.stringify(summary, null, 2));
}

try {
  const args = parseArgs(process.argv.slice(2));
  const summary = await runBenchmark(args);
  printSummary(summary, args.jsonOnly);
  if (summary.failed > 0) {
    process.exitCode = 1;
  }
} catch (error) {
  console.error(error?.stack ?? error);
  process.exitCode = 1;
}
