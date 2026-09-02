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
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { projectApi } from '@/api/project';
import { useAuth } from '@/store/auth';
import { useDict } from '@/hooks/useOptions';
import { fmtDateTime, fmtMoney } from '@/utils/format';
import ProjectFormModal from '@/components/ProjectFormModal';
import { PROJECT_STATUS, PROJECT_TYPES, ProjectForm, ProjectListItem } from '@/types';

type ViewMode = 'table' | 'card';

interface Filters {
  keyword?: string;
  type?: string;
  status?: string;
  ownerUnit?: string;
}

const YEARS = Array.from({ length: 8 }, (_, i) => 2026 - i);

export default function ProjectsPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const { options: units } = useDict('OWNER_UNIT');

  const [mode, setMode] = useState<ViewMode>('table');
  const [filters, setFilters] = useState<Filters>({});
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [year, setYear] = useState<number | undefined>();
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
      const res = await projectApi.page({ page, size, year, ...filters });
      setData(res.records);
      setTotal(res.total);
    } catch {
      /* 拦截器已提示 */
    } finally {
      setLoading(false);
    }
  }, [page, size, year, filters]);

  useEffect(() => {
    load();
  }, [load]);

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

  const columns: ColumnsType<ProjectListItem> = [
    {
      title: '项目编号',
      dataIndex: 'code',
      width: 130,
      render: (v: string) => <span style={{ fontFamily: 'Consolas,monospace' }}>{v}</span>,
    },
    {
      title: '项目名称',
      dataIndex: 'name',
      ellipsis: true,
      render: (v: string, row) => (
        <a onClick={() => navigate(`/projects/${row.id}`)}>{v}</a>
      ),
    },
    {
      title: '类型',
      dataIndex: 'type',
      width: 90,
      render: (v: string) => <Tag color={v === 'HW' ? 'geekblue' : 'purple'}>{PROJECT_TYPES[v] || v}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (v: string) => {
        const meta = PROJECT_STATUS[v];
        return meta ? <Tag color={meta.color}>{meta.text}</Tag> : v;
      },
    },
    { title: '甲方单位', dataIndex: 'ownerUnit', width: 130, ellipsis: true },
    { title: '当前阶段', dataIndex: 'currentPhaseName', width: 140, ellipsis: true },
    {
      title: '整体进度',
      dataIndex: 'overallProgress',
      width: 130,
      render: (v?: number) => <Progress percent={v || 0} size="small" />,
    },
    {
      title: '合同金额(元)',
      dataIndex: 'contractAmount',
      align: 'right',
      width: 130,
      render: fmtMoney,
    },
    {
      title: '累计实付(元)',
      dataIndex: 'paidAmount',
      align: 'right',
      width: 130,
      render: fmtMoney,
    },
    { title: '更新时间', dataIndex: 'updateTime', width: 130, render: fmtDateTime },
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
      <Space wrap>
        <Input.Search
          placeholder="项目名称 / 编号 / 供应商"
          allowClear
          style={{ width: 240 }}
          onSearch={(v) => {
            setFilters((f) => ({ ...f, keyword: v || undefined }));
            setPage(1);
          }}
        />
        <Select
          placeholder="类型"
          allowClear
          style={{ width: 120 }}
          value={filters.type}
          onChange={(v) => {
            setFilters((f) => ({ ...f, type: v }));
            setPage(1);
          }}
          options={Object.entries(PROJECT_TYPES).map(([value, label]) => ({ value, label }))}
        />
        <Select
          placeholder="状态"
          allowClear
          style={{ width: 120 }}
          value={filters.status}
          onChange={(v) => {
            setFilters((f) => ({ ...f, status: v }));
            setPage(1);
          }}
          options={Object.entries(PROJECT_STATUS).map(([value, m]) => ({ value, label: m.text }))}
        />
        <Select
          placeholder="甲方单位"
          allowClear
          showSearch
          style={{ width: 160 }}
          value={filters.ownerUnit}
          onChange={(v) => {
            setFilters((f) => ({ ...f, ownerUnit: v }));
            setPage(1);
          }}
          options={units.map((d) => ({ value: d.name, label: d.name }))}
        />
        <Select
          placeholder="立项年度"
          allowClear
          style={{ width: 120 }}
          value={year}
          onChange={(v) => {
            setYear(v);
            setPage(1);
          }}
          options={YEARS.map((y) => ({ value: y, label: `${y} 年` }))}
        />
        <Button
          icon={<ReloadOutlined />}
          onClick={() => {
            setFilters({});
            setYear(undefined);
            setPage(1);
          }}
        >
          重置
        </Button>
      </Space>
      <div style={{ float: 'right', display: 'inline-flex', gap: 12 }}>
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
      </div>
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
            scroll={{ x: 1300 }}
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
          {data.map((p) => (
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
                extra={
                  PROJECT_STATUS[p.status] ? (
                    <Tag color={PROJECT_STATUS[p.status].color}>{PROJECT_STATUS[p.status].text}</Tag>
                  ) : null
                }
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
                  {PROJECT_TYPES[p.type] || p.type}
                </div>
                <div style={{ marginBottom: 4 }}>
                  甲方单位：{p.ownerUnit || '-'}　负责人：{p.managerName || '-'}
                </div>
                <div style={{ marginBottom: 4 }}>当前阶段：{p.currentPhaseName || '-'}</div>
                <Progress percent={p.overallProgress || 0} size="small" />
                <div style={{ fontSize: 12, color: '#595959', marginTop: 4 }}>
                  合同 {fmtMoney(p.contractAmount)} 元　已付 {fmtMoney(p.paidAmount)} 元
                </div>
              </Card>
            </Col>
          ))}
          {!loading && data.length === 0 && (
            <Col span={24}>
              <Empty description="暂无项目，调整筛选条件或新建项目" />
            </Col>
          )}
        </Row>
      )}

      <ProjectFormModal
        open={modalOpen}
        initial={editing}
        submitting={submitting}
        onOk={onFormOk}
        onCancel={() => setModalOpen(false)}
      />
    </div>
  );
}
