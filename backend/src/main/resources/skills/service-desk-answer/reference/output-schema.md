# 统一 JSON 输出 Schema

所有企业服务台主线 LLM 节点都必须只输出一个 JSON 对象：

```json
{
  "answer": "面向员工的简明答复",
  "citations": ["资料名称或来源编号"],
  "confidence": 0.8,
  "resolved": true,
  "nextAction": "direct_answer",
  "ticketSummary": "",
  "escalationReason": ""
}
```

- `answer`：给员工、客服或销售同事看的最终答复。
- `citations`：知识库来源列表。
- `confidence`：0 到 1 的依据充分程度。
- `resolved`：是否建议先按答案自助处理。
- `nextAction`：`direct_answer`、`create_ticket`、`escalate_human` 三选一。
- `ticketSummary`：工单摘要。
- `escalationReason`：升级原因。
