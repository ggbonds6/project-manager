import { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Empty,
  Input,
  InputNumber,
  Popconfirm,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { DeleteOutlined, EditOutlined, KeyOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { systemApi } from '@/api/system';
import { deptApi } from '@/api/project';
import { api } from '@/api/http';
import { useFormModal } from '@/components/useFormModal';
import { useDict } from '@/hooks/useOptions';
import { useAuth } from '@/store/auth';
import { fmtDateTime } from '@/utils/format';
import {
  AdminUser,
  BIZ_TYPES,
  DeptRow,
  DictRow,
  DictTypeInfo,
  LogRow,
  PhaseTemplateRow,
} from '@/types/system';
import { PageResult } from '@/types';

const ROLE_OPTIONS = [
  { value: 'ADMIN', label: '管理员' },
  { value: 'MANAGER', label: '项目经办人' },
  { value: 'VIEWER', label: '领导(只读)' },
];

export default function SystemPage() {
  const { user } = useAuth();
  if (user?.role !== 'ADMIN') {
    return <Alert type="warning" showIcon message="仅管理员可访问系统管理" style={{ marginTop: 12 }} />;
  }
  return (
    <Tabs
      items={[
        { key: 'users', label: '用户管理', children: <UsersTab /> },
        { key: 'depts', label: '部门管理', children: <DeptsTab /> },
        { key: 'dicts', label: '基础字典', children: <DictsTab /> },
        { key: 'templates', label: '流程模板', children: <TemplatesTab /> },
        { key: 'logs', label: '操作日志', children: <LogsTab /> },
      ]}
    />
  );
}

/* ==================== 用户管理 ==================== */

function UsersTab() {
  const { open, el } = useFormModal();
  const [rows, setRows] = useState<AdminUser[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(false);
  const [depts, setDepts] = useState<DeptRow[]>([]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await systemApi.users({ page, size, keyword: keyword || undefined });
      setRows(res.records);
      setTotal(res.total);
    } finally {
      setLoading(false);
    }
  }, [page, size, keyword]);

  useEffect(() => {
    deptApi.list().then(setDepts).catch(() => undefined);
  }, []);
  useEffect(() => {
    load();
  }, [load]);

  const deptOptions = depts.map((d) => ({ value: d.id, label: d.name }));
  const statusOptions = [
    { value: 1, label: '启用' },
    { value: 0, label: '停用' },
  ];

  const editUser = (row: AdminUser) => {
    open(
      `编辑用户：${row.account}`,
      [
        { name: 'name', label: '姓名', el: <Input />, rules: [{ required: true }] },
        { name: 'deptId', label: '部门', el: <Select allowClear options={deptOptions} /> },
        { name: 'role', label: '角色', el: <Select options={ROLE_OPTIONS} />, rules: [{ required: true }] },
        { name: 'status', label: '状态', el: <Select options={statusOptions} />, rules: [{ required: true }] },
      ],
      { name: row.name, deptId: row.deptId ?? undefined, role: row.role, status: row.status },
      async (values) => {
        await systemApi.updateUser(row.id, {
          name: String(values.name),
          deptId: values.deptId ? Number(values.deptId) : null,
          role: String(values.role),
          status: Number(values.status),
        });
        message.success('已保存');
        load();
      },
    );
  };

  const createUser = () => {
    open(
      '新增用户',
      [
        { name: 'account', label: '账号', el: <Input />, rules: [{ required: true, message: '请输入账号' }] },
        { name: 'name', label: '姓名', el: <Input />, rules: [{ required: true, message: '请输入姓名' }] },
        { name: 'deptId', label: '部门', el: <Select allowClear options={deptOptions} /> },
        { name: 'role', label: '角色', el: <Select options={ROLE_OPTIONS} />, rules: [{ required: true }] },
        { name: 'password', label: '初始密码', el: <Input.Password />, rules: [{ required: true, message: '请输入初始密码' }] },
      ],
      { role: 'MANAGER', status: 1 },
      async (values) => {
        await systemApi.createUser({
          account: String(values.account).trim(),
          name: String(values.name).trim(),
          deptId: values.deptId ? Number(values.deptId) : null,
          role: String(values.role),
          password: String(values.password),
        });
        message.success('已创建');
        load();
      },
    );
  };

  const resetPwd = (row: AdminUser) => {
    open(
      `重置密码：${row.account}`,
      [{ name: 'password', label: '新密码', el: <Input.Password />, rules: [{ required: true, message: '请输入新密码' }] }],
      {},
      async (values) => {
        await systemApi.resetPassword(row.id, String(values.password));
        message.success('密码已重置');
      },
    );
  };

  const columns: ColumnsType<AdminUser> = [
    { title: '账号', dataIndex: 'account', width: 140 },
    { title: '姓名', dataIndex: 'name', width: 120 },
    {
      title: '角色',
      dataIndex: 'role',
      width: 130,
      render: (v: string) => (
        <Tag color={v === 'ADMIN' ? 'red' : v === 'MANAGER' ? 'blue' : 'default'}>
          {ROLE_OPTIONS.find((o) => o.value === v)?.label || v}
        </Tag>
      ),
    },
    { title: '部门', dataIndex: 'deptName', render: (v?: string) => v || '-' },
    {
      title: '状态',
      dataIndex: 'status',
      width: 80,
      render: (v: number) => (v === 1 ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>),
    },
    { title: '最后登录', dataIndex: 'lastLoginTime', width: 170, render: fmtDateTime },
    {
      title: '操作',
      key: 'op',
      width: 220,
      render: (_, row) => (
        <Space size={4}>
          <Button size="small" type="link" icon={<EditOutlined />} onClick={() => editUser(row)}>
            编辑
          </Button>
          <Button size="small" type="link" icon={<KeyOutlined />} onClick={() => resetPwd(row)}>
            重置密码
          </Button>
          <Popconfirm
            title={`删除用户 ${row.account}？`}
            onConfirm={async () => {
              await systemApi.deleteUser(row.id);
              load();
            }}
          >
            <Button size="small" type="link" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Card size="small">
      <Space style={{ marginBottom: 12 }}>
        <Input.Search
          placeholder="账号/姓名"
          allowClear
          style={{ width: 220 }}
          onSearch={(v) => {
            setKeyword(v);
            setPage(1);
          }}
        />
        <Button type="primary" icon={<PlusOutlined />} onClick={createUser}>
          新增用户
        </Button>
      </Space>
      <Table<AdminUser>
        rowKey="id"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={{
          current: page,
          pageSize: size,
          total,
          showSizeChanger: true,
          onChange: (p, s) => {
            setPage(p);
            setSize(s);
          },
        }}
      />
      {el}
    </Card>
  );
}

/* ==================== 部门管理 ==================== */

function DeptsTab() {
  const { open, el } = useFormModal();
  const [rows, setRows] = useState<DeptRow[]>([]);
  const [loading, setLoading] = useState(false);
  const load = useCallback(async () => {
    setLoading(true);
    try {
      setRows(await deptApi.list());
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    load();
  }, [load]);

  const parentName = (id: number) => (id === 0 ? '—' : rows.find((r) => r.id === id)?.name || String(id));
  const parentOptions = rows.map((d) => ({ value: d.id, label: `${d.name}${d.parentId === 0 ? '（根）' : ''}` }));

  const editDept = (row: DeptRow | null) => {
    open(
      row ? `编辑部门：${row.name}` : '新增部门',
      [
        { name: 'name', label: '名称', el: <Input />, rules: [{ required: true, message: '请输入部门名称' }] },
        {
          name: 'parentId',
          label: '上级部门',
          el: <Select allowClear showSearch optionFilterProp="label" options={parentOptions.filter((o) => o.value !== row?.id)} />,
        },
        { name: 'orderNo', label: '排序号', el: <InputNumber min={0} style={{ width: '100%' }} /> },
      ],
      { name: row?.name, parentId: row?.parentId || undefined, orderNo: row?.orderNo ?? 0 },
      async (values) => {
        const data = {
          name: String(values.name),
          parentId: values.parentId ? Number(values.parentId) : 0,
          orderNo: values.orderNo === undefined || values.orderNo === null ? 0 : Number(values.orderNo),
        };
        if (row) {
          await systemApi.updateDept(row.id, data);
        } else {
          await systemApi.createDept(data);
        }
        message.success('已保存');
        load();
      },
    );
  };

  const columns: ColumnsType<DeptRow> = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '部门名称', dataIndex: 'name' },
    { title: '上级部门', dataIndex: 'parentId', render: (v: number) => parentName(v) },
    { title: '排序号', dataIndex: 'orderNo', width: 100, align: 'right' },
    {
      title: '操作',
      key: 'op',
      width: 160,
      render: (_, row) => (
        <Space size={4}>
          <Button size="small" type="link" icon={<EditOutlined />} onClick={() => editDept(row)}>
            编辑
          </Button>
          <Popconfirm
            title={`删除部门 ${row.name}？`}
            onConfirm={async () => {
              await systemApi.deleteDept(row.id);
              load();
            }}
          >
            <Button size="small" type="link" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Card size="small">
      <Space style={{ marginBottom: 12 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => editDept(null)}>
          新增部门
        </Button>
        <Button icon={<ReloadOutlined />} onClick={load}>
          刷新
        </Button>
      </Space>
      <Table<DeptRow> rowKey="id" size="small" loading={loading} columns={columns} dataSource={rows} pagination={false} />
      {el}
    </Card>
  );
}

/* ==================== 基础字典 ==================== */

function DictsTab() {
  const { open, el } = useFormModal();
  const [types, setTypes] = useState<DictTypeInfo[]>([]);
  const [type, setType] = useState<string | undefined>();
  const [rows, setRows] = useState<DictRow[]>([]);
  const [loading, setLoading] = useState(false);

  const loadTypes = useCallback(async () => {
    const ts = await systemApi.dictTypes();
    setTypes(ts);
    return ts;
  }, []);

  useEffect(() => {
    loadTypes().then((ts) => {
      if (!type && ts.length) setType(ts[0].type);
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loadItems = useCallback(async () => {
    if (!type) {
      setRows([]);
      return;
    }
    setLoading(true);
    try {
      setRows(await systemApi.dictList(type));
    } finally {
      setLoading(false);
    }
  }, [type]);

  useEffect(() => {
    loadItems();
  }, [loadItems]);

  const editItem = (row: DictRow | null) => {
    open(
      row ? `编辑字典项：${row.name}` : '新增字典项',
      [
        {
          name: 'dictType',
          label: '类型编码',
          el: <Input placeholder="如 OWNER_UNIT" disabled={!!type} />,
          rules: [{ required: true, message: '请输入类型编码' }],
        },
        { name: 'code', label: '编码', el: <Input />, rules: [{ required: true }] },
        { name: 'name', label: '名称', el: <Input />, rules: [{ required: true }] },
        { name: 'sortNo', label: '排序号', el: <InputNumber min={0} style={{ width: '100%' }} /> },
      ],
      { dictType: row?.dictType ?? type, code: row?.code, name: row?.name, sortNo: row?.sortNo ?? 0 },
      async (values) => {
        const data = {
          dictType: String(values.dictType).trim(),
          code: String(values.code).trim(),
          name: String(values.name).trim(),
          sortNo: Number(values.sortNo ?? 0),
        };
        if (row?.id) {
          await systemApi.updateDict(row.id, data);
        } else {
          await systemApi.createDict(data);
        }
        message.success('已保存');
        setType(data.dictType);
        loadItems();
        loadTypes();
      },
    );
  };

  const columns: ColumnsType<DictRow> = [
    { title: '编码', dataIndex: 'code', width: 170 },
    { title: '名称', dataIndex: 'name' },
    { title: '排序', dataIndex: 'sortNo', width: 80, align: 'right' },
    {
      title: '操作',
      key: 'op',
      width: 150,
      render: (_, row) => (
        <Space size={4}>
          <Button size="small" type="link" icon={<EditOutlined />} onClick={() => editItem(row)}>
            编辑
          </Button>
          <Popconfirm
            title="删除该字典项？"
            onConfirm={async () => {
              await systemApi.deleteDict(row.id!);
              loadItems();
            }}
          >
            <Button size="small" type="link" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Card size="small">
      <Space style={{ marginBottom: 12 }}>
        <Select
          style={{ width: 240 }}
          placeholder="选择字典类型"
          value={type}
          onChange={setType}
          options={types.map((t) => ({ value: t.type, label: `${t.type}（${t.count}）` }))}
        />
        <Button type="primary" icon={<PlusOutlined />} onClick={() => editItem(null)}>
          新增字典项
        </Button>
        <Button icon={<ReloadOutlined />} onClick={() => { loadTypes(); loadItems(); }} />
      </Space>
      <Table<DictRow>
        rowKey="id"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={false}
        locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} /> }}
      />
      {el}
    </Card>
  );
}

/* ==================== 流程模板 ==================== */

function TemplatesTab() {
  const { open, el } = useFormModal();
  const { options: payNodes } = useDict('PAY_NODE');
  const { options: attachTypes } = useDict('ATTACH_TYPE');
  const [type, setType] = useState<'HW' | 'SW'>('HW');
  const [rows, setRows] = useState<PhaseTemplateRow[]>([]);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<PhaseTemplateRow[]>('/phase-templates', { type });
      setRows(res);
    } finally {
      setLoading(false);
    }
  }, [type]);

  useEffect(() => {
    load();
  }, [load]);

  const edit = (row: PhaseTemplateRow | null) => {
    open(
      row ? `编辑阶段：${row.phaseName}` : `新增阶段（${type === 'HW' ? '硬件' : '软件'}）`,
      [
        { name: 'phaseName', label: '阶段名称', el: <Input />, rules: [{ required: true }] },
        { name: 'weight', label: '权重', el: <InputNumber min={0} max={100} style={{ width: '100%' }} />, rules: [{ required: true }] },
        {
          name: 'payNode',
          label: '付款节点',
          el: <Select allowClear placeholder="无里程碑付款则留空" options={payNodes.map((p) => ({ value: p.code, label: p.name }))} />,
        },
        { name: 'sortNo', label: '顺序号', el: <InputNumber min={1} style={{ width: '100%' }} /> },
        {
          name: 'attachTypeHints',
          label: '常用附件类别',
          el: (
            <Select
              mode="multiple"
              placeholder="选择提示的附件类别"
              options={attachTypes.map((a) => ({ value: a.code, label: a.name }))}
            />
          ),
        },
        { name: 'description', label: '说明', el: <Input.TextArea rows={2} /> },
        { name: 'skipable', label: '可跳过', el: <Select options={[{ value: 0, label: '否' }, { value: 1, label: '是' }]} /> },
      ],
      {
        phaseName: row?.phaseName,
        weight: row?.weight ?? 5,
        payNode: row?.payNode || undefined,
        sortNo: row?.sortNo,
        attachTypeHints: row?.attachTypeHints ? row.attachTypeHints.split(',') : [],
        description: row?.description,
        skipable: row?.skipable ?? 0,
      },
      async (values) => {
        const data: Partial<PhaseTemplateRow> = {
          projectType: type,
          phaseName: String(values.phaseName),
          weight: Number(values.weight),
          payNode: values.payNode ? String(values.payNode) : null,
          sortNo: values.sortNo === undefined || values.sortNo === null ? undefined : Number(values.sortNo),
          attachTypeHints: Array.isArray(values.attachTypeHints) ? (values.attachTypeHints as string[]).join(',') : undefined,
          description: values.description ? String(values.description) : null,
          skipable: Number(values.skipable ?? 0),
        };
        if (row?.id) {
          await systemApi.updateTemplate(row.id, data);
        } else {
          await systemApi.createTemplate(data);
        }
        message.success('已保存');
        load();
      },
    );
  };

  const columns: ColumnsType<PhaseTemplateRow> = [
    { title: '顺序', dataIndex: 'sortNo', width: 60, align: 'right' },
    { title: '阶段名称', dataIndex: 'phaseName' },
    { title: '权重', dataIndex: 'weight', width: 70, align: 'right' },
    {
      title: '付款节点',
      dataIndex: 'payNode',
      width: 110,
      render: (v?: string | null) => (v ? <Tag color="gold">{payNodes.find((p) => p.code === v)?.name || v}</Tag> : '-'),
    },
    {
      title: '可跳过',
      dataIndex: 'skipable',
      width: 80,
      render: (v?: number) => (v === 1 ? <Tag>是</Tag> : '-'),
    },
    { title: '说明', dataIndex: 'description', ellipsis: true },
    {
      title: '操作',
      key: 'op',
      width: 150,
      render: (_, row) => (
        <Space size={4}>
          <Button size="small" type="link" icon={<EditOutlined />} onClick={() => edit(row)}>
            编辑
          </Button>
          <Popconfirm
            title="删除该阶段模板？新建项目将不再生成此阶段。"
            onConfirm={async () => {
              await systemApi.deleteTemplate(row.id!);
              load();
            }}
          >
            <Button size="small" type="link" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Card size="small">
      <Space style={{ marginBottom: 12 }}>
        <Select
          style={{ width: 130 }}
          value={type}
          onChange={setType}
          options={[
            { value: 'HW', label: '硬件项目' },
            { value: 'SW', label: '软件项目' },
          ]}
        />
        <Button type="primary" icon={<PlusOutlined />} onClick={() => edit(null)}>
          新增阶段
        </Button>
        <Button icon={<ReloadOutlined />} onClick={load} />
        <span style={{ color: '#8c8c8c', fontSize: 12 }}>
          权重用于整体进度计算；顺序决定生成阶段实例的顺序。
        </span>
      </Space>
      <Table<PhaseTemplateRow>
        rowKey="id"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={false}
      />
      {el}
    </Card>
  );
}

/* ==================== 操作日志 ==================== */

function LogsTab() {
  const [rows, setRows] = useState<LogRow[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(15);
  const [userName, setUserName] = useState('');
  const [bizType, setBizType] = useState<string | undefined>();
  const [action, setAction] = useState('');
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res: PageResult<LogRow> = await systemApi.logs({
        page,
        size,
        userName: userName || undefined,
        bizType,
        action: action || undefined,
      });
      setRows(res.records);
      setTotal(res.total);
    } finally {
      setLoading(false);
    }
  }, [page, size, userName, bizType, action]);

  useEffect(() => {
    load();
  }, [load]);

  const columns: ColumnsType<LogRow> = [
    { title: 'ID', dataIndex: 'id', width: 90 },
    { title: '时间', dataIndex: 'createTime', width: 175, render: fmtDateTime },
    { title: '用户', dataIndex: 'userName', width: 120, render: (v?: string | null) => v || '系统' },
    {
      title: '类型',
      dataIndex: 'bizType',
      width: 110,
      render: (v?: string) => (v ? <Tag>{BIZ_TYPES.find((b) => b.value === v)?.label || v}</Tag> : '-'),
    },
    {
      title: '动作',
      dataIndex: 'action',
      width: 140,
      render: (v?: string) => (v ? <Tag color="blue">{v}</Tag> : '-'),
    },
    { title: '详情', dataIndex: 'detail', ellipsis: true },
  ];

  return (
    <Card size="small">
      <Space wrap style={{ marginBottom: 12 }}>
        <Input.Search placeholder="操作人" allowClear style={{ width: 150 }} onSearch={(v) => { setUserName(v); setPage(1); }} />
        <Input.Search placeholder="动作（如 CREATE）" allowClear style={{ width: 180 }} onSearch={(v) => { setAction(v); setPage(1); }} />
        <Select
          style={{ width: 130 }}
          placeholder="业务类型"
          allowClear
          value={bizType}
          onChange={(v) => {
            setBizType(v);
            setPage(1);
          }}
          options={BIZ_TYPES}
        />
        <Button icon={<ReloadOutlined />} onClick={load}>
          查询
        </Button>
      </Space>
      <Table<LogRow>
        rowKey="id"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={rows}
        scroll={{ x: 950 }}
        pagination={{
          current: page,
          pageSize: size,
          total,
          showSizeChanger: true,
          onChange: (p, s) => {
            setPage(p);
            setSize(s);
          },
        }}
      />
    </Card>
  );
}
