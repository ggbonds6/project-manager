/**
 * 问答作用域选择器（顶部"当前范围"）
 * ----------------------------------------------------------------
 * 方案 §3.2 / §8.3 把"范围显式可见且可切换"列为**必须**（安全相关，不可省）：
 *  - 默认跟随当前页面：路径 `/projects/:id` 时默认该项目，其它页面默认"全部"；
 *  - 用户手动切过之后，本次会话内尊重手动选择（同一路径重复渲染不再被拉回默认）；
 *  - 切到别的项目页时，重新按新页面给默认值。
 *
 * 注意：这里选的只是**提问范围**，真正的权限过滤在主系统后端（§4 边界 3），
 * 前端不承担任何越权拦截职责。
 */
import { useEffect, useState } from 'react';
import { Select, Space, Tag, Tooltip, Typography } from 'antd';
import { GlobalOutlined, ProjectOutlined } from '@ant-design/icons';
import { useLocation } from 'react-router-dom';
import { useAiProjects } from '@/hooks/useAiProjects';

/** null = 全部可访问范围 */
export type AiScopeProjectId = number | null;

export interface AiScopeState {
  projectId: AiScopeProjectId;
  setProjectId: (id: AiScopeProjectId) => void;
  /** 当前页面本身代表的项目（仅 /projects/:id 有值），用于提示"已跟随本页" */
  pageProjectId: number | null;
}

/** 从路径解析当前项目 id：仅 `/projects/:id` 视为项目上下文 */
function parsePageProjectId(pathname: string): number | null {
  const m = /^\/projects\/(\d+)(?:\/|$)/.exec(pathname);
  if (!m) return null;
  const n = Number(m[1]);
  return Number.isFinite(n) ? n : null;
}

export function useAiScope(): AiScopeState {
  const location = useLocation();
  const pageProjectId = parsePageProjectId(location.pathname);
  const [projectId, setProjectId] = useState<AiScopeProjectId>(pageProjectId);

  // 页面上下文变化时重新取默认值；同一路径内保留用户的手动切换
  useEffect(() => {
    setProjectId(pageProjectId);
  }, [pageProjectId]);

  return { projectId, setProjectId, pageProjectId };
}

interface Props {
  scope: AiScopeState;
  /** 紧凑模式（悬浮窗窄，标签换行会挤） */
  compact?: boolean;
}

export default function AiScopePicker({ scope, compact }: Props) {
  const { projects } = useAiProjects();
  const { projectId, setProjectId, pageProjectId } = scope;

  const options = [
    { value: 0, label: '全部可访问范围' },
    ...projects.map((p) => ({ value: p.id, label: p.name })),
  ];

  const current = projectId === null ? null : projects.find((p) => p.id === projectId);
  // 选中的项目不在前 500 条里时，至少把 id 显示出来，不要显示成"全部"（那样会误导权限范围）
  const currentLabel =
    projectId === null ? '全部可访问范围' : current?.name || `项目 #${projectId}`;

  return (
    <Space size={6} wrap={!compact} style={{ fontSize: 12 }}>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        当前范围：
      </Typography.Text>
      <Tooltip
        title={
          pageProjectId !== null && projectId === pageProjectId
            ? '已跟随当前项目页；可切换为其它项目或全部范围'
            : '回答只会基于该范围内的文档与附件'
        }
      >
        <Select
          size="small"
          style={{ minWidth: compact ? 160 : 200, maxWidth: 280 }}
          value={projectId === null ? 0 : projectId}
          onChange={(v) => setProjectId(v === 0 ? null : Number(v))}
          options={options}
          showSearch
          optionFilterProp="label"
          popupMatchSelectWidth={280}
        />
      </Tooltip>
      {projectId === null ? (
        <Tag icon={<GlobalOutlined />} color="blue" style={{ marginInlineEnd: 0 }}>
          全部
        </Tag>
      ) : (
        <Tag icon={<ProjectOutlined />} color="geekblue" style={{ marginInlineEnd: 0 }}>
          {projectId === pageProjectId ? '跟随本页' : '已指定项目'}
        </Tag>
      )}
      {compact ? null : (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          （{currentLabel}）
        </Typography.Text>
      )}
    </Space>
  );
}
