/**
 * 「AI 与知识库」管理页（路由 `/ai`）
 * ----------------------------------------------------------------
 * 本轮为 P0：页签只做**文档库 / 任务队列 / 服务自检 / 问答助手**四块，且四态（加载/空/错误/有数据）齐全。
 * 检索调试、评测与指标属 P1/P2（方案 §5、§8.3），本轮**不放占位页签**——放个点不动的按钮比没有更糟。
 *
 * 数据来源全部是主系统后端的 `/api/ai/*`（§9 冻结契约），前端不直连 AI 服务。
 * 一处刻意的行为：AI 服务不可用时，文档库/任务队列显示"不可用"提示，而不是显示成"暂无数据"——
 * 方案 §6 要求"不让用户以为没找到"。
 */
import { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  Input,
  Modal,
  Popconfirm,
  Progress,
  Row,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  DeleteOutlined,
  ExperimentOutlined,
  ReloadOutlined,
  RobotOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { aiApi, clearDocAttachmentCache } from '@/api/ai';
import AiChatPanel from '@/components/ai/AiChatPanel';
import { useAttachmentPreview, buildAttachmentPreviewPath } from '@/components/ai/useAttachmentPreview';
import { useAiScope } from '@/components/ai/AiScopePicker';
import { useAiProjects } from '@/hooks/useAiProjects';
import { useAuth } from '@/store/auth';
import { fmtDateTime, fmtFileSize } from '@/utils/format';
import {
  aiIndexStatusMeta,
  aiTaskStatusMeta,
  aiVectorBackendText,
  AiDocument,
  AiHealth,
  AiTask,
  AI_TASK_STATUS_OPTIONS,
} from '@/types/ai';

export default function AiPage() {
  const { user } = useAuth();
  const canManage = user?.role === 'ADMIN' || user?.role === 'MANAGER';
  // 与 MainLayout 菜单同一判定（前端隐藏只是顺手，真正拦截在后端）
  const scope = useAiScope();

  return (
    <Tabs
      defaultActiveKey="documents"
      items={[
        { key: 'documents', label: '文档库', children: <DocumentsTab canManage={canManage} /> },
        { key: 'tasks', label: '任务队列', children: <TasksTab canManage={canManage} /> },
        { key: 'health', label: '服务自检', children: <HealthTab /> },
        {
          key: 'chat',
          label: (
            <span>
              <RobotOutlined /> 问答助手
            </span>
          ),
          // 与右下角悬浮窗共用同一个面板组件（§10：不许写两份）
          children: (
            <Card size="small" styles={{ body: { padding: 0 } }}>
              <AiChatPanel scope={scope} height="calc(100vh - 220px)" />
            </Card>
          ),
        },
      ]}
    />
  );
}

/* ==================== ① 文档库 ==================== */

function DocumentsTab({ canManage }: { canManage: boolean }) {
  const navigate = useNavigate();
  const { projects } = useAiProjects();
  /** 附件原文预览（文档库的"查看"与问答引用共用同一实现与同一份 docId→附件 映射缓存） */
  const preview = useAttachmentPreview();
  const [rows, setRows] = useState<AiDocument[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [keyword, setKeyword] = useState('');
  const [appliedKeyword, setAppliedKeyword] = useState('');
  const [projectId, setProjectId] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  /** 加载失败原因：用于区分"真的没文档"和"没读到" */
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await aiApi.documents({
        page,
        size,
        keyword: appliedKeyword || undefined,
        projectId: projectId ?? undefined,
      });
      setRows(res.records || []);
      setTotal(res.total || 0);
      setError(null);
    } catch (e) {
      setRows([]);
      setTotal(0);
      setError((e as Error).message || '文档库读取失败');
    } finally {
      setLoading(false);
    }
  }, [page, size, appliedKeyword, projectId]);

  useEffect(() => {
    void load();
  }, [load]);

  const doDelete = (row: AiDocument) => {
    Modal.confirm({
      title: '删除入库文档',
      content: `确定删除「${row.filename}」在知识库中的切片与向量吗？主系统的附件本身不会被删除，删除后可用"重新解析"再次入库。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await aiApi.deleteDocument(row.docId);
        // 文档已在知识库侧删除：清掉 docId→附件 的映射缓存，避免之后还照旧去打开
        clearDocAttachmentCache();
        message.success('已从知识库删除');
        void load();
      },
    });
  };

  /** 查看解析结果：就地打开附件原文（与问答出处的预览同一实现） */
  const doPreview = (row: AiDocument) => {
    void preview.open({
      attachmentId: row.attachmentId,
      docId: row.docId,
      filename: row.filename,
    });
  };

  const columns: ColumnsType<AiDocument> = [
    {
      title: '文件名',
      dataIndex: 'filename',
      ellipsis: true,
      render: (v: string, row) => (
        <a onClick={() => doPreview(row)} title="查看解析结果（打开附件原文）">
          {v || '(未命名)'}
        </a>
      ),
    },
    {
      title: '来源项目',
      dataIndex: 'projectName',
      width: 180,
      ellipsis: true,
      render: (v: string | null | undefined, row) =>
        row.projectId ? (
          <a
            onClick={() =>
              navigate(
                row.attachmentId
                  ? // 带上附件 id：跳到项目页后会自动打开该附件预览（见 useAttachmentPreview）
                    buildAttachmentPreviewPath(row.projectId!, row.attachmentId, row.filename)
                  : `/projects/${row.projectId}?tab=attach`,
              )
            }
          >
            {v || `项目 #${row.projectId}`}
          </a>
        ) : (
          v || '-'
        ),
    },
    { title: '页数', dataIndex: 'pageCount', width: 80, align: 'right', render: (v?: number | null) => v ?? '-' },
    { title: '切片数', dataIndex: 'chunkCount', width: 90, align: 'right', render: (v?: number | null) => v ?? '-' },
    {
      title: '大小',
      dataIndex: 'sizeBytes',
      width: 100,
      align: 'right',
      render: (v?: number | null) => fmtFileSize(v),
    },
    {
      title: '解析时间',
      dataIndex: 'indexedAt',
      width: 150,
      render: (v?: string | null) => fmtDateTime(v),
    },
    {
      title: '状态',
      dataIndex: 'indexStatus',
      width: 120,
      render: (v: string | null | undefined, row) => {
        const meta = aiIndexStatusMeta(v);
        const tag = <Tag color={meta.color}>{meta.text}</Tag>;
        return row.error ? <Tooltip title={row.error}>{tag}</Tooltip> : tag;
      },
    },
    {
      title: '操作',
      key: 'op',
      width: 160,
      fixed: 'right',
      render: (_, row) => (
        <Space size={4}>
          <Button size="small" type="link" onClick={() => doPreview(row)}>
            查看
          </Button>
          {canManage ? (
            <Popconfirm
              title="删除该文档在知识库中的切片与向量？"
              okText="删除"
              cancelText="取消"
              onConfirm={() => doDelete(row)}
            >
              <Button size="small" type="link" danger icon={<DeleteOutlined />}>
                删除
              </Button>
            </Popconfirm>
          ) : null}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Card size="small" style={{ marginBottom: 12 }}>
        <Space wrap>
          <Input
            placeholder="文件名关键字"
            allowClear
            style={{ width: 220 }}
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onPressEnter={() => {
              setAppliedKeyword(keyword);
              setPage(1);
            }}
          />
          <Select
            allowClear
            placeholder="来源项目"
            style={{ width: 220 }}
            value={projectId ?? undefined}
            onChange={(v) => {
              setProjectId(v ?? null);
              setPage(1);
            }}
            showSearch
            optionFilterProp="label"
            options={projects.map((p) => ({ value: p.id, label: p.name }))}
          />
          <Button
            type="primary"
            icon={<SearchOutlined />}
            onClick={() => {
              setAppliedKeyword(keyword);
              setPage(1);
            }}
          >
            搜 索
          </Button>
          <Button
            // 用 ReloadOutlined 而不是 ClearOutlined：与其它页面"重置"按钮保持同一图标语义
            icon={<ReloadOutlined />}
            onClick={() => {
              setKeyword('');
              setAppliedKeyword('');
              setProjectId(null);
              setPage(1);
            }}
          >
            重置
          </Button>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
        </Space>
      </Card>

      {error ? (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 12 }}
          message="文档库读取失败"
          description={
            <>
              <div>{error}</div>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                若提示与 AI 服务相关，请到「服务自检」页确认服务状态；此处<b>不代表知识库里没有文档</b>。
              </Typography.Text>
            </>
          }
        />
      ) : null}

      <Card size="small" styles={{ body: { padding: 0 } }}>
        <Table<AiDocument>
          rowKey="docId"
          loading={loading}
          columns={columns}
          dataSource={rows}
          scroll={{ x: 1200 }}
          locale={{
            emptyText: loading ? (
              <span />
            ) : (
              <Empty description="知识库暂无文档（附件上传后自动解析入库，失败时可到任务队列重试）" />
            ),
          }}
          pagination={{
            current: page,
            pageSize: size,
            total,
            showSizeChanger: true,
            showTotal: (t) => `共 ${t} 条`,
            onChange: (p, s) => {
              setPage(p);
              setSize(s);
            },
          }}
        />
      </Card>

      {/* 附件原文预览（"查看"）：就地打开，不离开文档库 */}
      {preview.el}
    </div>
  );
}

