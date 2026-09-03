import { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Input,
  InputNumber,
  Popconfirm,
  Space,
  Tag,
  message,
} from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import { contractApi, paymentApi } from '@/api/project';
import { useFormModal } from '@/components/useFormModal';
import { fmtMoney } from '@/utils/format';
import { ContractItem, PaymentItem } from '@/types';

interface Props {
  projectId: number;
  canManage: boolean;
}

interface Covered {
  id: number;
  name: string;
  code: string;
}

/**
 * 合同管理（v1.4 口径）：分（子）项目后，每个项目单独签订合同——
 * 即使供应商相同也各自登记（不跨项目共享合同）。金额由管理员录入。
 */
export default function ContractPanel({ projectId, canManage }: Props) {
  const { open, el } = useFormModal();
  const [contracts, setContracts] = useState<ContractItem[]>([]);
  const [covered, setCovered] = useState<Record<number, Covered[]>>({});
  const [payments, setPayments] = useState<PaymentItem[]>([]);
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
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    load();
  }, [load]);

  const paidOf = (cid?: number | null) =>
    payments
      .filter((p) => p.contractId === cid)
      .reduce((s, p) => s + (p.paidAmount || 0), 0);

  const openForm = (editing: ContractItem | null) => {
    open(
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
          projectIds: [projectId],
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
    <Card size="small" style={{ marginBottom: 12 }} title="本项目合同（每个（子）项目单独签订；金额由管理员录入）" loading={loading}>
      {!canManage && (
        <div style={{ marginBottom: 8, fontSize: 12, color: '#8c8c8c' }}>
          当前为只读角色，可查看合同与已付金额。
        </div>
      )}
      {canManage && (
        <Space style={{ marginBottom: 8 }}>
          <Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => openForm(null)}>
            登记合同
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
          message={canManage ? '尚未登记合同：点击“登记合同”录入金额（含供应商与编号）' : '本项目尚未登记合同'}
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
                {c.vendorName ? <Tag color="geekblue">{c.vendorName}</Tag> : null}
                <span style={{ fontSize: 13 }}>
                  合同金额 <b>{fmtMoney(c.contractAmount)}</b> 元
                </span>
                <span style={{ fontSize: 13, color: '#1677ff' }}>已付 {fmtMoney(paidOf(c.id))} 元</span>
                <Tag style={{ marginRight: 0 }}>绑定 {(covered[c.id!] || []).length ? `1 个项目` : '未绑定'}</Tag>
              </Space>
              {canManage && (
                <Space>
                  <Button size="small" type="link" icon={<EditOutlined />} onClick={() => openForm(c)}>
                    编辑
                  </Button>
                  <Popconfirm
                    title="删除该合同？"
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
