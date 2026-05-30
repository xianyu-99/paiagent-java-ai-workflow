import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Card, Input, Spin, Typography, message } from 'antd';
import { SendOutlined } from '@ant-design/icons';
import { useParams } from 'react-router-dom';
import AudioPlayer from '../components/AudioPlayer';
import ServiceDeskResultCard from '../components/ServiceDeskResultCard';
import {
  ExecutionNodeResult,
  ExecutionResponse,
  PublishedWorkflowInfo,
  executePublishedWorkflow,
  getPublishedWorkflow,
} from '../api/workflow';

const { Text } = Typography;

type AudioOutput = {
  audioUrl: string;
  fileName?: string;
};

const parseMaybeJson = (value: unknown): unknown => {
  if (typeof value !== 'string') {
    return value;
  }
  try {
    return JSON.parse(value);
  } catch {
    return value;
  }
};

const asRecord = (value: unknown): Record<string, unknown> | null => {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  return null;
};

const extractAudioOutput = (result: ExecutionResponse | null): AudioOutput | null => {
  if (!result) {
    return null;
  }

  const candidates: unknown[] = [parseMaybeJson(result.outputData)];
  const nodeResults = result.nodeResults || [];
  for (let i = nodeResults.length - 1; i >= 0; i -= 1) {
    candidates.push(parseMaybeJson(nodeResults[i].output));
  }

  for (const candidate of candidates) {
    const record = asRecord(candidate);
    const audioUrl = record?.audioUrl;
    if (typeof audioUrl === 'string' && audioUrl) {
      return {
        audioUrl,
        fileName: typeof record.fileName === 'string' ? record.fileName : undefined,
      };
    }
  }

  return null;
};

const pickResultOutput = (result: ExecutionResponse | null): unknown => {
  if (!result) {
    return '';
  }

  if (result.status !== 'SUCCESS' && result.errorMessage) {
    return result.errorMessage;
  }

  const output = parseMaybeJson(result.outputData);
  if (output !== undefined && output !== null && output !== '') {
    return output;
  }

  const successfulNodes = (result.nodeResults || []).filter((node: ExecutionNodeResult) => node.status === 'SUCCESS');
  const lastNode = successfulNodes[successfulNodes.length - 1];
  return parseMaybeJson(lastNode?.output) || '';
};

const PublishedWorkflowPage = () => {
  const { shareKey } = useParams<{ shareKey: string }>();
  const [workflow, setWorkflow] = useState<PublishedWorkflowInfo | null>(null);
  const [inputData, setInputData] = useState('');
  const [result, setResult] = useState<ExecutionResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    const loadWorkflow = async () => {
      if (!shareKey) {
        setError('发布链接无效');
        setLoading(false);
        return;
      }

      try {
        const response = await getPublishedWorkflow(shareKey);
        if (response.code === 200) {
          setWorkflow(response.data);
        } else {
          setError(response.message || '发布链接不可用');
        }
      } catch {
        setError('发布链接不可用');
      } finally {
        setLoading(false);
      }
    };

    loadWorkflow();
  }, [shareKey]);

  const audioOutput = useMemo(() => extractAudioOutput(result), [result]);
  const resultOutput = useMemo(() => pickResultOutput(result), [result]);

  const handleRun = async () => {
    if (!shareKey) {
      return;
    }
    if (!inputData.trim()) {
      message.warning('请输入内容');
      return;
    }

    setRunning(true);
    setResult(null);
    setError('');
    try {
      const response = await executePublishedWorkflow(shareKey, inputData);
      if (response.code === 200) {
        setResult(response.data);
        if (response.data?.status !== 'SUCCESS') {
          setError(response.data?.errorMessage || '执行失败');
        }
      } else {
        setError(response.message || '执行失败');
      }
    } catch {
      setError('执行失败');
    } finally {
      setRunning(false);
    }
  };

  if (loading) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center">
        <Spin size="large" />
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <div className="bg-white border-b px-6 py-4">
        <div className="max-w-5xl mx-auto flex items-center justify-between">
          <div>
            <h1 className="text-xl font-semibold text-gray-900">{workflow?.title || 'PaiAgent 工作流'}</h1>
            {(workflow?.nodeSummary || workflow?.description) && (
              <p className="text-sm text-gray-500 mt-1">{workflow.nodeSummary || workflow.description}</p>
            )}
          </div>
          <Text type="secondary">PaiAgent</Text>
        </div>
      </div>

      <main className="max-w-5xl mx-auto p-6 grid grid-cols-1 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)] gap-4">
        <Card title="输入" className="h-fit">
          {error && <Alert className="mb-4" type="error" showIcon message={error} />}
          <Input.TextArea
            value={inputData}
            onChange={(event) => setInputData(event.target.value)}
            placeholder="请输入企业服务台问题，例如：我连不上公司 VPN，提示证书过期，怎么办？"
            rows={12}
          />
          <Button
            type="primary"
            className="mt-4"
            icon={<SendOutlined />}
            loading={running}
            onClick={handleRun}
            block
          >
            运行
          </Button>
        </Card>

        <Card title="输出" className="min-h-[360px]">
          {running && (
            <div className="h-56 flex items-center justify-center">
              <Spin />
            </div>
          )}

          {!running && result && (
            <>
              <ServiceDeskResultCard
                value={resultOutput}
                rawTitle="Raw output JSON"
                rawMaxHeightClassName="max-h-[520px]"
              />
              {audioOutput && (
                <div className="mt-3">
                  <AudioPlayer audioUrl={audioOutput.audioUrl} fileName={audioOutput.fileName} />
                </div>
              )}
            </>
          )}

          {!running && !result && !error && (
            <div className="h-56 flex items-center justify-center text-gray-400">
              暂无输出
            </div>
          )}
        </Card>
      </main>
    </div>
  );
};

export default PublishedWorkflowPage;
