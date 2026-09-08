import { useCallback, useEffect, useRef, useState } from 'react';
import { Graph } from '@antv/x6';
import {
  Alert,
  Button,
  Card,
  Empty,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Segmented,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Tooltip,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
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
 * 流程模板设计器：
 * - 顶部 Tabs = 多套模板（内置 HW/SW 默认模板 + 可新增自定义模板）
 * - 内容视图切换「画布 / 列表」；两者同一份阶段数据
 * - 画布视图：节点横向拖拽排序（HTML5 DnD），点节点打开配置（名称/权重/付款节点/附件提示等）
 * - 保存：整模板阶段按当前顺序批量保存
 * - 暂为线性流程；并行/分支待图形化方案（X6/LogicFlow）B/C 期
 */
type ViewMode = 'canvas' | 'list' | 'preview';

export default function FlowTemplateDesigner() {
  const { open, el } = useFormModal();
  const { options: payNodes } = useDict('PAY_NODE');
  const { options: attachTypes } = useDict('ATTACH_TYPE');

  const [tpls, setTpls] = useState<PhaseTplRow[]>([]);
  const [activeId, setActiveId] = useState<number | null>(null);
  const [phases, setPhases] = useState<PhaseTemplateRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [view, setView] = useState<ViewMode>('canvas');
  const graphRef = useRef<Graph | null>(null);
  const canvasHostRef = useRef<HTMLDivElement | null>(null);

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
      let ordered: PhaseTemplateRow[];
      if (view === 'canvas' && graphRef.current) {
        // 画布：以连线拓扑（无连线按 Y 坐标）得到线性顺序
        const g = graphRef.current;
        const nodes = (g.getNodes() as any[]).map((n) => ({ id: n.id, data: n.getData() as PhaseTemplateRow, y: n.position().y }));
        const edges = (g.getEdges() as any[]).map((e) => ({ s: e.getSourceCellId(), t: e.getTargetCellId() }));
        const indeg: Record<string, number> = {};
        nodes.forEach((n) => (indeg[n.id] = 0));
        edges.forEach((e) => indeg[e.t] == null || indeg[e.t]++);
        const order: string[] = [];
        const ready = nodes.filter((n) => !indeg[n.id]).map((n) => n.id);
        const map = Object.fromEntries(nodes.map((n) => [n.id, n]));
        const adj: Record<string, string[]> = {};
        edges.forEach((e) => (adj[e.s] = adj[e.s] || []).push(e.t));
        const q = [...ready];
        while (q.length) {
          const id = q.shift()!;
          order.push(id);
          (adj[id] || []).forEach((t) => {
            indeg[t]--;
            if (!indeg[t]) q.push(t);
          });
        }
        const rest = nodes.filter((n) => !order.includes(n.id)).sort((a, b) => a.y - b.y).map((n) => n.id);
        ordered = [...order, ...rest].map((id, i) => ({ ...map[id].data, sortNo: i + 1 }));
      } else {
        ordered = sortedPhases.map((p, i) => ({ ...p, sortNo: i + 1 }));
      }
      const items: any[] = ordered.map((p) => ({
        id: p.id,
        phaseName: p.phaseName,
        weight: p.weight ?? 5,
        payNode: p.payNode || null,
        attachTypeHints: p.attachTypeHints || undefined,
        description: p.description || null,
        skipable: p.skipable ?? 0,
        guide: (p as any).guide ?? null,
        keyMaterials: (p as any).keyMaterials ?? null,
      }));
      await systemApi.saveTplPhases(activeId, items);
      message.success('已保存（共 ' + items.length + ' 个阶段）');
      await loadPhases(activeId);
    } catch {
      /* 统一错误提示 */
    }
  };

  // ---------- 新增模板 ----------
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

  // ---------- 阶段编辑（画布节点 / 列表行共用） ----------
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
        { name: 'description', label: '说明', el: <Input.TextArea rows={2} /> },
        { name: 'guide', label: '阶段说明：本阶段要做什么', el: <Input.TextArea rows={3} placeholder={'例如：\n1. 发起验收申请\n2. 组织甲方验收'} /> },
        { name: 'keyMaterials', label: '关键材料（每行一项）', el: <Input.TextArea rows={3} placeholder={'例如：\n验收申请单\n验收报告'} /> },
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
        const base = {
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
            attachTypeHints: Array.isArray(values.attachTypeHints)
              ? (values.attachTypeHints as string[]).join(',')
              : undefined,
            description: values.description ? String(values.description) : null,
            skipable: Number(values.skipable ?? 0),
            sortNo: phases.length + 1,
          };
          (np as any).guide = values.guide ? String(values.guide) : null;
          (np as any).keyMaterials = values.keyMaterials ? String(values.keyMaterials) : null;
          setPhases((prev) => [...prev, np]);
        }
        message.success('已保存到当前编辑区，请点「保存」应用到模板');
      },
    );
  };

  const removePhase = (index: number) => {
    setPhases((prev) => prev.filter((_, i) => i !== index));
  };

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

  // ---------- 画布视图：X6 draw.io 式 ----------
  const renderCanvas = () => (
    <div>
      <div
        ref={canvasHostRef}
        style={{ height: 520, border: '1px solid #e5e8ef', borderRadius: 8, background: '#f8f9fc', overflow: 'hidden' }}
      />
      <div style={{ marginTop: 8, fontSize: 12, color: '#8c8c8c' }}>
        操作：拖动节点自由排版；从节点拖出到另一节点即连线（保存时按连线拓扑排序，无连线按自上而下顺序）；单击节点编辑；Ctrl+滚轮缩放/平移。
      </div>
    </div>
  );

  // ---------- 折叠描述列表（列表 / 展示共用） ----------
  const renderCollapse = (editable: boolean) => {
    if (sortedPhases.length === 0) {
      return (
        <Empty description="暂无阶段">
          {editable ? (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => editPhase(null)}>
              新增阶段
            </Button>
          ) : null}
        </Empty>
      );
    }
    return (
      <div>
        {sortedPhases.map((p, i) => {
          const anyP = p as any;
          return (
            <div key={p.id ?? 'p' + i} style={{ border: '1px solid #eef0f3', borderRadius: 8, marginBottom: 8 }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '8px 12px', background: '#fafbfc', borderBottom: '1px solid #eef0f3', borderRadius: '8px 8px 0 0' }}>
                <Space size={8}>
                  <span style={{ color: '#8c8c8c' }}>STEP {i + 1}</span>
                  <b>{p.phaseName}</b>
                  {p.skipable === 1 ? <Tag>可跳过</Tag> : null}
                  {p.payNode ? (
                    <Tag color="gold">{payNodes.find((n) => n.code === p.payNode)?.name || p.payNode}</Tag>
                  ) : null}
                  <span style={{ color: '#8c8c8c', fontSize: 12 }}>权重 {p.weight ?? 0}</span>
                </Space>
                {editable ? (
                  <Space size={0}>
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
                ) : null}
              </div>
              <div style={{ padding: '8px 12px' }}>
                {p.description ? <div style={{ marginBottom: 6 }}>说明：{p.description}</div> : null}
                {anyP.guide ? (
                  <details style={{ marginBottom: 6 }} open={view === 'preview'}>
                    <summary style={{ cursor: 'pointer', color: '#1677ff' }}>阶段说明（要做什么）</summary>
                    <div style={{ whiteSpace: 'pre-wrap', marginTop: 4, color: '#4e5969' }}>{anyP.guide}</div>
                  </details>
                ) : null}
                {anyP.keyMaterials ? (
                  <details style={{ marginBottom: 6 }} open={view === 'preview'}>
                    <summary style={{ cursor: 'pointer', color: '#1677ff' }}>关键材料</summary>
                    <div style={{ whiteSpace: 'pre-wrap', marginTop: 4, color: '#4e5969' }}>{anyP.keyMaterials}</div>
                  </details>
                ) : null}
                {p.attachTypeHints ? (
                  <div style={{ marginTop: 4 }}>
                    <span style={{ color: '#8c8c8c', marginRight: 6 }}>常用附件：</span>
                    {p.attachTypeHints.split(',').map((code) => (
                      <Tag key={code} style={{ marginRight: 4 }}>
                        {attachTypes.find((a) => a.code === code)?.name || code}
                      </Tag>
                    ))}
                  </div>
                ) : null}
                {!anyP.guide && !anyP.keyMaterials && !p.description && !p.attachTypeHints ? (
                  <div style={{ color: '#bfbfbf', fontSize: 12 }}>暂无说明与材料</div>
                ) : null}
              </div>
            </div>
          );
        })}
      </div>
    );
  };

  // ---------- 列表视图 ----------
  const columns: ColumnsType<PhaseTemplateRow> = [
    { title: '顺序', dataIndex: 'sortNo', width: 64, align: 'right' },
    { title: '阶段名称', dataIndex: 'phaseName' },
    { title: '权重', dataIndex: 'weight', width: 70, align: 'right' },
    {
      title: '付款节点',
      dataIndex: 'payNode',
      width: 110,
      render: (v?: string | null) =>
        v ? <Tag color="gold">{payNodes.find((p) => p.code === v)?.name || v}</Tag> : '-',
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
      width: 220,
      render: (_, row, index) => (
        <Space size={0}>
          <Tooltip title="上移">
            <Button size="small" type="text" icon={<UpOutlined />} disabled={index === 0} onClick={() => movePhase(index, -1)} />
          </Tooltip>
          <Tooltip title="下移">
            <Button
              size="small"
              type="text"
              icon={<DownOutlined />}
              disabled={index === sortedPhases.length - 1}
              onClick={() => movePhase(index, 1)}
            />
          </Tooltip>
          <Button size="small" type="link" icon={<CopyOutlined />} onClick={() => copyPhase(row)}>
            复制
          </Button>
          <Button size="small" type="link" icon={<EditOutlined />} onClick={() => editPhase(row)}>
            编辑
          </Button>
          <Popconfirm title="删除该阶段？" onConfirm={() => removePhase(index)}>
            <Button size="small" type="link" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const weightTotal = sortedPhases.reduce((s, p) => s + (p.weight ?? 0), 0);

  // ---------- X6 画布构建 ----------
  useEffect(() => {
    if (view !== 'canvas' || !activeId || !canvasHostRef.current) return;
    if (!graphRef.current) {
      const g = new Graph({
        container: canvasHostRef.current,
        height: 520,
        background: { color: '#f8f9fc' },
        grid: { visible: true, size: 16 },
        panning: true,
        mousewheel: { enabled: true, modifiers: ['ctrl', 'meta'] },
        selecting: { enabled: true, showNodeSelectionBox: true },
        connecting: { allowBlank: false, allowLoop: false, snap: { radius: 24 }, highlight: true },
      } as any);
      g.on('node:click', ({ node }: any) => editPhase(node.getData()));
      graphRef.current = g;
    }
    const g = graphRef.current;
    if (!g) return;
    g.clearCells();
    const list = [...phases].sort((a, b) => (a.sortNo ?? 0) - (b.sortNo ?? 0));
    let flow: any = null;
    try {
      flow = JSON.parse((active as any)?.flowJson || 'null');
    } catch {
      flow = null;
    }
    const fNodes: any[] = flow?.nodes || [];
    list.forEach((p, i) => {
      const saved = fNodes.find((n: any) => String(n.id) === String(i)) || fNodes[i];
      const x = saved?.x ?? 40 + (i % 4) * 200;
      const y = saved?.y ?? 30 + Math.floor(i / 4) * 120;
      g.addNode({
        id: 'n' + i,
        x,
        y,
        width: 180,
        height: 70,
        data: p,
        attrs: {
          body: { rx: 8, fill: '#ffffff', stroke: (p as any).skipable === 1 ? '#faad14' : '#1677ff', strokeWidth: 1.5 },
          label: { text: p.phaseName, fontSize: 12, fill: '#1f2329' },
        },
      });
    });
    const fEdges: any[] = flow?.edges || [];
    const lineAttrs: any = { line: { stroke: '#8a919f', strokeWidth: 2, targetMarker: { name: 'block', size: 7 } } };
    if (fEdges.length) {
      fEdges.forEach((e: any) => {
        try {
          g.addEdge({ source: String(e.source), target: String(e.target), attrs: lineAttrs });
        } catch {
          /* ignore */
        }
      });
    } else {
      for (let i = 1; i < list.length; i++) {
        g.addEdge({ source: 'n' + (i - 1), target: 'n' + i, attrs: lineAttrs });
      }
    }
  }, [view, activeId, phases, (active as any)?.flowJson]);

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
        <div style={{ marginTop: 8 }}>
          <Space wrap style={{ marginBottom: 10 }}>
            <Segmented
              value={view}
              onChange={(v) => setView(v as ViewMode)}
              options={[
                { label: '画布', value: 'canvas' },
                { label: '列表', value: 'list' },
                { label: '展示', value: 'preview' },
              ]}
            />
            <Button type="primary" icon={<PlusOutlined />} onClick={() => editPhase(null)}>
              新增阶段
            </Button>
            <Button type="primary" icon={<EditOutlined />} onClick={save}>
              保存模板
            </Button>
            <Button onClick={rename}>重命名</Button>
            {active.builtin !== 1 && active.isDefault !== 1 && (
              <Button onClick={setDefault}>设为默认</Button>
            )}
            <Button icon={<ReloadOutlined />} onClick={() => activeId != null && loadPhases(activeId)} />
            <span style={{ color: '#8c8c8c', fontSize: 12 }}>
              共 {sortedPhases.length} 个阶段 · 权重合计 {weightTotal}
            </span>
          </Space>
          {view === 'canvas' ? renderCanvas() : null}
          {view === 'list' ? renderCollapse(true) : null}
          {view === 'preview' ? renderCollapse(false) : null}
          {active.builtin !== 1 ? (
            <div style={{ marginTop: 8 }}>
              <Alert type="info" showIcon message="自定义模板：可另设默认后，新项目按该模板生成阶段" style={{ fontSize: 12 }} />
            </div>
          ) : null}
        </div>
      )}
      {el}
    </Card>
  );
}
