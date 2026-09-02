import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Descriptions,
  Divider,
  Empty,
  Form,
  Input,
  InputNumber,
  List,
  Modal,
  Progress,
  Row,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Timeline,
  Tooltip,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  DownloadOutlined,
  EditOutlined,
  FileTextOutlined,
  PaperClipOutlined,
  PlusOutlined,
  ReloadOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import dayjs, { Dayjs } from 'dayjs';
import { useNavigate, useParams } from 'react-router-dom';
import { attachmentApi, attachmentUrl, paymentApi, projectApi } from '@/api/project';
import ProjectFormModal from '@/components/ProjectFormModal';
import PhaseEditModal from '@/components/PhaseEditModal';
import AttachmentUploadModal from '@/components/AttachmentUploadModal';
import { useAuth } from '@/store/auth';
import { useDict } from '@/hooks/useOptions';
import { fmtDate, fmtDateTime, fmtFileSize, fmtMoney } from '@/utils/format';
import {
  AttachmentItem,
  LogItem,
  PAYMENT_STATUS,
  PaymentItem,
  PHASE_STATUS,
  PhaseItem,
  PROJECT_STATUS,
  PROJECT_TYPES,
  ProjectDetail,
  ProjectForm,
} from '@/types';

const STATUS_DOT: Record<string, string> = {
  NOT_STARTED: 'gray',
  IN_PROGRESS: 'blue',
  DONE: 'green',
  SKIPPED: 'gray',
};

const statusMeta = (s?: string) => (s ? PROJECT_STATUS[s] : undefined);

