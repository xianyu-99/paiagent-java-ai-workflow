import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { message, Select, Spin, Tag, Tooltip } from 'antd';
import { BookOutlined } from '@ant-design/icons';
import { getSkills, SkillSummary } from '../api/skill';

interface SkillSelectorProps {
  value?: string;
  onChange?: (value: string | undefined) => void;
  disabled?: boolean;
}

const SkillSelector: React.FC<SkillSelectorProps> = ({ value, onChange, disabled }) => {
  const [skills, setSkills] = useState<SkillSummary[]>([]);
  const [loading, setLoading] = useState(false);

  const loadSkills = useCallback(async () => {
    setLoading(true);
    try {
      const response = await getSkills();
      if (response.code === 200 && response.data) {
        setSkills(response.data);
      } else {
        message.error(response.message || '加载技能列表失败');
      }
    } catch (error) {
      console.error('Failed to load skills:', error);
      message.error('加载技能列表失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadSkills();
  }, [loadSkills]);

  const skillOptions = useMemo(
    () => skills.map((skill) => ({
      ...skill,
      referenceCount: skill.referenceCount ?? 0,
      searchText: `${skill.name} ${skill.description}`,
    })),
    [skills],
  );

  const handleChange = (newValue: string | undefined) => {
    onChange?.(newValue);
  };

  return (
    <Spin spinning={loading}>
      <Select
        value={value}
        onChange={handleChange}
        disabled={disabled}
        placeholder="选择一个技能（可选）"
        allowClear
        showSearch
        optionFilterProp="label"
        filterOption={(input, option) =>
          String(option?.label ?? option?.value ?? '')
            .toLowerCase()
            .includes(input.toLowerCase())
        }
        notFoundContent={loading ? <Spin size="small" /> : '暂无可用技能'}
        style={{ width: '100%' }}
        suffixIcon={<BookOutlined />}
      >
        {skillOptions.map((skill) => (
          <Select.Option key={skill.name} value={skill.name} label={skill.searchText}>
            <Tooltip title={skill.description} placement="right">
              <div className="flex items-center gap-2 min-w-0">
                <Tag color="blue" className="shrink-0">{skill.name}</Tag>
                {skill.referenceCount > 0 && (
                  <Tag className="shrink-0">{skill.referenceCount} 份参考</Tag>
                )}
                <span className="text-gray-500 text-xs truncate">
                  {skill.description}
                </span>
              </div>
            </Tooltip>
          </Select.Option>
        ))}
      </Select>
    </Spin>
  );
};

export default SkillSelector;
