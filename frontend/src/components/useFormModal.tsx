import { ReactNode, useState } from 'react';
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
  ) => {
    setState({ title, fields, initial, onSave });
    form.resetFields();
  };

  const close = () => setState(null);

  const el = state ? (
    <Modal
      title={state.title}
      open
      width={520}
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
