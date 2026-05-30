import { Collapse, Progress, Tag } from 'antd';
import {
  ServiceDeskPayload,
  findServiceDeskPayload,
  formatServiceDeskRaw,
  isRecord,
} from '../utils/serviceDeskOutput';

type CitationRecord = {
  ref?: string;
  sourceName?: string;
  title?: string;
  section?: string;
  sectionTitle?: string;
  pageNumber?: number;
  score?: number;
  url?: string;
  preview?: string;
  content?: string;
  snippet?: string;
};

interface ServiceDeskResultCardProps {
  value: unknown;
  rawTitle?: string;
  rawMaxHeightClassName?: string;
}

const asCitationRecord = (value: unknown): CitationRecord | null => {
  if (typeof value === 'string') {
    return { title: value };
  }
  return isRecord(value) ? (value as CitationRecord) : null;
};

const formatConfidence = (confidence: ServiceDeskPayload['confidence']) => {
  if (typeof confidence === 'number') {
    const percent = confidence <= 1 ? confidence * 100 : confidence;
    return {
      label: `${Math.round(percent)}%`,
      percent: Math.max(0, Math.min(100, percent)),
    };
  }

  if (typeof confidence === 'string' && confidence.trim()) {
    const numeric = Number(confidence);
    if (Number.isFinite(numeric)) {
      const percent = numeric <= 1 ? numeric * 100 : numeric;
      return {
        label: `${Math.round(percent)}%`,
        percent: Math.max(0, Math.min(100, percent)),
      };
    }
    return {
      label: confidence,
      percent: undefined,
    };
  }

  return null;
};

const ACTION_LABELS: Record<string, string> = {
  direct_answer: '直接回答',
  create_ticket: '生成工单',
  escalate_human: '升级人工',
};

const formatNextAction = (nextAction?: string) => {
  if (!nextAction) {
    return '未指定';
  }
  return ACTION_LABELS[nextAction] || nextAction;
};

const renderCitations = (citations?: unknown[]) => {
  if (!Array.isArray(citations) || citations.length === 0) {
    return null;
  }

  return (
    <div className="border-t border-gray-100 pt-3">
      <div className="text-sm font-medium text-gray-900 mb-2">引用来源</div>
      <div className="space-y-2">
        {citations.map((citationValue, index) => {
          const citation = asCitationRecord(citationValue);
          if (!citation) {
            return null;
          }

          const title = citation.ref || citation.sourceName || citation.title || `来源 ${index + 1}`;
          const detailParts = [
            citation.sectionTitle || citation.section,
            typeof citation.pageNumber === 'number' ? `第 ${citation.pageNumber} 页` : '',
            typeof citation.score === 'number' ? `score ${citation.score.toFixed(3)}` : '',
          ].filter(Boolean);
          const preview = citation.preview || citation.snippet || citation.content;

          return (
            <div key={`${title}-${index}`} className="rounded border border-gray-200 bg-white p-3 text-sm">
              <div className="font-medium text-gray-900 break-words">
                {citation.url ? (
                  <a href={citation.url} target="_blank" rel="noreferrer">
                    {title}
                  </a>
                ) : (
                  title
                )}
              </div>
              {detailParts.length > 0 && (
                <div className="text-xs text-gray-500 mt-1">{detailParts.join(' / ')}</div>
              )}
              {preview && (
                <div className="text-gray-600 mt-2 whitespace-pre-wrap break-words line-clamp-3">{preview}</div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
};

const ServiceDeskResultCard = ({
  value,
  rawTitle = 'Raw JSON',
  rawMaxHeightClassName = 'max-h-48',
}: ServiceDeskResultCardProps) => {
  const payload = findServiceDeskPayload(value);

  if (!payload) {
    return (
      <pre className={`bg-gray-50 p-2 rounded text-xs overflow-auto ${rawMaxHeightClassName}`}>
        {formatServiceDeskRaw(value)}
      </pre>
    );
  }

  const confidence = formatConfidence(payload.confidence);
  const resolvedColor = payload.resolved === false ? 'orange' : payload.resolved === true ? 'green' : 'default';
  const resolvedText = payload.resolved === false ? '需后续处理' : payload.resolved === true ? '可自助解决' : '未标记';
  const nextActionText = formatNextAction(payload.nextAction);
  const shouldEscalate = payload.nextAction === 'escalate_human' || Boolean(payload.escalationReason);

  return (
    <div className="space-y-3">
      <div className="rounded border border-gray-200 bg-white p-4">
        <div className="flex flex-wrap items-center gap-2 mb-3">
          <Tag color={resolvedColor}>{resolvedText}</Tag>
          <Tag color="blue">下一步：{nextActionText}</Tag>
          <Tag color={shouldEscalate ? 'orange' : 'default'}>
            建议人工：{shouldEscalate ? '是' : '否'}
          </Tag>
        </div>

        {payload.answer && (
          <div>
            <div className="text-sm font-medium text-gray-900 mb-1">答复</div>
            <div className="text-sm text-gray-800 whitespace-pre-wrap break-words">{payload.answer}</div>
          </div>
        )}

        {confidence && (
          <div className="mt-4">
            <div className="flex items-center justify-between text-sm mb-1">
              <span className="font-medium text-gray-900">置信度</span>
              <span className="text-gray-600">{confidence.label}</span>
            </div>
            {typeof confidence.percent === 'number' ? (
              <Progress percent={confidence.percent} size="small" showInfo={false} />
            ) : (
              <div className="text-sm text-gray-600">{confidence.label}</div>
            )}
          </div>
        )}

        {payload.ticketSummary && (
          <div className="mt-4 rounded bg-gray-50 border border-gray-100 p-3">
            <div className="text-sm font-medium text-gray-900 mb-1">工单摘要</div>
            <div className="text-sm text-gray-700 whitespace-pre-wrap break-words">{payload.ticketSummary}</div>
          </div>
        )}

        {payload.escalationReason && (
          <div className="mt-3 rounded bg-orange-50 border border-orange-100 p-3">
            <div className="text-sm font-medium text-orange-900 mb-1">升级原因</div>
            <div className="text-sm text-orange-800 whitespace-pre-wrap break-words">{payload.escalationReason}</div>
          </div>
        )}

        {renderCitations(payload.citations)}
      </div>

      <Collapse
        size="small"
        items={[
          {
            key: 'raw',
            label: rawTitle,
            children: (
              <pre className={`bg-gray-50 p-2 rounded text-xs overflow-auto ${rawMaxHeightClassName}`}>
                {formatServiceDeskRaw(value)}
              </pre>
            ),
          },
        ]}
      />
    </div>
  );
};

export default ServiceDeskResultCard;