/* ==================== ② 任务队列 ==================== */

function TasksTab({ canManage }: { canManage: boolean }) {
  const { projects } = useAiProjects();
  const [rows, setRows] = useState<AiTask[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [status, setStatus] = useState<string | undefined>(undefined);
  const [projectId, setProjectId] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await aiApi.tasks({
        page,
        size,
        status: status || undefined,
        projectId: projectId ?? undefined,
      });
      setRows(res.records || []);
      setTotal(res.total || 0);
      setError(null);
    } catch (e) {
      setRows([]);
      setTotal(0);
      setError((e as Error).message || '任务队列读取失败');
    } finally {
      setLoading(false);
    }
  }, [page, size, status, projectId]);

  useEffect(() => {
    void load();
  }, [load]);

  const doRetry = async (row: AiTask) => {
    try {
      await aiApi.retryTask(row.taskId);
      message.success('已提交重试');
      void load();
    } catch {
      /* 拦截器已提示失败原因 */
    }
  };

  const columns: ColumnsType<AiTask> = [
    { title: '文件名', dataIndex: 'filename', ellipsis: true, render: (v?: string | null) => v || '-' },
    {
      title: '来源项目',
      dataIndex: 'projectName',
      width: 180,
      ellipsis: true,
      render: (v: string | null | undefined, row: AiTask) =>
        v || (row.projectId ? `项目 #${row.projectId}` : '-'),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (v: string) => {
        const meta = aiTaskStatusMeta(v);
        return <Tag color={meta.color}>{meta.text}</Tag>;
      },
    },
    {
      title: '进度',
      dataIndex: 'progress',
      width: 150,
      render: (v: number | null | undefined, row) =>
        row.status === 'RUNNING' ? <Progress percent={v ?? 0} size="small" /> : v ? `${v}%` : '-',
    },
    {
      title: '失败原因',
      dataIndex: 'error',
      ellipsis: true,
      render: (v?: string | null) =>
        v ? (
          <Tooltip title={v}>
            <Typography.Text type="danger" style={{ fontSize: 12 }}>
              {v}
            </Typography.Text>
          </Tooltip>
        ) : (
          '-'
        ),
    },
    { title: '创建时间', dataIndex: 'createdAt', width: 150, render: (v?: string | null) => fmtDateTime(v) },
    { title: '更新时间', dataIndex: 'updatedAt', width: 150, render: (v?: string | null) => fmtDateTime(v) },
    {
      title: '操作',
      key: 'op',
      width: 100,
      fixed: 'right',
      render: (_, row) =>
        canManage && row.status === 'FAILED' ? (
          <Button size="small" type="link" onClick={() => void doRetry(row)}>
            重试
          </Button>
        ) : (
          <span style={{ color: '#bfbfbf' }}>—</span>
        ),
    },
  ];

  return (
    <div>
      <Card size="small" style={{ marginBottom: 12 }}>
        <Space wrap>
          <Select
            allowClear
            placeholder="状态"
            style={{ width: 140 }}
            value={status}
            onChange={(v) => {
              setStatus(v);
              setPage(1);
            }}
            options={AI_TASK_STATUS_OPTIONS}
          />
          <Select
            allowClear
            placeholder="来源项目"
            style={{ width: 220 }}
            value={projectId ?? undefined}
            onChange={(v) => {
              setProjectId(v ?? null);
              setPage(1);
            }}
            showSearch
            optionFilterProp="label"
            options={projects.map((p) => ({ value: p.id, label: p.name }))}
          />
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
        </Space>
      </Card>

      {error ? (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 12 }}
          message="任务队列读取失败"
          description={error}
        />
      ) : null}

      <Card size="small" styles={{ body: { padding: 0 } }}>
        <Table<AiTask>
          rowKey="taskId"
          loading={loading}
          columns={columns}
          dataSource={rows}
          scroll={{ x: 1200 }}
          locale={{ emptyText: loading ? <span /> : <Empty description="暂无解析任务" /> }}
          pagination={{
            current: page,
            pageSize: size,
            total,
            showSizeChanger: true,
            showTotal: (t) => `共 ${t} 条`,
            onChange: (p, s) => {
              setPage(p);
              setSize(s);
            },
          }}
        />
      </Card>
    </div>
  );
}

