export type ServiceDeskPayload = {
  answer?: string;
  citations?: unknown[];
  confidence?: number | string;
  resolved?: boolean;
  nextAction?: string;
  ticketSummary?: string;
  escalationReason?: string;
};

const SERVICE_DESK_KEYS = [
  'answer',
  'citations',
  'confidence',
  'resolved',
  'nextAction',
  'ticketSummary',
  'escalationReason',
] as const;

export const isRecord = (value: unknown): value is Record<string, unknown> => {
  return !!value && typeof value === 'object' && !Array.isArray(value);
};

export const parseMaybeJson = (value: unknown): unknown => {
  if (typeof value !== 'string') {
    return value;
  }

  const trimmed = value.trim();
  if (!trimmed || (!trimmed.startsWith('{') && !trimmed.startsWith('['))) {
    return value;
  }

  try {
    return JSON.parse(trimmed) as unknown;
  } catch {
    return value;
  }
};

const collectRecords = (value: unknown, depth = 0): Record<string, unknown>[] => {
  if (depth > 4) {
    return [];
  }

  const parsed = parseMaybeJson(value);
  if (Array.isArray(parsed)) {
    return parsed.flatMap((item) => collectRecords(item, depth + 1));
  }

  if (!isRecord(parsed)) {
    return [];
  }

  const nestedKeys = ['output', 'data', 'result', 'payload', 'outputData'];
  const nestedRecords = nestedKeys.flatMap((key) => collectRecords(parsed[key], depth + 1));
  return [parsed, ...nestedRecords];
};

export const findServiceDeskPayload = (value: unknown): ServiceDeskPayload | null => {
  for (const record of collectRecords(value)) {
    const matchedKeyCount = SERVICE_DESK_KEYS.filter((key) => key in record).length;
    const hasBusinessContent =
      typeof record.answer === 'string' ||
      typeof record.ticketSummary === 'string' ||
      typeof record.escalationReason === 'string';

    if (matchedKeyCount >= 2 && hasBusinessContent) {
      return record as ServiceDeskPayload;
    }

    const hasLegacyBusinessContent =
      typeof record.answer === 'string' &&
      (
        Array.isArray(record.sources) ||
        Array.isArray(record.nextActions) ||
        isRecord(record.ticket) ||
        isRecord(record.escalation)
      );

    if (hasLegacyBusinessContent) {
      return normalizeLegacyPayload(record);
    }
  }

  return null;
};

const normalizeLegacyPayload = (record: Record<string, unknown>): ServiceDeskPayload => {
  const escalation = isRecord(record.escalation) ? record.escalation : {};
  const ticket = isRecord(record.ticket) ? record.ticket : {};
  const nextActions = Array.isArray(record.nextActions) ? record.nextActions : [];
  const sources = Array.isArray(record.sources) ? record.sources : [];
  const escalationRequired = escalation.required === true;

  return {
    answer: typeof record.answer === 'string' ? record.answer : undefined,
    citations: Array.isArray(record.citations) ? record.citations : sources,
    confidence: typeof record.confidence === 'number' || typeof record.confidence === 'string'
      ? record.confidence
      : undefined,
    resolved: typeof record.resolved === 'boolean' ? record.resolved : !escalationRequired,
    nextAction: typeof record.nextAction === 'string'
      ? record.nextAction
      : typeof nextActions[0] === 'string'
        ? nextActions[0]
        : escalationRequired
          ? 'escalate_human'
          : undefined,
    ticketSummary: typeof record.ticketSummary === 'string'
      ? record.ticketSummary
      : typeof ticket.summary === 'string'
        ? ticket.summary
        : undefined,
    escalationReason: typeof record.escalationReason === 'string'
      ? record.escalationReason
      : typeof escalation.reason === 'string'
        ? escalation.reason
        : undefined,
  };
};

export const formatServiceDeskRaw = (value: unknown) => {
  const parsed = parseMaybeJson(value);
  if (typeof parsed === 'string') {
    return parsed;
  }
  return JSON.stringify(parsed, null, 2);
};
