import { useCallback, useEffect, useState } from 'react';
import {
  Button,
  Card,
  Collapse,
  Empty,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Tabs,
  Tag,
  Tooltip,
  message,
} from 'antd';
import {
  CopyOutlined,
  DeleteOutlined,
  DownOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  UpOutlined,
} from '@ant-design/icons';
import { systemApi } from '@/api/system';
import { useFormModal } from '@/components/useFormModal';
import { useDict } from '@/hooks/useOptions';
import { PhaseTemplateRow, PhaseTplRow } from '@/types/system';

/**
 * 流程模板：Tab 式多模板 + 折叠阶段列表。
 * 每个阶段（Collapse 面板）默认折叠；展开后展示 说明/阶段做什么/关键材料/常用附件，并提供编辑操作。
 * 阶段描述字段：description=一句话说明；guide=阶段要做什么（详细）；key_materials=关键材料/交付物（每行一项）。
 */
export default function FlowTemplateDesigner() {
  const { open, el } = useFormModal();
  const { options: payNodes } = useDict('PAY_NODE');
  const { options: attachTypes } = useDict('ATTACH_TYPE');

  const [tpls, setTpls] = useState<PhaseTplRow[]>([]);
  const [activeId, setActiveId] = useState<number | null>(null);
  const [phases, setPhases] = useState<PhaseTemplateRow[]>([]);
  const [loading, setLoading] = useState(false);

  const active = tpls.find((t) => t.id === activeId) || null;
  const sortedPhases = [...phases].sort((a, b) => (a.sortNo ?? 0) - (b.sortNo ?? 0));

  const loadTpls = useCallback(async () => {
    const list = await systemApi.listTpls();
    setTpls(list);
    if (list.length) {
      setActiveId((cur) => (cur && list.some((t) => t.id === cur) ? cur : list[0].id!));
    } else {
      setActiveId(null);
      setPhases([]);
    }
  }, []);

  const loadPhases = useCallback(async (tplId: number) => {
    setLoading(true);
    try {
      const rows = await systemApi.tplPhases(tplId);
      setPhases(rows);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadTpls();
  }, [loadTpls]);

  useEffect(() => {
    if (activeId != null) {
      loadPhases(activeId);
    } else {
      setPhases([]);
    }
  }, [activeId, loadPhases]);

  const save = async () => {
    if (activeId == null) return;
    try {
      const items: any[] = sortedPhases.map((p, i) => ({
        id: p.id,
        phaseName: p.phaseName,
        weight: p.weight ?? 5,
        payNode: p.payNode || null,
        attachTypeHints: p.attachTypeHints || undefined,
        description: p.description || null,
        guide: (p as any).guide ?? null,
        keyMaterials: (p as any).keyMaterials ?? null,
        skipable: p.skipable ?? 0,
        sortNo: i + 1,
      }));
      await systemApi.saveTplPhases(activeId, items);
      message.success('已保存（共 ' + items.length + ' 个阶段）');
      await loadPhases(activeId);
    } catch {
      /* 统一错误提示 */
    }
  };

  const openCreate = () => {
    open(
      '新增流程模板',
      [
        { name: 'name', label: '模板名称', el: <Input placeholder="如：硬件项目-快速验收版" />, rules: [{ required: true }] },
        {
          name: 'projectType',
          label: '项目类型',
          el: (
            <Select
              options={[
                { value: 'HW', label: '硬件项目' },
                { value: 'SW', label: '软件项目' },
              ]}
            />
          ),
          rules: [{ required: true }],
        },
        {
          name: 'copyTplId',
          label: '从模板复制阶段',
          el: (
            <Select
              allowClear
              placeholder="可选：复制某套模板的阶段作为起点"
              options={tpls.map((t) => ({
                value: t.id!,
                label: (t.projectType === 'HW' ? '硬件' : '软件') + ' / ' + t.name,
              }))}
            />
          ),
        },
        { name: 'remark', label: '备注', el: <Input /> },
      ],
      { name: '', projectType: 'HW' },
      async (values) => {
        await systemApi.createTpl({
          name: String(values.name),
          projectType: String(values.projectType),
          copyTplId: values.copyTplId ? Number(values.copyTplId) : undefined,
          remark: values.remark ? String(values.remark) : undefined,
        });
        message.success('模板已创建');
        await loadTpls();
      },
    );
  };

  const setDefault = async () => {
    if (!activeId) return;
    await systemApi.updateTpl(activeId, { isDefault: 1 });
    message.success('已设为该类项目的默认模板');
    await loadTpls();
  };

  const rename = () => {
    if (!active) return;
    open(
      '重命名模板',
      [{ name: 'name', label: '模板名称', el: <Input />, rules: [{ required: true }] }],
      { name: active.name },
      async (values) => {
        await systemApi.updateTpl(activeId!, { name: String(values.name) });
        message.success('已重命名');
        await loadTpls();
      },
    );
  };

  const editPhase = (row: PhaseTemplateRow | null) => {
    open(
      row ? `编辑阶段：${row.phaseName}` : '新增阶段',
      [
        { name: 'phaseName', label: '阶段名称', el: <Input placeholder="如：到货验收" />, rules: [{ required: true }] },
        {
          name: 'weight',
          label: '权重',
          el: <InputNumber min={0} max={100} style={{ width: '100%' }} />,
          rules: [{ required: true }],
        },
        {
          name: 'payNode',
          label: '付款节点',
          el: (
            <Select
              allowClear
              placeholder="无里程碑付款则留空"
              options={payNodes.map((p) => ({ value: p.code, label: p.name }))}
            />
          ),
        },
        {
          name: 'attachTypeHints',
          label: '常用附件类别',
          el: (
            <Select
              mode="multiple"
              placeholder="可多选"
              options={attachTypes.map((a) => ({ value: a.code, label: a.name }))}
            />
          ),
        },
        { name: 'description', label: '一句话说明', el: <Input placeholder="如：设备到场清点并签收" /> },
        {
          name: 'guide',
          label: '阶段说明',
          el: (
            <div>
              <Input.TextArea rows={6} placeholder={'【目的】…\n【主要工作】1.… 2.…\n【完成标准】…\n【注意事项】…'} />
              <div style={{ color: '#8c8c8c', fontSize: 12, marginTop: 4 }}>
                建议覆盖：目的 / 主要工作 / 做法与要求 / 完成标准 / 注意事项
              </div>
            </div>
          ),
        },
        {
          name: 'keyMaterials',
          label: '关键材料',
          el: (
            <div>
              <Input.TextArea rows={3} placeholder={'到货签收单\n装箱单\n设备配置清单'} />
              <div style={{ color: '#8c8c8c', fontSize: 12, marginTop: 4 }}>每行一项：交付物 / 材料 / 单据</div>
            </div>
          ),
        },
        {
          name: 'skipable',
          label: '可跳过',
          el: (
            <Select
              options={[
                { value: 0, label: '否' },
                { value: 1, label: '是' },
              ]}
            />
          ),
        },
      ],
      {
        phaseName: row?.phaseName || '',
        weight: row?.weight ?? 5,
        payNode: row?.payNode || undefined,
        attachTypeHints: row?.attachTypeHints ? row.attachTypeHints.split(',') : [],
        description: row?.description || undefined,
        guide: (row as any)?.guide || '',
        keyMaterials: (row as any)?.keyMaterials || '',
        skipable: row?.skipable ?? 0,
      },
      async (values) => {
        const base: any = {
          phaseName: String(values.phaseName),
          weight: Number(values.weight ?? 5),
          payNode: values.payNode ? String(values.payNode) : null,
          attachTypeHints: Array.isArray(values.attachTypeHints)
            ? (values.attachTypeHints as string[]).join(',')
            : undefined,
          description: values.description ? String(values.description) : null,
          guide: values.guide ? String(values.guide) : null,
          keyMaterials: values.keyMaterials ? String(values.keyMaterials) : null,
          skipable: Number(values.skipable ?? 0),
        };
        if (row?.id) {
          setPhases((prev) => prev.map((p) => (p.id === row.id ? { ...p, ...base } : p)));
        } else {
          const np: PhaseTemplateRow = {
            id: undefined,
            projectType: active?.projectType || '',
            phaseName: String(values.phaseName),
            weight: Number(values.weight ?? 5),
            payNode: values.payNode ? String(values.payNode) : null,
            attachTypeHints: base.attachTypeHints,
            description: base.description,
            skipable: Number(values.skipable ?? 0),
            sortNo: phases.length + 1,
          };
          (np as any).guide = base.guide;
          (np as any).keyMaterials = base.keyMaterials;
          setPhases((prev) => [...prev, np]);
        }
        message.success('已保存到当前编辑区，请点「保存模板」应用到模板');
      },
    );
  };

  const removePhase = (index: number) => setPhases((prev) => prev.filter((_, i) => i !== index));

  const movePhase = (index: number, dir: -1 | 1) => {
    const arr = [...sortedPhases];
    const j = index + dir;
    if (j < 0 || j >= arr.length) return;
    const [item] = arr.splice(index, 1);
    arr.splice(j, 0, item);
    setPhases(arr.map((p, i) => ({ ...p, sortNo: i + 1 })));
  };

  const copyPhase = (row: PhaseTemplateRow) => {
    setPhases((prev) => [
      ...prev,
      { ...row, id: undefined, phaseName: row.phaseName + '（副本）', sortNo: prev.length + 1 },
    ]);
  };

  const phaseText = (label: string, value?: string) =>
    value ? (
      <div style={{ marginBottom: 10 }}>
        <div style={{ fontWeight: 600, color: '#1f2329', marginBottom: 4 }}>{label}</div>
        <div style={{ whiteSpace: 'pre-wrap', lineHeight: 1.8, color: '#4e5969' }}>{value}</div>
      </div>
    ) : null;

  const weightTotal = sortedPhases.reduce((s, p) => s + (p.weight ?? 0), 0);

  const collapseItems = sortedPhases.map((p, i) => {
    const anyP = p as any;
    const content = (
      <div>
        {phaseText('说明', p.description || undefined)}
        {phaseText('阶段说明：目的 / 主要工作 / 做法要求 / 完成标准 / 注意事项', anyP.guide)}
        {phaseText('关键材料 / 交付物', anyP.keyMaterials)}
        {p.attachTypeHints ? (
          <div style={{ marginBottom: 8 }}>
            <span style={{ color: '#8c8c8c' }}>常用附件：</span>
            {p.attachTypeHints.split(',').map((code) => (
              <Tag key={code} style={{ marginRight: 4 }}>
                {attachTypes.find((a) => a.code === code)?.name || code}
              </Tag>
            ))}
          </div>
        ) : null}
        <Space size={0} style={{ marginTop: 6 }}>
          <Tooltip title="上移">
            <Button size="small" type="text" icon={<UpOutlined />} disabled={i === 0} onClick={() => movePhase(i, -1)} />
          </Tooltip>
          <Tooltip title="下移">
            <Button
              size="small"
              type="text"
              icon={<DownOutlined />}
              disabled={i === sortedPhases.length - 1}
              onClick={() => movePhase(i, 1)}
            />
          </Tooltip>
          <Button size="small" type="link" icon={<CopyOutlined />} onClick={() => copyPhase(p)}>
            复制
          </Button>
          <Button size="small" type="link" icon={<EditOutlined />} onClick={() => editPhase(p)}>
            编辑
          </Button>
          <Popconfirm title="删除该阶段？" onConfirm={() => removePhase(i)}>
            <Button size="small" type="link" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      </div>
    );
    return {
      key: String(p.id ?? 'new' + i),
      label: (
        <Space size={8}>
          <span style={{ color: '#8c8c8c', fontSize: 12 }}>STEP {i + 1}</span>
          <b>{p.phaseName}</b>
          <span style={{ color: '#8c8c8c', fontSize: 12 }}>权重 {p.weight ?? 0}</span>
          {p.payNode ? (
            <Tag color="gold" style={{ marginRight: 0 }}>
              {payNodes.find((n) => n.code === p.payNode)?.name || p.payNode}
            </Tag>
          ) : null}
          {p.skipable === 1 ? <Tag>可跳过</Tag> : null}
        </Space>
      ),
      children: content,
    };
  });

  if (!tpls.length) {
    return (
      <Card size="small">
        <Empty description="暂无流程模板">
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新建模板
          </Button>
        </Empty>
        {el}
      </Card>
    );
  }

  return (
    <Card size="small" styles={{ body: { paddingTop: 8 } }}>
      <Tabs
        type="editable-card"
        activeKey={activeId != null ? String(activeId) : ''}
        onChange={(k) => setActiveId(Number(k))}
        onEdit={(key, action) => {
          if (action === 'add') openCreate();
          if (action === 'remove') {
            const t = tpls.find((x) => x.id === Number(key));
            if (!t) return;
            Modal.confirm({
              title: '删除模板',
              content: `确定删除「${t.name}」吗？其阶段配置将一并删除。`,
              okButtonProps: { danger: true },
              onOk: async () => {
                await systemApi.deleteTpl(t.id!);
                await loadTpls();
              },
            });
          }
        }}
        items={tpls.map((t) => ({
          key: String(t.id),
          closable: !(t.builtin === 1),
          label: (
            <Space size={4}>
              {t.name}
              {t.isDefault === 1 ? <Tag color="green">默认</Tag> : null}
              {t.enabled === 0 ? <Tag>停用</Tag> : null}
            </Space>
          ),
          children: null,
        }))}
      />

      {active && (
        <div style={{ marginTop: 12 }}>
          <Space wrap style={{ marginBottom: 10 }}>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => editPhase(null)}>
              新增阶段
            </Button>
            <Button type="primary" icon={<EditOutlined />} onClick={save}>
              保存模板
            </Button>
            <Button onClick={rename}>重命名</Button>
            {active.builtin !== 1 && active.isDefault !== 1 && <Button onClick={setDefault}>设为默认</Button>}
            <Button icon={<ReloadOutlined />} onClick={() => activeId != null && loadPhases(activeId)} />
            <span style={{ color: '#8c8c8c', fontSize: 12 }}>
              共 {sortedPhases.length} 个阶段 · 权重合计 {weightTotal}
            </span>
          </Space>
          {sortedPhases.length === 0 ? (
            <Empty description="暂无阶段，点击「新增阶段」开始配置" />
          ) : (
            <Collapse items={collapseItems} />
          )}
        </div>
      )}
      {el}
    </Card>
  );
}
