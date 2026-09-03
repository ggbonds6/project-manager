import { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Input,
  InputNumber,
  Popconfirm,
  Select,
  Space,
  Tag,
  Tooltip,
  message,
} from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import { contractApi, paymentApi, projectApi } from '@/api/project';
import { useFormModal } from '@/components/useFormModal';
import { fmtMoney } from '@/utils/format';
import { ContractItem, PaymentItem, ProjectListItem } from '@/types';

interface Props {
  projectId: number;
  /** 当前项目若是子项目则传其父项目 id，否则 null（顶层） */
  parentId?: number | null;
  canManage: boolean;
}

interface Covered {
  id: number;
  name: string;
  code: string;
}

/**
 * 合同管理（金额按合同由管理员录入）。
 * 一个合同可覆盖任意多个(子)项目——在资金情况页顶部展示与维护。
 */
export default function ContractPanel({ projectId, parentId, canManage }: Props) {
  const { open, el } = useFormModal();
  const [contracts, setContracts] = useState<ContractItem[]>([]);
  const [covered, setCovered] = useState<Record<number, Covered[]>>({});
  const [payments, setPayments] = useState<PaymentItem[]>([]);
  const [candidates, setCandidates] = useState<ProjectListItem[]>([]);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const cs = await contractApi.listByProject(projectId);
      setContracts(cs);
      const covs: Record<number, Covered[]> = {};
      await Promise.all(
        cs.map(async (c) => {
          if (c.id) covs[c.id] = await contractApi.covered(c.id);
        }),
      );
      setCovered(covs);
      setPayments(await paymentApi.listByProject(projectId));
      // 可选覆盖对象：子项目=同父兄弟（含自己）；顶层=其子项目；顶层无子项目则仅自身
      const root = parentId == null ? projectId : parentId;
      const res = await projectApi.page({ page: 1, size: 300, parentId: root });
      let list = res.records;
      if (!list.some((p) => p.id === projectId)) {
        const self = await projectApi.detail(projectId);
        list = [
          { id: self.id, code: self.code || '', name: self.name, type: self.type, status: self.status || 'RUN' },
          ...list,
        ];
      }
      setCandidates(list);
    } finally {
      setLoading(false);
    }
  }, [projectId, parentId]);

  useEffect(() => {
    load();
  }, [load]);

  const paidOf = (cid?: number | null) =>
    payments
      .filter((p) => p.contractId === cid)
      .reduce((s, p) => s + (p.paidAmount || 0), 0);

  const openForm = (editing: ContractItem | null) => {
    open(
      editing ? `编辑合同：${editing.name}` : '新建合同（可覆盖多个子项目）',
      [
        { name: 'name', label: '合同名称', el: <Input placeholder="如：一期总包合同（覆盖子项目A/B）" />, rules: [{ required: true }] },
        { name: 'contractNo', label: '合同编号', el: <Input /> },
        { name: 'vendorName', label: '供应商', el: <Input /> },
        { name: 'vendorContact', label: '联系人/电话', el: <Input /> },
        {
          name: 'contractAmount',
          label: '合同金额(元)',
          el: <InputNumber min={0} precision={2} style={{ width: '100%' }} />,
          rules: [{ required: true, message: '请录入合同金额' }],
        },
        { name: 'bidAmount', label: '中标金额(元)', el: <InputNumber min={0} precision={2} style={{ width: '100%' }} /> },
        { name: 'changeAmount', label: '变更金额(元)', el: <InputNumber precision={2} style={{ width: '100%' }} /> },
        {
          name: 'projectIds',
          label: '覆盖项目',
          el: (
            <Select
              mode="multiple"
              showSearch
              optionFilterProp="label"
              placeholder="选择该合同覆盖的子项目（可多选=共享合同）"
              options={candidates.map((c) => ({ value: c.id, label: `${c.name}（${c.code}）` }))}
            />
          ),
          rules: [{ required: true, message: '请选择该合同覆盖的项目' }],
        },
        { name: 'scopeRemark', label: '覆盖说明', el: <Input placeholder="如：覆盖其中 3 个子项目" /> },
        { name: 'remark', label: '备注', el: <Input.TextArea rows={2} /> },
      ],
      {
        name: editing?.name,
        contractNo: editing?.contractNo,
        vendorName: editing?.vendorName,
        vendorContact: editing?.vendorContact,
        contractAmount: editing?.contractAmount ?? undefined,
        bidAmount: editing?.bidAmount ?? undefined,
        changeAmount: editing?.changeAmount ?? 0,
        projectIds: editing?.id ? (covered[editing.id] || []).map((c) => c.id) : parentId != null ? [projectId] : [],
        scopeRemark: editing?.scopeRemark,
        remark: editing?.remark,
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
          remark: values.remark ? String(values.remark) : undefined,
          projectIds: (values.projectIds as number[]) || [],
        };
        if (editing?.id) {
          await contractApi.update(editing.id, data);
        } else {
          await contractApi.create(data);
        }
        message.success('合同已保存');
        load();
      },
    );
  };

  return (
    <Card size="small" style={{ marginBottom: 12 }} title="合同（一个合同可覆盖多个子项目；金额由管理员录入）" loading={loading}>
      {!canManage && (
        <div style={{ marginBottom: 8, fontSize: 12, color: '#8c8c8c' }}>
          当前为只读角色，可查看合同与已付金额。
        </div>
      )}
      {canManage && (
        <Space style={{ marginBottom: 8 }}>
          <Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => openForm(null)}>
            新建合同
          </Button>
          <Button size="small" onClick={load}>
            刷新
          </Button>
        </Space>
      )}
      {contracts.length === 0 ? (
        <Alert
          type="info"
          showIcon
          message={canManage ? '尚未登记合同：请点击“新建合同”，按合同录入金额并勾选覆盖的子项目' : '该项目尚未关联合同'}
        />
      ) : (
        <Space direction="vertical" style={{ width: '100%' }} size={8}>
          {contracts.map((c) => (
            <div
              key={c.id}
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                gap: 12,
                background: '#fafafa',
                border: '1px solid #f0f0f0',
                borderRadius: 8,
                padding: '8px 12px',
                flexWrap: 'wrap',
              }}
            >
              <Space size={10} wrap>
                <b>{c.name}</b>
                {c.contractNo ? <Tag>{c.contractNo}</Tag> : null}
                <span style={{ fontSize: 13 }}>
                  合同金额 <b>{fmtMoney(c.contractAmount)}</b> 元
                </span>
                <span style={{ fontSize: 13, color: '#1677ff' }}>已付 {fmtMoney(paidOf(c.id))} 元</span>
                <Tooltip title={(covered[c.id!] || []).map((x) => `${x.name}（${x.code}）`).join('；')}>
                  <Tag color="geekblue">覆盖 {(covered[c.id!] || []).length} 个</Tag>
                </Tooltip>
              </Space>
              {canManage && (
                <Space>
                  <Button size="small" type="link" icon={<EditOutlined />} onClick={() => openForm(c)}>
                    编辑
                  </Button>
                  <Popconfirm
                    title="删除该合同？将解除其对子项目的覆盖关联。"
                    onConfirm={async () => {
                      await contractApi.remove(c.id!);
                      load();
                    }}
                  >
                    <Button size="small" type="link" danger icon={<DeleteOutlined />}>
                      删除
                    </Button>
                  </Popconfirm>
                </Space>
              )}
            </div>
          ))}
        </Space>
      )}
      {el}
    </Card>
  );
}