/* ==================== ③ 服务自检 ==================== */

const MODEL_LABELS: { key: 'chat' | 'ocr' | 'embedding' | 'reranker'; label: string }[] = [
  { key: 'chat', label: '对话模型' },
  { key: 'ocr', label: 'OCR 识别' },
  { key: 'embedding', label: '向量化模型' },
  { key: 'reranker', label: '重排模型' },
];

/** 快速探活不探的三个模型（深度自检才探）：用来判断要不要提示"点深度自检" */
const MODEL_KEYS_DEEP_ONLY: ('chat' | 'embedding' | 'reranker')[] = ['chat', 'embedding', 'reranker'];

function HealthTab() {
  const [health, setHealth] = useState<AiHealth | null>(null);
  const [loading, setLoading] = useState(false);
  /**
   * 深度自检单独一个 loading：它会真的去 ping 平台模型（几秒到十几秒）。
   * 不复用卡片 loading——那会把已经显示出来的结论整块换成骨架屏，
   * 也没法在按钮上表达"正在探测"。
   */
  const [deepLoading, setDeepLoading] = useState(false);
  /** 当前结果是否来自深度自检（决定"未探测"提示与检测方式文案） */
  const [deepProbed, setDeepProbed] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (deep = false) => {
    if (deep) {
      setDeepLoading(true);
    } else {
      setLoading(true);
    }
    try {
      // deep=true 时 api 层会放宽超时（见 AI_DEEP_HEALTH_TIMEOUT_MS）：
      // 探测本身要几秒到十几秒，不能被默认短超时掐断而误报成"服务不可用"
      setHealth(await aiApi.health(deep));
      setDeepProbed(deep);
      setError(null);
    } catch (e) {
      setHealth(null);
      setDeepProbed(false);
      setError((e as Error).message || '服务自检接口调用失败');
    } finally {
      setLoading(false);
      setDeepLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  /**
   * 三态：可用 / 不可用 / **未探测**。
   *
   * 后端在没探测那个模型时给 null——以前这里渲染成"未知"，看起来像"探了但看不出结论"，
   * 用户会以为系统坏了。实际语义是"这次没探"，必须和"不可用"区分开。
   */
  const okTag = (ok?: boolean | null) =>
    ok === true ? (
      <Tag color="success" icon={<CheckCircleOutlined />}>
        可用
      </Tag>
    ) : ok === false ? (
      <Tag color="error" icon={<CloseCircleOutlined />}>
        不可用
      </Tag>
    ) : (
      <Tag>未探测</Tag>
    );

  /** 有没有该探却没探的模型（快速探活下三个恒为 null，需要引导用户点深度自检） */
  const hasUnprobed =
    !!health && MODEL_KEYS_DEEP_ONLY.some((k) => health.models?.[k] === null || health.models?.[k] === undefined);

  return (
    <Card
      size="small"
      title="AI 服务自检"
      loading={loading}
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            重新检测
          </Button>
          <Button
            type="primary"
            icon={<ExperimentOutlined />}
            loading={deepLoading}
            onClick={() => void load(true)}
          >
            深度自检
          </Button>
        </Space>
      }
    >
      {error ? (
        <Alert
          type="error"
          showIcon
          message="无法获取服务状态"
          description={
            <>
              <div>{error}</div>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                此时附件解析与问答都不可用；附件中心会显示"AI 服务不可用"，而不是把失败当成"未找到"。
              </Typography.Text>
            </>
          }
        />
      ) : health ? (
        <>
          {health.available ? (
            <Alert
              type="success"
              showIcon
              style={{ marginBottom: 12 }}
              message="AI 服务可用"
              description={health.message || '知识库解析与问答均正常。'}
            />
          ) : (
            <Alert
              type="error"
              showIcon
              style={{ marginBottom: 12 }}
              message="AI 服务不可用"
              description={
                <>
                  <div>{health.message || '未提供原因'}</div>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    不可用期间：附件不会入库、问答会直接报错（不会返回"未找到"）。请检查 AI 服务与平台网关。
                  </Typography.Text>
                </>
              }
            />
          )}

          <Row gutter={[12, 12]}>
            <Col xs={24} lg={12}>
              <Descriptions size="small" column={1} bordered>
                <Descriptions.Item label="AI 服务"> {okTag(health.available)}</Descriptions.Item>
                <Descriptions.Item label="平台网关"> {okTag(health.platformReachable)}</Descriptions.Item>
                <Descriptions.Item label="向量后端">
                  <Tag color="geekblue">{aiVectorBackendText(health.vectorBackend)}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label="服务地址">
                  <Typography.Text style={{ fontSize: 12 }}>{health.aiServiceBaseUrl || '-'}</Typography.Text>
                </Descriptions.Item>
                <Descriptions.Item label="检测时间">
                  {fmtDateTime(health.checkedAt)}（{deepProbed ? '深度自检' : '快速探活'}）
                </Descriptions.Item>
              </Descriptions>
            </Col>
            <Col xs={24} lg={12}>
              <Descriptions size="small" column={1} bordered>
                <Descriptions.Item label="文档数">{health.documentCount ?? '-'}</Descriptions.Item>
                <Descriptions.Item label="待处理任务数">{health.pendingTaskCount ?? '-'}</Descriptions.Item>
                {MODEL_LABELS.map((m) => (
                  <Descriptions.Item key={m.key} label={m.label}>
                    {okTag(health.models?.[m.key])}
                  </Descriptions.Item>
                ))}
              </Descriptions>
            </Col>
          </Row>

          {hasUnprobed ? (
            <Alert
              type="info"
              showIcon
              style={{ marginTop: 12 }}
              message="对话 / 向量化 / 重排模型：未探测"
              description={
                <Typography.Text style={{ fontSize: 12 }}>
                  默认只做 OCR 快速探活（打开页面就能出结论，不用等）。
                  点右上角「深度自检」可实际探测对话 / 向量化 / 重排模型——它会真的去 ping 平台模型，
                  需要几秒到十几秒。
                </Typography.Text>
              }
            />
          ) : null}

          <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 12, marginBottom: 0 }}>
            判定口径：四个模型任一不可用都会让解析或问答能力不完整——OCR 不可用影响扫描件入库，
            向量/重排模型不可用会让检索降级（问答会显示"降级"提示），对话模型不可用则问答直接失败。
            「未探测」= 本次没探过（不是"探了不知道"，更不是不可用）。
          </Typography.Paragraph>
        </>
      ) : (
        <Empty description="暂无检测结果" />
      )}
    </Card>
  );
}