export default function ProjectDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { user } = useAuth();
  const [detail, setDetail] = useState<ProjectDetail | null>(null);
  const [payments, setPayments] = useState<PaymentItem[]>([]);
  const [attachments, setAttachments] = useState<AttachmentItem[]>([]);
  const [logs, setLogs] = useState<LogItem[]>([]);
  const [loading, setLoading] = useState(true);

  // 弹窗状态
  const [editOpen, setEditOpen] = useState(false);
  const [phaseModal, setPhaseModal] = useState<{ open: boolean; phase: PhaseItem | null }>({ open: false, phase: null });
  const [upload, setUpload] = useState<{ open: boolean; bizType: string; bizId: number; label: string } | null>(null);
  const [payModal, setPayModal] = useState<{ open: boolean; item: PaymentItem | null }>({ open: false, item: null });
  const [submitting, setSubmitting] = useState(false);

  const { options: fundSources } = useDict('FUND_SOURCE');
  const { options: bidTypes } = useDict('BID_TYPE');
  const { options: sources } = useDict('PROJECT_SOURCE');
  const { options: attachTypes } = useDict('ATTACH_TYPE');
  const { options: payNodes } = useDict('PAY_NODE');

  const canEdit = user?.role === 'ADMIN' || user?.role === 'MANAGER';

  const reload = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const [d, ps, as, ls] = await Promise.all([
        projectApi.detail(id),
        paymentApi.listByProject(id),
        attachmentApi.listByProject(id),
        projectApi.logs(id),
      ]);
      setDetail(d);
      setPayments(ps);
      setAttachments(as);
      setLogs(ls);
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    reload();
  }, [reload]);

  const dictName = (options: { code: string; name: string }[], code?: string | null) =>
    code ? options.find((o) => o.code === code)?.name || code : '-';

  const attachTypeName = (code?: string | null) => dictName(attachTypes, code);

  // 附件中心分组（按阶段/项目/付款凭证）
  const grouped = useMemo(() => {
    const groups: { title: string; items: AttachmentItem[] }[] = [];
    const byPhase = new Map<string, AttachmentItem[]>();
    for (const a of attachments) {
      const key =
        a.bizType === 'PROJECT_PHASE'
          ? `阶段：${a.phaseName || '未知阶段'}`
          : a.bizType === 'PAYMENT'
            ? '付款凭证'
            : '项目附件';
      if (!byPhase.has(key)) byPhase.set(key, []);
      byPhase.get(key)!.push(a);
    }
    for (const [title, items] of byPhase.entries()) {
      groups.push({ title, items });
    }
    return groups.sort((a, b) => a.title.localeCompare(b.title, 'zh'));
  }, [attachments]);

  // 附件中心“上传到”目标
  const [uploadScope, setUploadScope] = useState<{ bizType: string; bizId: number; label: string }>({
    bizType: 'PROJECT',
    bizId: Number(id),
    label: '项目级附件',
  });

  const onProjectSaved = async (values: ProjectForm) => {
    if (!detail) return;
    setSubmitting(true);
    try {
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
              <Tag color="geekblue">{PROJECT_TYPES[detail.type] || detail.type}</Tag>
              {statusMeta(detail.status) ? (
                <Tag color={statusMeta(detail.status)!.color}>{statusMeta(detail.status)!.text}</Tag>
              ) : null}
              <span style={{ color: '#8c8c8c' }}>{detail.code}</span>
            </Space>
          </Col>
          <Col>
            <Space size={8}>
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
            当前阶段：<b>{detail.currentPhaseName || '-'}</b>
          </Col>
          <Col xs={24} md={6}>
            整体进度：
            <Progress percent={detail.overallProgress || 0} size="small" style={{ width: 140, display: 'inline-block', marginLeft: 6 }} />
          </Col>
          <Col xs={24} md={6}>
            项目经理：{detail.managerName || '-'}（{detail.ownerDeptName || '未分部门'}）
          </Col>
          <Col xs={24} md={6}>
            合同总额：<b>{fmtMoney(detail.contractTotal)}</b> 元
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

  const phaseContent = (
    <Card size="small">
      {detail.phases.length === 0 ? (
        <Empty description="暂无阶段" />
      ) : (
        <Timeline
          items={detail.phases.map((ph) => {
            const pAtts = attachments.filter((a) => a.bizType === 'PROJECT_PHASE' && a.bizId === ph.id);
            return {
              color: STATUS_DOT[ph.status] || 'gray',
              children: (
                <Card type="inner" size="small" key={ph.id} style={{ marginBottom: 8 }}>
                  <Row gutter={12} align="middle">
                    <Col flex="auto">
                      <Space size={8} wrap>
                        <b>
                          {ph.sortNo}. {ph.phaseName}
                        </b>
                        <Tag color={PHASE_STATUS[ph.status]?.color}>{PHASE_STATUS[ph.status]?.text}</Tag>
                        {ph.weight ? <span style={{ color: '#8c8c8c' }}>权重 {ph.weight}</span> : null}
                        {ph.managerName ? <span style={{ color: '#8c8c8c' }}>负责人：{ph.managerName}</span> : null}
                      </Space>
                      <div style={{ marginTop: 4, color: '#595959', fontSize: 13 }}>
                        <Progress percent={ph.percent} size="small" style={{ maxWidth: 220, display: 'inline-block', marginRight: 12 }} />
                        {ph.status !== 'NOT_STARTED' && (
                          <span style={{ marginRight: 10 }}>
                            实际 {fmtDate(ph.actualStartDate)} ~ {fmtDate(ph.actualFinishDate)}
                          </span>
                        )}
                        <span>计划 {fmtDate(ph.planStartDate)} ~ {fmtDate(ph.planFinishDate)}</span>
                      </div>
                      {ph.note ? (
                        <div style={{ marginTop: 6, color: '#595959' }}>经办记录：{ph.note}</div>
                      ) : null}
                      {ph.resultFields && typeof ph.resultFields === 'object' && Object.keys(ph.resultFields).length > 0 ? (
                        <div style={{ marginTop: 6 }}>
                          {Object.entries(ph.resultFields).map(([k, v]) => (
                            <Tag key={k} style={{ marginBottom: 4 }}>
                              {k}：{String(v)}
                            </Tag>
                          ))}
                        </div>
                      ) : null}
                      {pAtts.length > 0 ? (
                        <div style={{ marginTop: 6 }}>
                          <Space size={[4, 4]} wrap>
                            {pAtts.map((a) => (
                              <Tooltip key={a.id} title={`${a.fileName} · ${fmtFileSize(a.fileSize)} · ${a.uploadUserName || ''}`}>
                                <Button size="small" icon={<PaperClipOutlined />} href={attachmentUrl(a.id)} target="_blank">
                                  {a.fileName}
                                </Button>
                              </Tooltip>
                            ))}
                          </Space>
                        </div>
                      ) : null}
                    </Col>
                    {canEdit && (
                      <Col>
                        <Space direction="vertical" size={4}>
                          <Button size="small" icon={<EditOutlined />} onClick={() => setPhaseModal({ open: true, phase: ph })}>
                            推进/编辑
                          </Button>
                          <Button
                            size="small"
                            icon={<UploadOutlined />}
                            onClick={() =>
                              setUpload({ open: true, bizType: 'PROJECT_PHASE', bizId: ph.id, label: ph.phaseName })
                            }
                          >
                            上传附件
                          </Button>
                        </Space>
                      </Col>
                    )}
                  </Row>
                </Card>
              ),
            };
          })}
        />
      )}
    </Card>
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
        <Descriptions.Item label="承接部门">{detail.ownerDeptName || '-'}</Descriptions.Item>
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
    </Card>
  );

  const fundSummary = (
    <Row gutter={12} style={{ marginBottom: 12 }}>
      <Col span={6}>
        <Card size="small">预算金额：<b>{fmtMoney(detail.budgetAmount)}</b></Card>
      </Col>
      <Col span={6}>
        <Card size="small">合同总额：<b>{fmtMoney(detail.contractTotal)}</b></Card>
      </Col>
      <Col span={6}>
        <Card size="small">累计已付：<b style={{ color: '#1677ff' }}>{fmtMoney(payments.reduce((s, p) => s + (p.paidAmount || 0), 0))}</b></Card>
      </Col>
      <Col span={6}>
        <Card size="small">
          待付：<b style={{ color: '#fa541c' }}>{fmtMoney((detail.contractTotal || 0) - payments.reduce((s, p) => s + (p.paidAmount || 0), 0))}</b>
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
      title: '操作',
      key: 'op',
      width: 200,
      render: (_, row) =>
        canEdit ? (
          <Space size={4}>
            <Button size="small" type="link" onClick={() => setPayModal({ open: true, item: row })}>
              编辑
            </Button>
            <Button
              size="small"
              type="link"
              icon={<PaperClipOutlined />}
              onClick={() => row.id && setUpload({ open: true, bizType: 'PAYMENT', bizId: row.id, label: `付款凭证：${row.nodeName}` })}
            >
              凭证
            </Button>
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
          </Space>
        ) : null,
    },
  ];

  const fundContent = (
    <div>
      {fundSummary}
      <Card size="small" styles={{ body: { padding: 0 } }}>
        {canEdit && (
          <div style={{ padding: 12 }}>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setPayModal({ open: true, item: null })}>
              新增付款记录
            </Button>
          </div>
        )}
        <Table<PaymentItem>
          rowKey={(r) => r.id ?? `new-${r.nodeCode}`}
          columns={payColumns}
          dataSource={payments}
          pagination={false}
          locale={{ emptyText: <Empty description="暂无付款记录" /> }}
        />
      </Card>
    </div>
  );

  const attachContent = (
    <Card size="small">
      {canEdit && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message={
            <Space wrap>
              <span>上传到：</span>
              <Select
                style={{ width: 220 }}
                value={uploadScope.bizId}
                onChange={(v) => {
                  if (v === Number(id)) {
                    setUploadScope({ bizType: 'PROJECT', bizId: Number(id), label: '项目级附件' });
                  } else {
                    const ph = detail.phases.find((p) => p.id === v);
                    if (ph) setUploadScope({ bizType: 'PROJECT_PHASE', bizId: ph.id, label: ph.phaseName });
                  }
                }}
                options={[
                  { value: Number(id), label: '项目级附件' },
                  ...detail.phases.map((p) => ({ value: p.id, label: p.phaseName })),
                ]}
              />
              <Button
                type="primary"
                icon={<UploadOutlined />}
                onClick={() =>
                  setUpload({
                    open: true,
                    bizType: uploadScope.bizType,
                    bizId: uploadScope.bizId,
                    label: uploadScope.label,
                  })
                }
              >
                上传附件
              </Button>
            </Space>
          }
        />
      )}
      {grouped.length === 0 ? (
        <Empty description="暂无附件" />
      ) : (
        grouped.map((g) => (
          <div key={g.title} style={{ marginBottom: 16 }}>
            <Divider orientation="left" style={{ marginTop: 0, fontSize: 13 }}>
              {g.title}（{g.items.length}）
            </Divider>
            <List
              size="small"
              dataSource={g.items}
              renderItem={(a) => (
                <List.Item
                  actions={[
                    <Tooltip key="dl" title="下载">
                      <Button
                        type="link"
                        size="small"
                        icon={<DownloadOutlined />}
                        href={attachmentUrl(a.id)}
                        download={a.fileName}
                      >
                        下载
                      </Button>
                    </Tooltip>,
                    <Tooltip key="pv" title="预览(pdf/图片)">
                      <Button type="link" size="small" href={attachmentUrl(a.id, 'inline')} target="_blank">
                        预览
                      </Button>
                    </Tooltip>,
                    ...(canEdit
                      ? [
                          <Button
                            key="del"
                            type="link"
                            size="small"
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
                  <Space>
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
          </div>
        ))
      )}
    </Card>
  );

  const logContent = (
    <Card size="small">
      {logs.length === 0 ? (
        <Empty description="暂无操作日志" />
      ) : (
        <Timeline
          items={logs.map((l) => ({
            color: l.action === 'DELETE' || l.action === 'ATTACH_DELETE' ? 'red' : 'blue',
            children: (
              <div>
                <div>
                  <b>{l.userName || '系统'}</b> · {fmtDateTime(l.createTime)}
                  <Tag style={{ marginLeft: 8 }}>{l.action}</Tag>
                </div>
                <div style={{ color: '#595959' }}>{l.detail}</div>
              </div>
            ),
          }))}
        />
      )}
    </Card>
  );

  return (
    <div>
      {header}
      <Tabs
        defaultActiveKey="phases"
        items={[
          { key: 'phases', label: '流程进展', children: phaseContent },
          { key: 'info', label: '项目信息', children: infoContent },
          { key: 'fund', label: '资金情况', children: fundContent },
          { key: 'attach', label: '附件中心', children: attachContent },
          { key: 'log', label: '操作日志', children: logContent },
        ]}
      />

      <ProjectFormModal open={editOpen} initial={detail} submitting={submitting} onOk={onProjectSaved} onCancel={() => setEditOpen(false)} />
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
          onDone={reload}
          onCancel={() => setUpload(null)}
        />
      )}
      {payModal.open && (
        <PaymentModal
          open={payModal.open}
          projectId={detail.id}
          item={payModal.item}
          nodeOptions={payNodes}
          onCancel={() => setPayModal({ open: false, item: null })}
          onDone={reload}
        />
      )}
    </div>
  );
}

/* ==================== 付款记录新增/编辑 ==================== */

interface PaymentModalProps {
  open: boolean;
  projectId: number;
  item: PaymentItem | null;
  nodeOptions: { code: string; name: string }[];
  onCancel: () => void;
  onDone: () => void;
}

interface PaymentFormValues {
  nodeCode: string;
  nodeName?: string;
  conditionDesc?: string;
  planAmount?: number;
  planDate?: Dayjs | null;
  paidAmount?: number;
  paidDate?: Dayjs | null;
  status: string;
  remark?: string;
}

function PaymentModal({ open, projectId, item, nodeOptions, onCancel, onDone }: PaymentModalProps) {
  const [form] = Form.useForm<PaymentFormValues>();
  const [saving, setSaving] = useState(false);
  const nodeCode = Form.useWatch('nodeCode', form);

  useEffect(() => {
    if (!open) return;
    if (item) {
      form.setFieldsValue({
        nodeCode: item.nodeCode,
        nodeName: item.nodeName,
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
      form.setFieldsValue({ status: 'UNPAID', paidAmount: 0 });
    }
  }, [open, item, form]);

  const submit = () => {
    form.validateFields().then(async (values) => {
      setSaving(true);
      try {
        const payload: PaymentItem = {
          ...(item?.id ? { id: item.id } : {}),
          projectId,
          nodeCode: values.nodeCode,
          nodeName: values.nodeName || nodeOptions.find((n) => n.code === values.nodeCode)?.name || values.nodeCode,
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
      <Form<PaymentFormValues> form={form} labelCol={{ span: 6 }} wrapperCol={{ span: 16 }}>
        <Form.Item label="付款节点" name="nodeCode" rules={[{ required: true, message: '请选择付款节点' }]}>
          <Select options={nodeOptions.map((n) => ({ value: n.code, label: n.name }))} />
        </Form.Item>
        <Form.Item label="节点名称" name="nodeName">
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
