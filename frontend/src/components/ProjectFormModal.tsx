import { useEffect } from 'react';
import {
  Button,
  Col,
  DatePicker,
  Form,
  Input,
  InputNumber,
  Modal,
  Row,
  Select,
} from 'antd';
import dayjs, { Dayjs } from 'dayjs';
import { useDict, useUsers } from '@/hooks/useOptions';
import { ProjectForm } from '@/types';

export interface ProjectFormValues extends Omit<ProjectForm, 'approveDate' | 'planStartDate' | 'planFinishDate'> {
  approveDate?: Dayjs | null;
  planStartDate?: Dayjs | null;
  planFinishDate?: Dayjs | null;
}

interface Props {
  open: boolean;
  /** 编辑时传入既有值；新建为 null */
  initial?: ProjectForm | null;
  submitting?: boolean;
  onOk: (values: ProjectForm) => void;
  onCancel: () => void;
}

function toDayjs(v?: string | null): Dayjs | null {
  return v ? dayjs(v) : null;
}

export default function ProjectFormModal({ open, initial, submitting, onOk, onCancel }: Props) {
  const [form] = Form.useForm<ProjectFormValues>();
  const { options: units } = useDict('OWNER_UNIT');
  const { options: fundSources } = useDict('FUND_SOURCE');
  const { options: bidTypes } = useDict('BID_TYPE');
  const { options: sources } = useDict('PROJECT_SOURCE');
  const users = useUsers();

  useEffect(() => {
    if (!open) return;
    if (initial) {
      form.setFieldsValue({
        ...initial,
        approveDate: toDayjs(initial.approveDate),
        planStartDate: toDayjs(initial.planStartDate),
        planFinishDate: toDayjs(initial.planFinishDate),
      });
    } else {
      form.resetFields();
      form.setFieldsValue({ type: 'HW', status: 'RUN' });
    }
  }, [open, initial, form]);

  const submit = () => {
    form.validateFields().then((values) => {
      const payload: ProjectForm = {
        ...values,
        code: values.code || undefined,
        approveDate: values.approveDate ? values.approveDate.format('YYYY-MM-DD') : undefined,
        planStartDate: values.planStartDate ? values.planStartDate.format('YYYY-MM-DD') : undefined,
        planFinishDate: values.planFinishDate ? values.planFinishDate.format('YYYY-MM-DD') : undefined,
        ownerDeptId: values.ownerDeptId ?? null,
        managerUserId: values.managerUserId ?? null,
        memberIds: values.memberIds ?? [],
      };
      onOk(payload);
    });
  };

  const labelW = 108;

  return (
    <Modal
      title={initial ? '编辑项目' : '新建项目'}
      open={open}
      width={820}
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
      <Form<ProjectFormValues> form={form} labelCol={{ style: { width: labelW } }} wrapperCol={{ flex: 1 }}>
        <Row gutter={16}>
          <Col span={12}>
            <Form.Item label="项目名称" name="name" rules={[{ required: true, message: '请输入项目名称' }]}>
              <Input placeholder="如：XX 局政务云扩容项目" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item label="项目类型" name="type" rules={[{ required: true }]}>
              <Select
                options={[
                  { value: 'HW', label: '硬件项目' },
                  { value: 'SW', label: '软件项目' },
                ]}
              />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item label="项目编号" name="code">
              <Input placeholder="留空自动生成，如 YJ-2026-001" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item label="项目状态" name="status">
              <Select
                options={[
                  { value: 'RUN', label: '进行中' },
                  { value: 'DONE', label: '已完结' },
                  { value: 'PAUSE', label: '暂停' },
                  { value: 'STOP', label: '中止' },
                ]}
              />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item label="甲方单位" name="ownerUnit">
              <Select allowClear showSearch options={units.map((d) => ({ value: d.name, label: d.name }))} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item label="项目经理" name="managerUserId">
              <Select allowClear showSearch optionFilterProp="label" options={users.map((u) => ({ value: u.id, label: `${u.name}（${u.account}）` }))} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item label="参与人员" name="memberIds">
              <Select mode="multiple" allowClear options={users.map((u) => ({ value: u.id, label: u.name }))} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item label="供应商" name="vendorName">
              <Input placeholder="中标/合作厂商全称" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item label="供应商联系人" name="vendorContact">
              <Input placeholder="姓名/电话" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item label="批复文号" name="approveNo">
              <Input placeholder="如 X发改〔2025〕12 号" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item label="项目来源" name="projectSource">
              <Select allowClear options={sources.map((d) => ({ value: d.code, label: d.name }))} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item label="预算金额(元)" name="budgetAmount">
              <InputNumber style={{ width: '100%' }} min={0} precision={2} placeholder="概算/预算金额" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item label="资金来源" name="fundSource">
              <Select allowClear options={fundSources.map((d) => ({ value: d.code, label: d.name }))} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item label="招标方式" name="bidType">
              <Select allowClear options={bidTypes.map((d) => ({ value: d.code, label: d.name }))} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item label="中标金额(元)" name="bidAmount">
              <InputNumber style={{ width: '100%' }} min={0} precision={2} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item label="合同编号" name="contractNo">
              <Input />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item label="合同金额(元)" name="contractAmount">
              <InputNumber style={{ width: '100%' }} min={0} precision={2} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item label="变更金额(元)" name="changeAmount">
              <InputNumber style={{ width: '100%' }} precision={2} placeholder="增减金额，无则 0" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item label="立项日期" name="approveDate">
              <DatePicker style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item label="计划开始" name="planStartDate">
              <DatePicker style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item label="计划完成" name="planFinishDate">
              <DatePicker style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={24}>
            <Form.Item label="建设内容" name="contentSummary">
              <Input.TextArea rows={2} placeholder="一至两段文字说明" />
            </Form.Item>
          </Col>
          <Col span={24}>
            <Form.Item label="备注" name="remark">
              <Input.TextArea rows={1} />
            </Form.Item>
          </Col>
        </Row>
      </Form>
    </Modal>
  );
}
