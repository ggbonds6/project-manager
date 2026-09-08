import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Button,
  Card,
  Col,
  Collapse,
  DatePicker,
  Descriptions,
  Empty,
  Form,
  Input,
  InputNumber,
  List,
  Modal,
  Pagination,
  Popconfirm,
  Progress,
  Row,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Timeline,
  Tooltip,
  Avatar,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { CollapseProps } from 'antd';
import {
  ArrowLeftOutlined,
  DownloadOutlined,
  EditOutlined,
  EyeOutlined,
  FileTextOutlined,
  PaperClipOutlined,
  PlusOutlined,
  ReloadOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import dayjs, { Dayjs } from 'dayjs';
import { useNavigate, useParams } from 'react-router-dom';
import './flow.css';
import { attachmentApi, attachmentUrl, contractApi, paymentApi, projectApi } from '@/api/project';
import ProjectFormModal from '@/components/ProjectFormModal';
import PhaseEditModal from '@/components/PhaseEditModal';
import AttachmentUploadModal from '@/components/AttachmentUploadModal';
import AttachmentPreviewModal from '@/components/AttachmentPreviewModal';
import ProjectOverviewPanel from '@/components/ProjectOverviewPanel';
import { useFormModal } from '@/components/useFormModal';
import { useAuth } from '@/store/auth';
import { useDict } from '@/hooks/useOptions';
import { fmtDate, fmtDateTime, fmtFileSize, fmtMoney } from '@/utils/format';
import { payNodeTag, payStatusTag, phaseNameTag, projectStatusTag, projectTypeTag } from '@/config/tagDict';
import {
  AttachmentItem,
  ContractItem,
  LogItem,
  PAYMENT_STATUS,
  PaymentItem,
  PHASE_STATUS,
  PhaseItem,
  PROJECT_STATUS,
  PROJECT_TYPES,
  ProjectDetail,
  ProjectForm,
  ProjectListItem,
} from '@/types';

const statusMeta = (s?: string) => (s ? PROJECT_STATUS[s] : undefined);

export default function ProjectDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { user } = useAuth();
  const [detail, setDetail] = useState<ProjectDetail | null>(null);
  const [payments, setPayments] = useState<PaymentItem[]>([]);
  const [contracts, setContracts] = useState<ContractItem[]>([]);
  const [attachments, setAttachments] = useState<AttachmentItem[]>([]);
  const [logs, setLogs] = useState<LogItem[]>([]);
  const [loading, setLoading] = useState(true);

  // 弹窗状态
  const [editOpen, setEditOpen] = useState(false);
  const [phaseModal, setPhaseModal] = useState<{ open: boolean; phase: PhaseItem | null }>({ open: false, phase: null });
  const [upload, setUpload] = useState<{
    open: boolean;
    bizType: string;
    bizId: number;
    label: string;
    fixedAttachType?: string;
  } | null>(null);
  const [payModal, setPayModal] = useState<{
    open: boolean;
    item: PaymentItem | null;
    nodeCode?: string;
    contractId?: number | null;
  }>({ open: false, item: null });
  const [submitting, setSubmitting] = useState(false);

  // 合同登记/编辑弹窗（资金区折叠面板 = 合同（父）→ 付款（子））
  const { open: openContractModal, el: contractModalEl } = useFormModal();
  const openContractForm = (editing: ContractItem | null) => {
    openContractModal(
      editing ? `编辑合同：${editing.name}` : '登记本项目合同（每个项目独立签订）',
      [
        { name: 'name', label: '合同名称', el: <Input placeholder="如：XX 子项目合同" />, rules: [{ required: true }] },
        { name: 'contractNo', label: '合同编号', el: <Input /> },
        { name: 'vendorName', label: '供应商', el: <Input placeholder="供应商相同也各自登记合同" /> },
        { name: 'vendorContact', label: '联系人/电话', el: <Input /> },
        {
          name: 'contractAmount',
          label: '合同金额(元)',
          el: <InputNumber min={0} precision={2} style={{ width: '100%' }} />,
          rules: [{ required: true, message: '请录入合同金额' }],
        },
        { name: 'bidAmount', label: '中标金额(元)', el: <InputNumber min={0} precision={2} style={{ width: '100%' }} /> },
        { name: 'changeAmount', label: '变更金额(元)', el: <InputNumber precision={2} style={{ width: '100%' }} /> },
        { name: 'scopeRemark', label: '范围/备注', el: <Input.TextArea rows={2} /> },
      ],
      {
        name: editing?.name,
        contractNo: editing?.contractNo,
        vendorName: editing?.vendorName,
        vendorContact: editing?.vendorContact,
        contractAmount: editing?.contractAmount ?? undefined,
        bidAmount: editing?.bidAmount ?? undefined,
        changeAmount: editing?.changeAmount ?? 0,
        scopeRemark: editing?.scopeRemark,
      },
      async (values) => {
        const data = {
          name: String(values.name),
          contractNo: values.contractNo ? String(values.contractNo) : undefined,
          vendorName: values.vendorName ? String(values.vendorName) : undefined,
          vendorContact: values.vendorContact ? String(values.vendorContact) : undefined,
          contractAmount: Number(values.contractAmount),
          bidAmount: values.bidAmount === undefined || values.bidAmount === null ? undefined : Number(values.bidAmount),
          changeAmount: Number(values.changeAmount ?? 0),
          scopeRemark: values.scopeRemark ? String(values.scopeRemark) : undefined,
        };
        if (editing?.id) {
          await contractApi.update(editing.id, data);
        } else {
          await contractApi.create({ ...data, projectIds: [detail!.id] });
        }
        message.success('合同已保存');
        reload();
      },
    );
  };

  const { options: fundSources } = useDict('FUND_SOURCE');
  const { options: bidTypes } = useDict('BID_TYPE');
  const { options: sources } = useDict('PROJECT_SOURCE');
  const { options: attachTypes } = useDict('ATTACH_TYPE');
  const { options: payNodes } = useDict('PAY_NODE');

  const canEdit = user?.role === 'ADMIN' || user?.role === 'MANAGER';
  // 付款金额按合同由管理员录入
  const canManagePay = user?.role === 'ADMIN';
  // 总项目容器（有子项目）：无自身阶段流程，仅汇总/公用附件
  const isContainer = (detail?.childCount ?? 0) > 0;

  const reload = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const [d, ps, cs, as, ls] = await Promise.all([
        projectApi.detail(id),
        paymentApi.listByProject(id),
        contractApi.listByProject(id),
        attachmentApi.listByProject(id),
        projectApi.logs(id),
      ]);
      setDetail(d);
      setPayments(ps);
      setContracts(cs);
      setAttachments(as);
      setLogs(ls);
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    reload();
  }, [reload]);

  // 总项目容器：拉取其子项目供“子项目”页签展示
  useEffect(() => {
    if (!detail?.childCount || !id) return;
    projectApi
      .page({ page: 1, size: 200, parentId: Number(id) })
      .then((res) => setChildren(res.records))
      .catch(() => setChildren([]));
  }, [id, detail?.childCount]);

  const dictName = (options: { code: string; name: string }[], code?: string | null) =>
    code ? options.find((o) => o.code === code)?.name || code : '-';

  const attachTypeName = (code?: string | null) => dictName(attachTypes, code);

  // 附件中心分组：按归属（阶段按项目顺序 → 项目级 → 付款凭证），并携带上传目标信息
  const grouped = useMemo(() => {
    interface Group {
      title: string;
      kind: 'PHASE' | 'PROJECT' | 'PARENT' | 'PAYMENT';
      bizType: string;
      bizId: number;
      phaseName?: string;
      items: AttachmentItem[];
    }
    const map = new Map<string, Group>();
    for (const a of attachments) {
      let key: string;
      let kind: Group['kind'];
      let bizType = a.bizType;
      let bizId = a.bizId;
      let title: string;
      let phaseName: string | undefined;
      if (a.bizType === 'PROJECT_PHASE') {
        key = `PHASE:${a.bizId}`;
        kind = 'PHASE';
        title = `阶段：${a.phaseName || '未知阶段'}`;
        phaseName = a.phaseName || undefined;
      } else if (a.bizType === 'PAYMENT') {
        const pay = payments.find((pr) => pr.id === a.bizId);
        key = `PAYMENT:${a.bizId}`;
        kind = 'PAYMENT';
        bizId = a.bizId;
        const node = pay ? payNodeTag(pay.nodeCode) : null;
        title = pay
          ? `${pay.nodeName || node?.text || '款项'}${
              pay.planAmount ? `（计划 ${fmtMoney(pay.planAmount)} 元）` : ''
            }`
          : '付款凭证';
      } else if (a.bizType === 'PROJECT' && a.phaseName === '总项目公用附件') {
        // 父级总项目公用附件（子项目共用查看）
        key = `PARENT:${a.bizId}`;
        kind = 'PARENT';
        bizId = a.bizId;
        title = '总项目公用附件';
      } else {
        key = 'PROJECT';
        kind = 'PROJECT';
        bizId = Number(id);
        title = isContainer ? '总项目公用附件' : '项目附件';
      }
      let g = map.get(key);
      if (!g) {
        g = { title, kind, bizType, bizId, phaseName, items: [] };
        map.set(key, g);
      }
      g.items.push(a);
    }
    const groups = [...map.values()];
    const rank = { PHASE: 0, PROJECT: 1, PARENT: 2, PAYMENT: 3 } as const;
    groups.sort((a, b) => {
      if (a.kind === 'PHASE' && b.kind === 'PHASE') {
        const ai = detail?.phases.findIndex((p) => p.id === a.bizId);
        const bi = detail?.phases.findIndex((p) => p.id === b.bizId);
        return (ai === undefined || ai < 0 ? 999 : ai) - (bi === undefined || bi < 0 ? 999 : bi);
      }
      return rank[a.kind] - rank[b.kind];
    });
    return groups;
  }, [attachments, detail, id, payments, isContainer]);

  // 附件中心筛选（类别 / 归属）
  const [attachTypeFilter, setAttachTypeFilter] = useState<string[]>([]);
  const [groupFilter, setGroupFilter] = useState<string[]>([]);

  // 附件在线预览 / 操作日志分页
  const [previewAtt, setPreviewAtt] = useState<AttachmentItem | null>(null);
  const [logPage, setLogPage] = useState(1);
  const [logSize, setLogSize] = useState(8);
  // 子项目（总项目容器视图）
  const [children, setChildren] = useState<ProjectListItem[]>([]);
  // 新建子项目时预设父项目
  const [presetParent, setPresetParent] = useState<number | null>(null);
  const [editingChild, setEditingChild] = useState(false);

  const onProjectSaved = async (values: ProjectForm) => {
    setSubmitting(true);
    try {
      if (editingChild) {
        const newId = await projectApi.create(values);
        message.success('子项目已创建');
        setEditOpen(false);
        setEditingChild(false);
        setPresetParent(null);
        navigate(`/projects/${newId}`);
        return;
      }
      if (!detail) return;
      await projectApi.update(detail.id, values);
      message.success('项目信息已更新');
      setEditOpen(false);
      reload();
    } finally {
      setSubmitting(false);
    }
  };

  const onPhaseSaved = async (data: Partial<PhaseItem>) => {
    if (!detail) return;
    setSubmitting(true);
    try {
      await projectApi.updatePhase(detail.id, phaseModal.phase!.id, data);
      message.success('阶段已更新');
      setPhaseModal({ open: false, phase: null });
      reload();
    } finally {
      setSubmitting(false);
    }
  };

  const header = useMemo(() => {
    if (!detail) return null;
    return (
      <Card size="small" style={{ marginBottom: 12 }}>
        <Row gutter={16} align="middle">
          <Col flex="auto">
            <Space size={12} wrap>
              <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/projects')}>
                返回
              </Button>
              <span style={{ fontSize: 18, fontWeight: 600 }}>{detail.name}</span>
              {detail.parentId && detail.parentName ? (
                <a style={{ fontSize: 13 }} onClick={() => navigate(`/projects/${detail.parentId}`)}>
                  ← 所属总项目：{detail.parentName}
                </a>
              ) : null}
              <Tag color="geekblue">{PROJECT_TYPES[detail.type] || detail.type}</Tag>
              {statusMeta(detail.status) ? (
                <Tag color={statusMeta(detail.status)!.color}>{statusMeta(detail.status)!.text}</Tag>
              ) : null}
              <span style={{ color: '#8c8c8c' }}>{detail.code}</span>
            </Space>
          </Col>
          <Col>
            <Space size={8}>
              {canEdit && !detail.parentId && (
                <Button
                  icon={<PlusOutlined />}
                  onClick={() => {
                    setEditingChild(true);
                    setPresetParent(detail.id);
                    setEditOpen(true);
                  }}
                >
                  新增子项目
                </Button>
              )}
              {canEdit && (
                <Button type="primary" icon={<EditOutlined />} onClick={() => setEditOpen(true)}>
                  编辑基本信息
                </Button>
              )}
              <Button icon={<ReloadOutlined />} onClick={reload}>
                刷新
              </Button>
            </Space>
          </Col>
        </Row>
        <Row gutter={16} style={{ marginTop: 12 }}>
          <Col xs={24} md={6}>
            {isContainer ? (
              <>定位：<b>总项目（汇总容器）</b></>
            ) : (
              <>
                当前阶段：<b>{detail.currentPhaseName || '-'}</b>
              </>
            )}
          </Col>
          <Col xs={24} md={6}>
            {isContainer ? (
              <>阶段流程：由各子项目独立推进</>
            ) : (
              <>
                整体进度：
                <Progress percent={detail.overallProgress || 0} size="small" style={{ width: 140, display: 'inline-block', marginLeft: 6 }} />
              </>
            )}
          </Col>
          <Col xs={24} md={6}>
            项目经理：{detail.managerName || '-'}
          </Col>
          <Col xs={24} md={6}>
            {isContainer ? (
              <>
                子项目：<b>{detail.childCount} 个</b>（合同按子项目分别签订）
              </>
            ) : (
              <>
                合同总额：<b>{fmtMoney(detail.contractTotal)}</b> 元
              </>
            )}
          </Col>
        </Row>
      </Card>
    );
  }, [detail, canEdit, navigate, reload]);

  if (!detail) {
    return (
      <div>
        {header}
        <Card loading={loading}>
          <Empty description="加载项目详情失败或项目不存在" />
        </Card>
      </div>
    );
  }

  const phaseContent =
    detail.phases.length === 0 ? (
      <Empty description="暂无阶段" />
    ) : (
      <div className="pm-tl">
        {detail.phases.map((ph, idx) => {
          const pAtts = attachments.filter((a) => a.bizType === 'PROJECT_PHASE' && a.bizId === ph.id);
          const today = dayjs().startOf('day');
          const notClosed = ph.status === 'IN_PROGRESS' || ph.status === 'NOT_STARTED';
          const overdue = notClosed && !!ph.planFinishDate && dayjs(ph.planFinishDate).isBefore(today);
          const overdueDays = overdue ? today.diff(dayjs(ph.planFinishDate), 'day') : 0;
          const cls =
            ph.status === 'DONE' ? 'done' : ph.status === 'IN_PROGRESS' ? 'act' : ph.status === 'SKIPPED' ? 'skip' : 'wait';
          const st = PHASE_STATUS[ph.status];
          const payName = ph.payNode ? payNodes.find((n) => n.code === ph.payNode)?.name || ph.payNode : null;
          return (
            <div key={ph.id} className={`pm-tl-item ${cls}${overdue ? ' overdue' : ''}`}>
              <div className="pm-phase-card">
                <div className="pm-phase-head">
                  <div className="pm-tt">
                    <span className="pm-no">STEP {idx + 1}</span>
                    <span className="pm-nm">{ph.phaseName}</span>
                    {st ? <Tag color={st.color}>{st.text}</Tag> : null}
                    {payName ? <Tag color="gold">{payName}</Tag> : null}
                  </div>
                  {canEdit && (
                    <Space size={6} style={{ flexShrink: 0 }}>
                      <Button size="small" icon={<EditOutlined />} onClick={() => setPhaseModal({ open: true, phase: ph })}>
                        记录/推进
                      </Button>
                      <Button
                        size="small"
                        type="primary"
                        ghost
                        icon={<UploadOutlined />}
                        onClick={() =>
                          setUpload({ open: true, bizType: 'PROJECT_PHASE', bizId: ph.id, label: ph.phaseName })
                        }
                      >
                        上传附件
                      </Button>
                    </Space>
                  )}
                </div>
                {overdue && (
                  <div className="pm-od">⚠ 本阶段计划完成日期 {fmtDate(ph.planFinishDate)} 已逾期 {overdueDays} 天</div>
                )}
                <div className="pm-meta">
                  <span className="it">
                    <b>计划</b>
                    {fmtDate(ph.planStartDate)} ~ {fmtDate(ph.planFinishDate)}
                  </span>
                  <span className="it">
                    <b>实际开始</b>
                    {fmtDate(ph.actualStartDate)}
                  </span>
                  <span className="it">
                    <b>实际完成</b>
                    {fmtDate(ph.actualFinishDate)}
                  </span>
                  <span className="it">
                    <b>负责人</b>
                    {ph.managerName || '-'}
                  </span>
                  {ph.percent ? (
                    <span className="it">
                      <b>完成比例</b>
                      {ph.percent}%
                    </span>
                  ) : null}
                </div>
                {ph.payNode
                  ? (() => {
                      // 付款明细与流程付款节点互通：按节点编码在同项目付款记录中查找
                      const pay = payments.find((p) => p.nodeCode === ph.payNode);
                      const node = payNodeTag(ph.payNode);
                      const hasAmount = !!pay && (pay.planAmount != null || pay.paidAmount != null);
                      return (
                        <div
                          style={{
                            display: 'flex',
                            justifyContent: 'space-between',
                            alignItems: 'center',
                            gap: 10,
                            background: '#fffdf3',
                            border: '1px solid #ffe7a3',
                            borderRadius: 8,
                            padding: '6px 12px',
                            margin: '6px 0 8px',
                            flexWrap: 'wrap',
                          }}
                        >
                          <Space size={8} wrap>
                            <span style={{ fontWeight: 600 }}>💰 付款节点</span>
                            <Tag color={node.color}>{node.text}</Tag>
                            {pay ? (
                              hasAmount ? (
                                <>
                                  <span style={{ fontSize: 13 }}>
                                    计划 <b>{fmtMoney(pay.planAmount)}</b> 元 · 已付{' '}
                                    <b style={{ color: '#1677ff' }}>{fmtMoney(pay.paidAmount)}</b> 元
                                  </span>
                                  {(() => {
                                    const st = payStatusTag(pay.status);
                                    return <Tag color={st.color}>{st.text}</Tag>;
                                  })()}
                                </>
                              ) : (
                                <Tag color="orange">金额未填写</Tag>
                              )
                            ) : (
                              <Tag color="gold">未登记付款</Tag>
                            )}
                          </Space>
                          {canManagePay && (
                            <Button
                              size="small"
                              onClick={() =>
                                setPayModal({
                                  open: true,
                                  item: pay || null,
                                  nodeCode: ph.payNode || undefined,
                                  contractId: contracts.length === 1 ? (contracts[0].id ?? null) : null,
                                })
                              }
                            >
                              {pay ? '编辑登记' : '登记付款'}
                            </Button>
                          )}
                        </div>
                      );
                    })()
                  : null}
                {ph.note ? <div className="pm-note">{ph.note}</div> : null}
                {(() => {
                  const px = ph as any;
                  if (!px.guide && !px.keyMaterials) return null;
                  return (
                    <Collapse
                      ghost
                      size="small"
                      style={{ marginTop: 6, maxWidth: 640 }}
                      items={[
                        {
                          key: 'guide' + ph.id,
                          label: <span style={{ color: '#1677ff' }}>阶段说明 · 关键材料</span>,
                          children: (
                            <div style={{ whiteSpace: 'pre-wrap', fontSize: 13, lineHeight: 1.8 }}>
                              {px.guide ? <div><b>要做什么：</b>{px.guide}</div> : null}
                              {px.keyMaterials ? (
                                <div style={{ marginTop: 6 }}>
                                  <b>关键材料：</b>
                                  <ul style={{ margin: '4px 0 0 18px' }}>
                                    {String(px.keyMaterials)
                                      .split('\n')
                                      .map((m: string, mi: number) => (m.trim() ? <li key={mi}>{m.trim()}</li> : null))}
                                  </ul>
                                </div>
                              ) : null}
                            </div>
                          ),
                        },
                      ]}
                    />
                  );
                })()}
                {ph.resultFields && typeof ph.resultFields === 'object' && Object.keys(ph.resultFields).length > 0 ? (
                  <div style={{ margin: '0 0 4px' }}>
                    {Object.entries(ph.resultFields).map(([k, v]) => (
                      <Tag key={k} style={{ marginBottom: 4 }}>
                        {k}：{String(v)}
                      </Tag>
                    ))}
                  </div>
                ) : null}
                {ph.status === 'IN_PROGRESS' ? (
                  <div className="pm-progress">
                    <Progress percent={ph.percent} size="small" />
                  </div>
                ) : null}
                {pAtts.length > 0 ? (
                  <div className="pm-chips">
                    {pAtts.map((a) => (
                      <Tooltip
                        key={a.id}
                        title={`${a.fileName} · ${fmtFileSize(a.fileSize)} · ${a.uploadUserName || '系统'} · 点击预览`}
                      >
                        <span className="pm-chip" style={{ cursor: 'pointer' }} onClick={() => setPreviewAtt(a)}>
                          <span className="pm-ext">{(a.fileExt || 'file').toUpperCase()}</span>
                          <span className="pm-name">{a.fileName}</span>
                          <span className="pm-sz">{fmtFileSize(a.fileSize)}</span>
                        </span>
                      </Tooltip>
                    ))}
                  </div>
                ) : (
                  <div className="pm-muted" style={{ marginTop: 6 }}>
                    暂无附件
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>
    );

  const infoContent = (
    <Card size="small">
      <Descriptions bordered column={2} size="small">
        <Descriptions.Item label="项目编号">{detail.code}</Descriptions.Item>
        <Descriptions.Item label="项目名称">{detail.name}</Descriptions.Item>
        <Descriptions.Item label="项目类型">{PROJECT_TYPES[detail.type]}</Descriptions.Item>
        <Descriptions.Item label="项目状态">
          {statusMeta(detail.status)?.text || detail.status || '-'}
        </Descriptions.Item>
        <Descriptions.Item label="甲方单位">{detail.ownerUnit || '-'}</Descriptions.Item>
        <Descriptions.Item label="项目经理">{detail.managerName || '-'}</Descriptions.Item>
        <Descriptions.Item label="参与人员">{(detail.memberNames || []).join('、') || '-'}</Descriptions.Item>
        <Descriptions.Item label="供应商">{detail.vendorName || '-'}</Descriptions.Item>
        <Descriptions.Item label="供应商联系人">{detail.vendorContact || '-'}</Descriptions.Item>
        <Descriptions.Item label="批复文号">{detail.approveNo || '-'}</Descriptions.Item>
        <Descriptions.Item label="项目来源">{dictName(sources, detail.projectSource)}</Descriptions.Item>
        <Descriptions.Item label="预算金额">{fmtMoney(detail.budgetAmount)} 元</Descriptions.Item>
        <Descriptions.Item label="资金来源">{dictName(fundSources, detail.fundSource)}</Descriptions.Item>
        <Descriptions.Item label="招标方式">{dictName(bidTypes, detail.bidType)}</Descriptions.Item>
        <Descriptions.Item label="中标金额">{fmtMoney(detail.bidAmount)} 元</Descriptions.Item>
        <Descriptions.Item label="合同编号">{detail.contractNo || '-'}</Descriptions.Item>
        <Descriptions.Item label="合同金额">{fmtMoney(detail.contractAmount)} 元</Descriptions.Item>
        <Descriptions.Item label="变更金额">
          {fmtMoney(detail.changeAmount)} 元
        </Descriptions.Item>
        <Descriptions.Item label="合同当前总额">
          <b>{fmtMoney(detail.contractTotal)}</b> 元
        </Descriptions.Item>
        <Descriptions.Item label="立项日期">{fmtDate(detail.approveDate)}</Descriptions.Item>
        <Descriptions.Item label="计划起止">
          {fmtDate(detail.planStartDate)} ~ {fmtDate(detail.planFinishDate)}
        </Descriptions.Item>
        <Descriptions.Item label="实际完成(终验)">{fmtDate(detail.actualFinishDate)}</Descriptions.Item>
        <Descriptions.Item label="创建/更新时间">
          {fmtDateTime(detail.createTime)} / {fmtDateTime(detail.updateTime)}
        </Descriptions.Item>
        <Descriptions.Item label="建设内容" span={2}>
          {detail.contentSummary || '-'}
        </Descriptions.Item>
        <Descriptions.Item label="备注" span={2}>
          {detail.remark || '-'}
        </Descriptions.Item>
      </Descriptions>
      <ProjectOverviewPanel projectId={detail.id} overview={detail.overview} canEdit={canEdit} onSaved={reload} />
    </Card>
  );

  // 资金汇总：按实际合同/付款数据实时计算（登记合同/付款后 reload 即刷新，与列表/详情口径一致）
  const totalFromContracts = contracts.reduce((s, c) => s + (c.contractAmount || 0) + (c.changeAmount || 0), 0);
  const paidFromPayments = payments.reduce((s, p) => s + (p.paidAmount || 0), 0);

  const fundSummary = (
    <Row gutter={12} style={{ marginBottom: 12 }}>
      <Col span={6}>
        <Card size="small">预算金额：<b>{fmtMoney(detail.budgetAmount)}</b></Card>
      </Col>
      <Col span={6}>
        <Card size="small">合同总额：<b>{fmtMoney(totalFromContracts)}</b></Card>
      </Col>
      <Col span={6}>
        <Card size="small">累计已付：<b style={{ color: '#1677ff' }}>{fmtMoney(paidFromPayments)}</b></Card>
      </Col>
      <Col span={6}>
        <Card size="small">
          待付：<b style={{ color: '#fa541c' }}>{fmtMoney(Math.max(totalFromContracts - paidFromPayments, 0))}</b>
        </Card>
      </Col>
    </Row>
  );

  const payColumns: ColumnsType<PaymentItem> = [
    { title: '付款节点', dataIndex: 'nodeName', width: 110 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (v: string) => {
        const m = PAYMENT_STATUS[v];
        return m ? <Tag color={m.color}>{m.text}</Tag> : v;
      },
    },
    { title: '触发条件', dataIndex: 'conditionDesc', ellipsis: true },
    {
      title: '计划金额(元)',
      dataIndex: 'planAmount',
      align: 'right',
      width: 120,
      render: fmtMoney,
    },
    { title: '计划付款日期', dataIndex: 'planDate', width: 120, render: fmtDate },
    {
      title: '实付金额(元)',
      dataIndex: 'paidAmount',
      align: 'right',
      width: 120,
      render: (v?: number) => <b>{fmtMoney(v || 0)}</b>,
    },
    { title: '实付日期', dataIndex: 'paidDate', width: 120, render: fmtDate },
    {
      title: '凭证附件',
      key: 'att',
      width: 250,
      render: (_, row) => {
        const atts = attachments.filter((a) => a.bizType === 'PAYMENT' && a.bizId === row.id);
        if (!atts.length) return <span style={{ color: '#bfbfbf' }}>—</span>;
        return (
          <Space size={[4, 4]} wrap>
            {atts.map((a) => (
              <Tooltip key={a.id} title={a.fileName}>
                <Button
                  size="small"
                  type="link"
                  icon={<FileTextOutlined />}
                  onClick={() => setPreviewAtt(a)}
                  style={{ paddingLeft: 0 }}
                >
                  {a.fileName.length > 14 ? a.fileName.slice(0, 14) + '…' : a.fileName}
                </Button>
              </Tooltip>
            ))}
          </Space>
        );
      },
    },
    {
      title: '操作',
      key: 'op',
      width: 200,
      render: (_, row) =>
        canManagePay || canEdit ? (
          <Space size={4}>
            {canManagePay && (
              <Button size="small" type="link" onClick={() => setPayModal({ open: true, item: row })}>
                编辑
              </Button>
            )}
            <Button
              size="small"
              type="link"
              icon={<PaperClipOutlined />}
              onClick={() =>
                row.id &&
                setUpload({
                  open: true,
                  bizType: 'PAYMENT',
                  bizId: row.id,
                  label: `付款凭证：${row.nodeName}`,
                  fixedAttachType: 'PAY_VOUCHER',
                })
              }
            >
              凭证
            </Button>
            {canManagePay && (
              <Button
                size="small"
                type="link"
                danger
                onClick={() => {
                  Modal.confirm({
                    title: '删除付款记录',
                    content: `确定删除「${row.nodeName}」这条付款记录吗？`,
                    okButtonProps: { danger: true },
                    onOk: async () => {
                      await paymentApi.remove(row.id!);
                      reload();
                    },
                  });
                }}
              >
                删除
              </Button>
            )}
          </Space>
        ) : null,
    },
  ];

  // 付款明细按合同分组（每份合同一块，付款归属对应合同）
  const renderPayTable = (rows: PaymentItem[], emptyText: string) => (
    <Table<PaymentItem>
      rowKey={(r) => r.id ?? `new-${r.nodeCode}-${r.contractId ?? 'none'}`}
      columns={payColumns}
      dataSource={rows}
      pagination={false}
      size="small"
      locale={{ emptyText: <Empty description={emptyText} /> }}
    />
  );
  const unboundPayments = payments.filter((p) => !p.contractId);

  // 合同附件：与“附件中心”互通（类别=合同扫描件；项目级或“合同签订”阶段级均可，两处自动同步）
  const contractPhaseIds = new Set(
    (detail?.phases || []).filter((p) => p.phaseName === '合同签订').map((p) => p.id),
  );
  const contractAtts = attachments.filter(
    (a) =>
      a.attachType === 'CONTRACT' &&
      ((a.bizType === 'PROJECT' && a.bizId === detail?.id) ||
        (a.bizType === 'PROJECT_PHASE' && contractPhaseIds.has(a.bizId))),
  );

  // 折叠结构：每份合同 = 一个面板（父），其付款明细 = 面板内容（子）
  const contractPanels: CollapseProps['items'] = contracts.map((c) => {
    const rows = payments.filter((p) => p.contractId === c.id);
    const paid = rows.reduce((s, p) => s + (p.paidAmount || 0), 0);
    return {
      key: String(c.id),
      label: (
        <Space wrap size={8}>
          <b>{c.name}</b>
          {c.contractNo ? <Tag>{c.contractNo}</Tag> : null}
          {c.vendorName ? <Tag color="geekblue">{c.vendorName}</Tag> : null}
          <span style={{ fontSize: 12 }}>
            合同金额 <b>{fmtMoney(c.contractAmount)}</b> 元
          </span>
          <span style={{ fontSize: 12, color: '#1677ff' }}>已付 {fmtMoney(paid)} 元</span>
          <span style={{ fontSize: 12, color: '#bfbfbf' }}>付款 {rows.length} 条</span>
        </Space>
      ),
      extra: canManagePay ? (
        <div onClick={(e) => e.stopPropagation()}>
          <Space size={0}>
            <Button size="small" type="link" onClick={() => openContractForm(c)}>
              编辑合同
            </Button>
            <Popconfirm
              title="删除该合同？"
              onConfirm={async () => {
                await contractApi.remove(c.id!);
                message.success('合同已删除');
                reload();
              }}
            >
              <Button size="small" type="link" danger>
                删除
              </Button>
            </Popconfirm>
          </Space>
        </div>
      ) : undefined,
      children: (
        <div>
          <div style={{ marginBottom: 8, display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 8 }}>
            <span style={{ fontSize: 12, color: '#8c8c8c' }}>本合同付款明细</span>
            {canManagePay && (
              <Button
                size="small"
                type="primary"
                ghost
                icon={<PlusOutlined />}
                onClick={() => setPayModal({ open: true, item: null, contractId: c.id ?? null })}
              >
                登记本合同付款
              </Button>
            )}
          </div>
          {renderPayTable(rows, '本合同暂无付款记录')}
        </div>
      ),
    };
  });

  const fundContent = (
    <div>
      {fundSummary}
      <div style={{ marginBottom: 12 }}>
        {canManagePay && (
          <Space wrap>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => openContractForm(null)}>
              登记合同
            </Button>
            <Button type="primary" ghost icon={<PlusOutlined />} onClick={() => setPayModal({ open: true, item: null })}>
              新增付款记录
            </Button>
          </Space>
        )}
      </div>
      <Card
        size="small"
        style={{ marginBottom: 12 }}
        title={`合同附件（${contractAtts.length}）`}
        extra={
          canEdit ? (
            <Button
              size="small"
              type="primary"
              ghost
              icon={<UploadOutlined />}
              onClick={() =>
                setUpload({
                  open: true,
                  bizType: 'PROJECT',
                  bizId: detail.id,
                  label: '合同扫描件（同步至资金情况）',
                  fixedAttachType: 'CONTRACT',
                })
              }
            >
              上传合同附件
            </Button>
          ) : undefined
        }
      >
        {contractAtts.length ? (
          <Space size={[8, 8]} wrap>
            {contractAtts.map((a) => (
              <Space key={a.id} size={0}>
                <Button
                  size="small"
                  type="link"
                  icon={<FileTextOutlined />}
                  onClick={() => setPreviewAtt(a)}
                  style={{ paddingLeft: 0 }}
                >
                  {a.fileName.length > 18 ? a.fileName.slice(0, 18) + '…' : a.fileName}
                </Button>
                <a href={attachmentUrl(a.id)} download={a.fileName}>
                  <DownloadOutlined style={{ color: '#8c8c8c' }} />
                </a>
              </Space>
            ))}
          </Space>
        ) : (
          <span style={{ fontSize: 12, color: '#bfbfbf' }}>暂无合同附件</span>
        )}
      </Card>
      {contracts.length > 0 ? (
        <Collapse
          key={contracts.map((c) => String(c.id)).join('-')}
          defaultActiveKey={contracts.map((c) => String(c.id))}
          expandIconPosition="end"
          items={contractPanels}
        />
      ) : (
        <Card size="small" style={{ marginBottom: 12 }}>
          <Empty description="尚未登记合同" />
        </Card>
      )}
      {unboundPayments.length > 0 && (
        <Card size="small" title={`未关联合同的付款记录（${unboundPayments.length}）`} style={{ marginTop: 12, borderColor: '#ffd666' }}>
          {renderPayTable(unboundPayments, '')}
        </Card>
      )}
    </div>
  );

  const attachContent = (() => {
    const visible = grouped.filter((g) => {
      if (attachTypeFilter?.length && !g.items.some((it) => !!it.attachType && attachTypeFilter.includes(it.attachType)))
        return false;
      if (groupFilter?.length && !groupFilter.includes(g.title)) return false;
      return true;
    });
    const groupOptions = grouped.map((g) => ({ value: g.title, label: g.title }));
    return (
      <Card size="small">
        <Space wrap style={{ marginBottom: 12 }}>
          {canEdit && (
            <Button
              type="primary"
              icon={<UploadOutlined />}
              onClick={() =>
                setUpload({ open: true, bizType: 'PROJECT', bizId: Number(id), label: isContainer ? '总项目公用附件' : '项目级附件' })
              }
            >
              上传附件（项目级）
            </Button>
          )}
          <Select
            mode="multiple"
            allowClear
            placeholder="文档类别"
            style={{ width: 200 }}
            value={attachTypeFilter}
            onChange={(v) => setAttachTypeFilter(v as string[])}
            options={attachTypes.map((d) => ({ value: d.code, label: d.name }))}
            maxTagCount="responsive"
          />
          <Select
            mode="multiple"
            allowClear
            placeholder="按归属过滤"
            style={{ width: 220 }}
            value={groupFilter}
            onChange={(v) => setGroupFilter(v as string[])}
            options={groupOptions}
            maxTagCount="responsive"
          />
        </Space>
        {grouped.length === 0 ? (
          <Empty description="暂无附件" />
        ) : visible.length === 0 ? (
          <Empty description="没有符合当前过滤条件的附件" />
        ) : (
          <Collapse
            key={visible.map((g) => g.title).join('|')}
            defaultActiveKey={visible.map((g) => g.title)}
            items={visible.map((g) => ({
              key: g.title,
              label: (
                <Space size={8}>
                  <b style={{ fontSize: 14 }}>{g.title}</b>
                  <span style={{ color: '#8c8c8c', fontSize: 12 }}>{g.items.length} 个文件</span>
                  {canEdit && g.kind !== 'PAYMENT' && g.kind !== 'PARENT' ? (
                    <Button
                      size="small"
                      type="primary"
                      ghost
                      icon={<UploadOutlined />}
                      onClick={(e) => {
                        e.stopPropagation();
                        setUpload({
                          open: true,
                          bizType: g.bizType,
                          bizId: g.bizId,
                          label: g.kind === 'PHASE' ? (g.phaseName || '阶段') : g.kind === 'PARENT' ? '总项目公用附件' : isContainer ? '总项目公用附件' : '项目级附件',
                        });
                      }}
                    >
                      上传到此{ g.kind === 'PHASE' ? '阶段' : '' }
                    </Button>
                  ) : null}
                </Space>
              ),
              children: (
                <List
                  size="small"
                  dataSource={g.items}
                  renderItem={(a) => (
                    <List.Item
                      actions={[
                        <Tooltip key="pv" title="在线预览">
                          <Button size="small" type="link" icon={<EyeOutlined />} onClick={() => setPreviewAtt(a)}>
                            预览
                          </Button>
                        </Tooltip>,
                        <Tooltip key="dl" title="下载">
                          <Button
                            size="small"
                            type="link"
                            icon={<DownloadOutlined />}
                            href={attachmentUrl(a.id)}
                            download={a.fileName}
                          >
                            下载
                          </Button>
                        </Tooltip>,
                        ...(canEdit
                          ? [
                              <Button
                                key="del"
                                size="small"
                                type="link"
                                danger
                                onClick={() => {
                                  Modal.confirm({
                                    title: '删除附件',
                                    content: `确定删除「${a.fileName}」吗？将做逻辑删除保留文件。`,
                                    okButtonProps: { danger: true },
                                    onOk: async () => {
                                      await attachmentApi.remove(a.id);
                                      reload();
                                    },
                                  });
                                }}
                              >
                                删除
                              </Button>,
                            ]
                          : []),
                      ]}
                    >
                      <Space wrap>
                        <FileTextOutlined />
                        <span>{a.fileName}</span>
                        {a.attachType ? <Tag>{attachTypeName(a.attachType)}</Tag> : null}
                        <span style={{ color: '#8c8c8c', fontSize: 12 }}>{fmtFileSize(a.fileSize)}</span>
                        <span style={{ color: '#8c8c8c', fontSize: 12 }}>
                          {a.uploadUserName || '-'} · {fmtDateTime(a.uploadTime)}
                        </span>
                      </Space>
                    </List.Item>
                  )}
                />
              ),
            }))}
          />
        )}
      </Card>
    );
  })();

  const logContent = (() => {
    const totalPages = Math.max(1, Math.ceil(logs.length / logSize));
    const cur = Math.min(logPage, totalPages);
    const slice = logs.slice((cur - 1) * logSize, cur * logSize);
    const actionColor = (act?: string) =>
      !act ? 'default' : act.includes('DELETE') ? 'red' : act.includes('UPLOAD') ? 'green' : act === 'LOGIN' ? 'default' : 'blue';
    return (
      <Card size="small">
        <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 10 }}>
          共 {logs.length} 条操作记录（按时间倒序），实时记录本项目的创建、编辑、阶段推进、附件与付款操作。
        </div>
        {logs.length === 0 ? (
          <Empty description="暂无操作日志" />
        ) : (
          <>
            <List
              size="small"
              dataSource={slice}
              renderItem={(l) => (
                <List.Item style={{ padding: '10px 0', alignItems: 'flex-start' }}>
                  <Space align="start" size={10} style={{ width: '100%' }}>
                    <Avatar
                      size={32}
                      style={{ background: '#2f5d8a', flexShrink: 0, marginTop: 2 }}
                    >
                      {(l.userName || '系')[0]}
                    </Avatar>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <Space size={8} wrap>
                        <b style={{ fontSize: 13 }}>{l.userName || '系统'}</b>
                        <span style={{ color: '#bfbfbf', fontSize: 12 }}>{fmtDateTime(l.createTime)}</span>
                        <Tag color={actionColor(l.action)}>{l.action}</Tag>
                      </Space>
                      <div
                        style={{
                          marginTop: 4,
                          background: '#f7f9fc',
                          borderRadius: 8,
                          padding: '8px 12px',
                          fontSize: 13,
                          color: '#404040',
                          lineHeight: 1.7,
                        }}
                      >
                        {l.detail}
                      </div>
                    </div>
                  </Space>
                </List.Item>
              )}
            />
            {totalPages > 1 && (
              <div style={{ textAlign: 'center', marginTop: 8 }}>
                <Pagination
                  size="small"
                  current={cur}
                  pageSize={logSize}
                  total={logs.length}
                  showSizeChanger
                  pageSizeOptions={[5, 8, 15, 30]}
                  onChange={(p, s) => {
                    setLogPage(p);
                    setLogSize(s);
                  }}
                  showTotal={(t) => `共 ${t} 条`}
                />
              </div>
            )}
          </>
        )}
      </Card>
    );
  })();

  // 子项目付款标签摘要（与项目管理列表卡片同款）
  const childPayChips = (list?: ProjectListItem['payments']) => {
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

  // 总项目容器：子项目页签（与项目管理列表“卡片网格”同款布局）
  const childrenTabContent = (
    <Card size="small" title={`子项目（${children.length}）`}>
      {children.length === 0 ? (
        <Empty description="暂无子项目，点击右上角“新增子项目”" />
      ) : (
        <Row gutter={[12, 12]}>
          {children.map((ch) => {
            const st = projectStatusTag(ch.status);
            const ty = projectTypeTag(ch.type);
            const ph = phaseNameTag(ch.currentPhaseName);
            return (
              <Col key={ch.id} xs={24} lg={12}>
                <Card
                  size="small"
                  hoverable
                  onClick={() => navigate(`/projects/${ch.id}`)}
                  title={
                    <Tooltip title={ch.name}>
                      <span style={{ fontWeight: 600 }}>{ch.name}</span>
                    </Tooltip>
                  }
                  extra={<Tag color={st.color}>{st.text}</Tag>}
                >
                  <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 6 }}>
                    <span style={{ fontFamily: 'Consolas,monospace' }}>{ch.code}</span> ·{' '}
                    <Tag color={ty.color} style={{ marginRight: 0 }}>
                      {ty.text}
                    </Tag>
                  </div>
                  <div style={{ marginBottom: 6, fontSize: 12 }}>
                    当前阶段：
                    {ch.currentPhaseName ? (
                      <Tag color={ph.color} style={{ marginRight: 0 }}>
                        {ph.text}
                      </Tag>
                    ) : (
                      '-'
                    )}
                  </div>
                  <Progress percent={ch.overallProgress || 0} size="small" />
                  <div style={{ fontSize: 12, color: '#595959', marginTop: 6 }}>{childPayChips(ch.payments)}</div>
                  <div style={{ fontSize: 12, color: '#595959', marginTop: 4 }}>
                    合同 {fmtMoney(ch.contractAmount)} 元　已付 {fmtMoney(ch.paidAmount)} 元
                  </div>
                </Card>
              </Col>
            );
          })}
        </Row>
      )}
    </Card>
  );

  return (
    <div>
      {header}
      <Tabs
        defaultActiveKey={isContainer ? 'info' : 'phases'}
        items={[
          ...(isContainer
            ? []
            : [{ key: 'phases', label: '流程进展', children: phaseContent }]),
          { key: 'info', label: '项目信息', children: infoContent },
          ...(isContainer
            ? [{ key: 'children', label: `子项目（${children.length}）`, children: childrenTabContent }]
            : []),
          ...(isContainer
            ? []
            : [{ key: 'fund', label: '资金情况', children: fundContent }]),
          { key: 'attach', label: '附件中心', children: attachContent },
          { key: 'log', label: '操作日志', children: logContent },
        ]}
      />

      <ProjectFormModal
        open={editOpen}
        initial={editingChild ? null : detail}
        presetParentId={editingChild ? presetParent : undefined}
        submitting={submitting}
        onOk={onProjectSaved}
        onCancel={() => {
          setEditOpen(false);
          setEditingChild(false);
          setPresetParent(null);
        }}
      />
      <PhaseEditModal
        open={phaseModal.open}
        phase={phaseModal.phase}
        submitting={submitting}
        onOk={onPhaseSaved}
        onCancel={() => setPhaseModal({ open: false, phase: null })}
      />
      {upload && (
        <AttachmentUploadModal
          open={upload.open}
          projectId={detail.id}
          bizType={upload.bizType}
          bizId={upload.bizId}
          scopeLabel={upload.label}
          fixedAttachType={upload.fixedAttachType}
          onDone={reload}
          onCancel={() => setUpload(null)}
        />
      )}
      {payModal.open && (
        <PaymentModal
          open={payModal.open}
          projectId={detail.id}
          item={payModal.item}
          initialNodeCode={payModal.nodeCode}
          nodeOptions={payNodes}
          contracts={contracts}
          presetContractId={payModal.contractId ?? null}
          onCancel={() => setPayModal({ open: false, item: null })}
          onDone={reload}
        />
      )}
      {previewAtt && <AttachmentPreviewModal item={previewAtt} onClose={() => setPreviewAtt(null)} />}
      {contractModalEl}
    </div>
  );
}

/* ==================== 付款记录新增/编辑 ==================== */

interface PaymentModalProps {
  open: boolean;
  projectId: number;
  item: PaymentItem | null;
  /** 从“流程进展”付款节点带入的节点编码（新增场景） */
  initialNodeCode?: string;
  nodeOptions: { code: string; name: string }[];
  contracts: ContractItem[];
  /** 新增时预选（按合同登记） */
  presetContractId?: number | null;
  onCancel: () => void;
  onDone: () => void;
}

interface PaymentFormValues {
  contractId?: number | null;
  nodeCode: string;
  /** 避免使用 name="nodeName"（与 React/antd 保留名冲突导致 elem.nodeName 报错） */
  nodeTitle?: string;
  conditionDesc?: string;
  planAmount?: number;
  planDate?: Dayjs | null;
  paidAmount?: number;
  paidDate?: Dayjs | null;
  status: string;
  remark?: string;
}

function PaymentModal({
  open,
  projectId,
  item,
  initialNodeCode,
  nodeOptions,
  contracts,
  presetContractId,
  onCancel,
  onDone,
}: PaymentModalProps) {
  const [form] = Form.useForm<PaymentFormValues>();
  const [saving, setSaving] = useState(false);
  const nodeCode = Form.useWatch('nodeCode', form);

  useEffect(() => {
    if (!open) return;
    if (item) {
      form.setFieldsValue({
        contractId: item.contractId ?? undefined,
        nodeCode: item.nodeCode,
        nodeTitle: item.nodeName,
        conditionDesc: item.conditionDesc,
        planAmount: item.planAmount ?? undefined,
        planDate: item.planDate ? dayjs(item.planDate) : null,
        paidAmount: item.paidAmount ?? 0,
        paidDate: item.paidDate ? dayjs(item.paidDate) : null,
        status: item.status,
        remark: item.remark,
      });
    } else {
      form.resetFields();
      form.setFieldsValue({ status: 'UNPAID', paidAmount: 0, nodeCode: initialNodeCode, contractId: presetContractId ?? undefined });
    }
  }, [open, item, initialNodeCode, presetContractId, form]);

  const submit = () => {
    form.validateFields().then(async (values) => {
      setSaving(true);
      try {
        const payload: PaymentItem = {
          ...(item?.id ? { id: item.id } : {}),
          projectId,
          contractId: values.contractId ?? item?.contractId ?? null,
          nodeCode: values.nodeCode,
          nodeName: values.nodeTitle || nodeOptions.find((n) => n.code === values.nodeCode)?.name || values.nodeCode,
          conditionDesc: values.conditionDesc || undefined,
          planAmount: values.planAmount ?? null,
          planDate: values.planDate ? values.planDate.format('YYYY-MM-DD') : null,
          paidAmount: values.paidAmount ?? 0,
          paidDate: values.paidDate ? values.paidDate.format('YYYY-MM-DD') : null,
          status: values.status,
          remark: values.remark,
        };
        if (item?.id) {
          await paymentApi.update(item.id, payload);
        } else {
          await paymentApi.create(payload);
        }
        message.success('保存成功');
        onDone();
        onCancel();
      } finally {
        setSaving(false);
      }
    });
  };

  return (
    <Modal
      title={item ? `编辑付款记录：${item.nodeName}` : '新增付款记录'}
      open={open}
      onCancel={onCancel}
      width={640}
      footer={[
        <Button key="cancel" onClick={onCancel}>
          取消
        </Button>,
        <Button key="ok" type="primary" loading={saving} onClick={submit}>
          保存
        </Button>,
      ]}
    >
      {!item && initialNodeCode && (
        <div
          style={{
            marginBottom: 12,
            background: '#e6f4ff',
            border: '1px solid #91caff',
            borderRadius: 6,
            padding: '6px 10px',
            fontSize: 13,
          }}
        >
          请按合同录入「{nodeOptions.find((n) => n.code === initialNodeCode)?.name || initialNodeCode}」的计划金额与实际支付情况。
        </div>
      )}
      <Form<PaymentFormValues> form={form} labelCol={{ span: 6 }} wrapperCol={{ span: 16 }}>
        <Form.Item label="所属合同" name="contractId">
          <Select
            allowClear
            placeholder="请选择本合同关联的付款；不选则按后端归属逻辑处理"
            options={contracts.map((c) => ({
              value: c.id!,
              label: c.name + (c.contractNo ? `（${c.contractNo}）` : ''),
            }))}
          />
        </Form.Item>
        <Form.Item label="付款节点" name="nodeCode" rules={[{ required: true, message: '请选择付款节点' }]}>
          <Select options={nodeOptions.map((n) => ({ value: n.code, label: n.name }))} />
        </Form.Item>
        <Form.Item label="节点名称" name="nodeTitle">
          <Input placeholder="默认取节点名称，可改" />
        </Form.Item>
        <Form.Item label="触发条件" name="conditionDesc">
          <Input placeholder="如：到货验收合格后 15 日内" />
        </Form.Item>
        <Form.Item label="计划金额(元)" name="planAmount">
          <InputNumber min={0} precision={2} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item label="计划付款日期" name="planDate">
          <DatePicker style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item label="实付金额(元)" name="paidAmount">
          <InputNumber min={0} precision={2} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item label="实际付款日期" name="paidDate">
          <DatePicker style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item label="状态" name="status" rules={[{ required: true }]}>
          <Select
            options={Object.entries(PAYMENT_STATUS).map(([value, m]) => ({ value, label: m.text }))}
          />
        </Form.Item>
        <Form.Item label="备注" name="remark">
          <Input.TextArea rows={2} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
