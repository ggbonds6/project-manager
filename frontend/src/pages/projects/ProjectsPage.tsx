import { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
  Input,
  Modal,
  Progress,
  Row,
  Segmented,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { projectApi } from '@/api/project';
import { useAuth } from '@/store/auth';
import { useDict } from '@/hooks/useOptions';
import { fmtDateTime, fmtMoney } from '@/utils/format';
import ProjectFormModal from '@/components/ProjectFormModal';
import {
  phaseNameTag,
  projectStatusTag,
  projectTypeTag,
  payNodeTag,
  payStatusTag,
} from '@/config/tagDict';
import { PROJECT_TYPES, ProjectForm, ProjectListItem } from '@/types';

type ViewMode = 'table' | 'card';

/** 待提交的筛选草稿（改动不触发搜索，点“搜 索”统一生效） */
interface Draft {
  keyword: string;
  type?: string;
  status?: string;
  ownerUnit?: string;
  year?: number;
}

const YEARS = Array.from({ length: 8 }, (_, i) => 2026 - i);

export default function ProjectsPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const { options: units } = useDict('OWNER_UNIT');

  const [mode, setMode] = useState<ViewMode>('table');
  const [draft, setDraft] = useState<Draft>({ keyword: '' });
  // 已生效筛选（仅“搜 索 / 重置”会更新）
  const [applied, setApplied] = useState<Draft>({ keyword: '' });
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [data, setData] = useState<ProjectListItem[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<(ProjectForm & { id?: number }) | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const canEdit = user?.role === 'ADMIN' || user?.role === 'MANAGER';
  const canDelete = user?.role === 'ADMIN';

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await projectApi.page({
        page,
        size,
        keyword: applied.keyword || undefined,
        type: applied.type,
        status: applied.status,
        ownerUnit: applied.ownerUnit,
        year: applied.year,
      });
      setData(res.records);
      setTotal(res.total);
    } catch {
      /* 拦截器已提示 */
    } finally {
      setLoading(false);
    }
  }, [page, size, applied]);

  useEffect(() => {
    load();
  }, [load]);

  const applySearch = () => {
    setApplied({ ...draft });
    setPage(1);
  };

  const resetAll = () => {
    const empty: Draft = { keyword: '' };
    setDraft(empty);
    setApplied(empty);
    setPage(1);
  };

  const openCreate = () => {
    setEditing(null);
    setModalOpen(true);
  };

  const openEdit = (row: ProjectListItem) => {
    projectApi
      .detail(row.id)
      .then((d) => {
        setEditing(d);
        setModalOpen(true);
      })
      .catch(() => undefined);
  };

  const doDelete = (row: ProjectListItem) => {
    Modal.confirm({
      title: '删除项目',
      content: `确定删除「${row.name}」吗？项目及其阶段将逻辑删除（附件/付款记录保留可追溯）。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await projectApi.remove(row.id);
        load();
      },
    });
  };

  const onFormOk = async (values: ProjectForm) => {
    setSubmitting(true);
    try {
      if (editing?.id) {
        await projectApi.update(editing.id, values);
      } else {
        await projectApi.create(values);
      }
      setModalOpen(false);
      load();
    } finally {
      setSubmitting(false);
    }
  };

  /* 付款节点及金额：一行最多显示 3 个，其余折叠为 +N */
  const renderPayments = (list?: ProjectListItem['payments']) => {
    if (!list || list.length === 0) return <span style={{ color: '#bfbfbf' }}>—</span>;
    const show = list.slice(0, 3);
    return (
      <Space size={[4, 4]} wrap>
        {show.map((p) => {
          const node = payNodeTag(p.nodeCode);
          const st = payStatusTag(p.status);
          const paid = (p.paidAmount || 0) > 0;
          return (
            <Tooltip
              key={p.nodeCode}
              title={`${node.text}：${paid ? '已付 ' + fmtMoney(p.paidAmount) : '计划 ' + fmtMoney(p.planAmount)} 元（${st.text}）`}
            >
              <Tag color={node.color}>
                {node.text} {paid ? fmtMoney(p.paidAmount) : `计划 ${fmtMoney(p.planAmount)}`}
              </Tag>
            </Tooltip>
          );
        })}
        {list.length > 3 ? <Tag>+{list.length - 3}</Tag> : null}
      </Space>
    );
  };

  const columns: ColumnsType<ProjectListItem> = [
    {
      title: '项目编号',
      dataIndex: 'code',
      width: 125,
      render: (v: string) => <span style={{ fontFamily: 'Consolas,monospace' }}>{v}</span>,
    },
    {
      title: '项目名称',
      dataIndex: 'name',
      ellipsis: true,
      render: (v: string, row) => <a onClick={() => navigate(`/projects/${row.id}`)}>{v}</a>,
    },
    {
      title: '类型',
      dataIndex: 'type',
      width: 95,
      render: (v: string) => {
        const t = projectTypeTag(v);
        return <Tag color={t.color}>{t.text}</Tag>;
      },
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (v: string) => {
        const s = projectStatusTag(v);
        return <Tag color={s.color}>{s.text}</Tag>;
      },
    },
    {
      title: '当前阶段',
      dataIndex: 'currentPhaseName',
      width: 150,
      render: (v?: string | null) => {
        const p = phaseNameTag(v);
        return v ? <Tag color={p.color}>{p.text}</Tag> : '-';
      },
    },
    {
      title: '整体进度',
      dataIndex: 'overallProgress',
      width: 120,
      render: (v?: number) => <Progress percent={v || 0} size="small" />,
    },
    {
      title: '付款节点及金额(元)',
      dataIndex: 'payments',
      width: 250,
      render: (_, row) => renderPayments(row.payments),
    },
    {
      title: '合同金额(元)',
      dataIndex: 'contractAmount',
      align: 'right',
      width: 120,
      render: fmtMoney,
    },
    {
      title: '累计实付(元)',
      dataIndex: 'paidAmount',
      align: 'right',
      width: 120,
      render: fmtMoney,
    },
    { title: '更新时间', dataIndex: 'updateTime', width: 125, render: fmtDateTime },
    {
      title: '操作',
      key: 'op',
      width: 150,
      fixed: 'right',
      render: (_, row) => (
        <Space size={4}>
          <Tooltip title="详情">
            <Button size="small" type="link" icon={<EyeOutlined />} onClick={() => navigate(`/projects/${row.id}`)} />
          </Tooltip>
          {canEdit && (
            <Tooltip title="编辑">
              <Button size="small" type="link" icon={<EditOutlined />} onClick={() => openEdit(row)} />
            </Tooltip>
          )}
          {canDelete && (
            <Tooltip title="删除">
              <Button size="small" type="link" danger icon={<DeleteOutlined />} onClick={() => doDelete(row)} />
            </Tooltip>
          )}
        </Space>
      ),
    },
  ];

  const filterBar = (
    <Card size="small" style={{ marginBottom: 12 }}>
      <Space wrap style={{ marginBottom: 8 }}>
        <span style={{ color: '#8c8c8c', fontSize: 12 }}>筛选条件（下拉改动后点击“搜 索”生效）</span>
      </Space>
      <Space wrap>
        <Input
          placeholder="项目名称 / 编号 / 供应商"
          allowClear
          style={{ width: 220 }}
          value={draft.keyword}
          onChange={(e) => setDraft((d) => ({ ...d, keyword: e.target.value }))}
          onPressEnter={applySearch}
        />
        <Button type="primary" icon={<SearchOutlined />} onClick={applySearch}>
          搜 索
        </Button>
        <Select
          placeholder="类型"
          allowClear
          style={{ width: 120 }}
          value={draft.type}
          onChange={(v) => setDraft((d) => ({ ...d, type: v }))}
          options={Object.entries(PROJECT_TYPES).map(([value, label]) => ({ value, label }))}
        />
        <Select
          placeholder="状态"
          allowClear
          style={{ width: 120 }}
          value={draft.status}
          onChange={(v) => setDraft((d) => ({ ...d, status: v }))}
          options={Object.entries({
            RUN: '进行中',
            DONE: '已完结',
            PAUSE: '暂停',
            STOP: '中止',
          }).map(([value, label]) => ({ value, label }))}
        />
        <Select
          placeholder="甲方单位"
          allowClear
          showSearch
          style={{ width: 160 }}
          value={draft.ownerUnit}
          onChange={(v) => setDraft((d) => ({ ...d, ownerUnit: v }))}
          options={units.map((d) => ({ value: d.name, label: d.name }))}
        />
        <Select
          placeholder="立项年度"
          allowClear
          style={{ width: 120 }}
          value={draft.year}
          onChange={(v) => setDraft((d) => ({ ...d, year: v }))}
          options={YEARS.map((y) => ({ value: y, label: `${y} 年` }))}
        />
        <Button icon={<ReloadOutlined />} onClick={resetAll}>
          重置
        </Button>
        <Segmented
          value={mode}
          onChange={(v) => setMode(v as ViewMode)}
          options={[
            { label: '表格', value: 'table' },
            { label: '卡片', value: 'card' },
          ]}
        />
        {canEdit && (
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新建项目
          </Button>
        )}
      </Space>
    </Card>
  );

  return (
    <div>
      {!canEdit && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="当前为只读（领导）角色，可查看项目列表/详情，暂不支持编辑操作。"
        />
      )}
      {filterBar}

      {mode === 'table' ? (
        <Card size="small" styles={{ body: { padding: 0 } }}>
          <Table<ProjectListItem>
            rowKey="id"
            loading={loading}
            columns={columns}
            dataSource={data}
            scroll={{ x: 1500 }}
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
      ) : (
        <Row gutter={[12, 12]}>
          {data.map((p) => {
            const st = projectStatusTag(p.status);
            const ty = projectTypeTag(p.type);
            const ph = phaseNameTag(p.currentPhaseName);
            return (
              <Col key={p.id} xs={24} sm={12} lg={8} xl={6}>
                <Card
                  size="small"
                  hoverable
                  onClick={() => navigate(`/projects/${p.id}`)}
                  title={
                    <Tooltip title={p.name}>
                      <span style={{ fontWeight: 600 }}>{p.name}</span>
                    </Tooltip>
                  }
                  extra={<Tag color={st.color}>{st.text}</Tag>}
                  actions={
                    canEdit
                      ? [
                          <EditOutlined
                            key="edit"
                            onClick={(e) => {
                              e.stopPropagation();
                              openEdit(p);
                            }}
                          />,
                          ...(canDelete
                            ? [
                                <DeleteOutlined
                                  key="del"
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    doDelete(p);
                                  }}
                                />,
                              ]
                            : []),
                        ]
                      : undefined
                  }
                >
                  <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 6 }}>
                    <span style={{ fontFamily: 'Consolas,monospace' }}>{p.code}</span> ·{' '}
                    <Tag color={ty.color} style={{ marginRight: 0 }}>
                      {ty.text}
                    </Tag>
                  </div>
                  <div style={{ marginBottom: 4 }}>
                    甲方单位：{p.ownerUnit || '-'}　负责人：{p.managerName || '-'}
                  </div>
                  <div style={{ marginBottom: 4 }}>
                    当前阶段：
                    {p.currentPhaseName ? <Tag color={ph.color}>{ph.text}</Tag> : '-'}
                  </div>
                  <Progress percent={p.overallProgress || 0} size="small" />
                  <div style={{ fontSize: 12, color: '#595959', marginTop: 6 }}>{renderPayments(p.payments)}</div>
                  <div style={{ fontSize: 12, color: '#595959', marginTop: 4 }}>
                    合同 {fmtMoney(p.contractAmount)} 元　已付 {fmtMoney(p.paidAmount)} 元
                  </div>
                </Card>
              </Col>
            );
          })}
          {!loading && data.length === 0 && (
            <Col span={24}>
              <Empty description="暂无项目，调整筛选条件或新建项目" />
            </Col>
          )}
        </Row>
      )}

      <ProjectFormModal open={modalOpen} initial={editing} submitting={submitting} onOk={onFormOk} onCancel={() => setModalOpen(false)} />
    </div>
  );
}
