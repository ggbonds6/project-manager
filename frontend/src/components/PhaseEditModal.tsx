import { useEffect, useMemo, useState } from 'react';
import { Button, Col, DatePicker, Form, Input, InputNumber, Modal, Radio, Row, Select, Space } from 'antd';
import dayjs, { Dayjs } from 'dayjs';
import { useDict, useUsers } from '@/hooks/useOptions';
import { PHASE_STATUS, PhaseItem } from '@/types';

interface FormValues {
  status: string;
  percent: number;
  planStartDate?: Dayjs | null;
  planFinishDate?: Dayjs | null;
  actualStartDate?: Dayjs | null;
  actualFinishDate?: Dayjs | null;
  managerUserId?: number | null;
  payNode?: string;
  note?: string;
  resultJson?: string;
}

interface Props {
  open: boolean;
  phase: PhaseItem | null;
  submitting?: boolean;
  onOk: (data: Partial<PhaseItem>) => void;
  onCancel: () => void;
}

export default function PhaseEditModal({ open, phase, submitting, onOk, onCancel }: Props) {
  const [form] = Form.useForm<FormValues>();
  const users = useUsers();
  const { options: payNodes } = useDict('PAY_NODE');

  useEffect(() => {
    if (!open || !phase) return;
    form.setFieldsValue({
      status: phase.status,
      percent: phase.percent,
      planStartDate: phase.planStartDate ? dayjs(phase.planStartDate) : null,
      planFinishDate: phase.planFinishDate ? dayjs(phase.planFinishDate) : null,
      actualStartDate: phase.actualStartDate ? dayjs(phase.actualStartDate) : null,
      actualFinishDate: phase.actualFinishDate ? dayjs(phase.actualFinishDate) : null,
      managerUserId: phase.managerUserId ?? null,
      payNode: phase.payNode ?? undefined,
      note: phase.note ?? undefined,
      resultJson:
        phase.resultFields && typeof phase.resultFields === 'object'
          ? JSON.stringify(phase.resultFields, null, 2)
          : phase.resultFields
            ? String(phase.resultFields)
            : undefined,
    });
  }, [open, phase, form]);

  const status = Form.useWatch('status', form);

  const quickActions = useMemo(() => {
    if (!status) return null;
    const items: { label: string; value: string }[] = [];
    if (status === 'NOT_STARTED' || status === 'DONE' || status === 'SKIPPED') {
      items.push({ label: '标记进行中', value: 'IN_PROGRESS' });
    }
    if (status !== 'DONE') {
      items.push({ label: '标记完成', value: 'DONE' });
    }
    return items.length ? items : null;
  }, [status]);

  const submit = () => {
    form.validateFields().then((values) => {
      let resultFields: Record<string, unknown> | undefined;
      if (values.resultJson && values.resultJson.trim()) {
        try {
          resultFields = JSON.parse(values.resultJson);
        } catch {
          return void Modal.warning({ title: '提示', content: '关键结果字段不是合法 JSON，请检查后重试。' });
        }
      }
      onOk({
        status: values.status,
        percent: values.percent ?? 0,
        planStartDate: values.planStartDate ? values.planStartDate.format('YYYY-MM-DD') : null,
        planFinishDate: values.planFinishDate ? values.planFinishDate.format('YYYY-MM-DD') : null,
        actualStartDate: values.actualStartDate ? values.actualStartDate.format('YYYY-MM-DD') : null,
        actualFinishDate: values.actualFinishDate ? values.actualFinishDate.format('YYYY-MM-DD') : null,
        managerUserId: values.managerUserId ?? null,
        payNode: values.payNode ?? '',
        note: values.note || null,
        resultFields,
      });
    });
  };

  return (
    <Modal
      title={phase ? `阶段推进：${phase.phaseName}` : '阶段推进'}
      open={open}
      onCancel={onCancel}
      destroyOnClose
      footer={[
        <Button key="cancel" onClick={onCancel}>
          取消
        </Button>,
        <Button key="ok" type="primary" loading={submitting} onClick={submit}>
          保存
        </Button>,
      ]}
    >
      <Form<FormValues> form={form} labelCol={{ span: 7 }} wrapperCol={{ span: 17 }} initialValues={{ percent: 0, status: 'NOT_STARTED' }}>
        <Form.Item label="阶段状态" name="status" rules={[{ required: true }]}>
          <Radio.Group
            options={Object.entries(PHASE_STATUS).map(([value, m]) => ({ value, label: m.text }))}
          />
        </Form.Item>
        <Form.Item label="快捷推进">
          <Space>
            {quickActions?.map((a) => (
              <Button
                key={a.value}
                size="small"
                onClick={() => {
                  form.setFieldValue('status', a.value);
                  if (a.value === 'DONE') form.setFieldValue('percent', 100);
                  if (a.value === 'IN_PROGRESS' && !form.getFieldValue('actualStartDate')) {
                    form.setFieldValue('actualStartDate', dayjs());
                  }
                }}
              >
                {a.label}
              </Button>
            ))}
          </Space>
        </Form.Item>
        <Form.Item label="完成比例(%)" name="percent" rules={[{ required: true }]}>
          <InputNumber min={0} max={100} style={{ width: '100%' }} addonAfter="%" />
        </Form.Item>
        <Row>
          <Col span={12}>
            <Form.Item label="计划开始" name="planStartDate" labelCol={{ span: 14 }} wrapperCol={{ span: 10 }}>
              <DatePicker style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item label="计划完成" name="planFinishDate" labelCol={{ span: 14 }} wrapperCol={{ span: 10 }}>
              <DatePicker style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item label="实际开始" name="actualStartDate" labelCol={{ span: 14 }} wrapperCol={{ span: 10 }}>
              <DatePicker style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item label="实际完成" name="actualFinishDate" labelCol={{ span: 14 }} wrapperCol={{ span: 10 }}>
              <DatePicker style={{ width: '100%' }} />
            </Form.Item>
          </Col>
        </Row>
        <Form.Item label="阶段负责人" name="managerUserId">
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            options={users.map((u) => ({ value: u.id, label: `${u.name}（${u.account}）` }))}
          />
        </Form.Item>
        <Form.Item label="付款节点" name="payNode" tooltip="该阶段完成是否对应一笔里程碑付款（预付/到货/初验/终验/质保），可在资金情况中登记金额">
          <Select allowClear placeholder="无里程碑付款则留空" options={payNodes.map((p) => ({ value: p.code, label: p.name }))} />
        </Form.Item>
        <Form.Item label="经办记录" name="note">
          <Input.TextArea rows={3} placeholder="本阶段做了什么、结论如何" />
        </Form.Item>
        <Form.Item label="关键结果字段" name="resultJson">
          <Input.TextArea
            rows={4}
            placeholder={'JSON 格式，如：\n{"批复文号":"X发改〔2025〕12号","供应商":"XX公司"}，留空则不变'}
            style={{ fontFamily: 'Consolas,monospace', fontSize: 12 }}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}
