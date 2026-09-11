import { ReactNode, useEffect, useState } from 'react';
import { Form, Modal } from 'antd';
import type { Rule } from 'antd/es/form';

export interface FieldDef {
  name: string;
  label: string;
  el: ReactNode;
  rules?: Rule[];
}

interface ModalState {
  title: string;
  fields: FieldDef[];
  initial: Record<string, unknown>;
  onSave: (values: Record<string, never>) => Promise<void> | void;
  /** 弹窗宽度（默认 560） */
  width?: number;
}

/**
 * 通用“表单弹窗”hook：每个使用它的组件内部挂载一个 Modal + Form。
 */
export function useFormModal() {
  const [state, setState] = useState<ModalState | null>(null);
  const [form] = Form.useForm();
  const [saving, setSaving] = useState(false);

  const open = (
    title: string,
    fields: FieldDef[],
    initial: Record<string, unknown>,
    onSave: ModalState['onSave'],
    width?: number,
  ) => {
    setState({ title, fields, initial, onSave, width });
  };

  /**
   * 回显关键：必须等 state 更新、Form 用新的 initialValues 渲染之后再重置，
   * 否则会重置成上一次的值（表现为"编辑时总显示第一次点开的数据"）。
   *
   * 原因：Form 实例在本 hook 中创建，跨多次打开是同一个 store；
   * 而 rc-field-form 在 initialValues 变化时只更新内部记录，**不会自动填充已有字段**，
   * 因此需要在渲染后手动 reset（清掉上次残留）+ setFieldsValue（填入本次值）。
   */
  useEffect(() => {
    if (!state) {
      return;
    }
    form.resetFields();
    form.setFieldsValue(state.initial);
  }, [state, form]);

  const close = () => setState(null);

  const el = state ? (
    <Modal
      title={state.title}
      open
      width={state.width ?? 560}
      okText="保存"
      cancelText="取消"
      confirmLoading={saving}
      onCancel={close}
      onOk={() =>
        form
          .validateFields()
          .then(async (values) => {
            setSaving(true);
            try {
              await state.onSave(values as Record<string, never>);
              close();
            } finally {
              setSaving(false);
            }
          })
          .catch(() => undefined)
      }
      destroyOnClose
    >
      <Form
        form={form}
        labelCol={{ span: 6 }}
        wrapperCol={{ span: 17 }}
        style={{ marginTop: 12 }}
        initialValues={state.initial}
      >
        {state.fields.map((f) => (
          <Form.Item key={f.name} name={f.name} label={f.label} rules={f.rules}>
            {f.el}
          </Form.Item>
        ))}
      </Form>
    </Modal>
  ) : null;

  return { open, el };
}
