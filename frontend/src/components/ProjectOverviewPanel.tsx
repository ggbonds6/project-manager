import { useEffect, useState } from 'react';
import { Button, Card, Empty, Form, Input, Modal, Space, Typography, message } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import ReactMarkdown from 'react-markdown';
import { projectApi } from '@/api/project';
import { OverviewData, OverviewModule } from '@/types';

interface Props {
  projectId: number;
  overview?: OverviewData | null;
  canEdit: boolean;
  onSaved: () => void;
}

const mdStyle: React.CSSProperties = {
  background: '#fafafa',
  borderRadius: 6,
  padding: '10px 18px',
  maxHeight: 420,
  overflow: 'auto',
  lineHeight: 1.75,
  fontSize: 13,
};

/**
 * 项目信息-概览：README 式介绍（Markdown） + 二级功能模块清单。
 * 展示给所有角色；编辑仅 ADMIN/MANAGER。
 */
export default function ProjectOverviewPanel({ projectId, overview, canEdit, onSaved }: Props) {
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();

  const introMd = overview?.introMd;
  const modules = overview?.modules || [];

  const openEdit = () => {
    form.setFieldsValue({
      introMd: introMd || '',
      modules: modules.map((m) => ({
        name: m.name || '',
        description: m.description || '',
        children: (m.children || []).map((c) => ({ name: c.name || '', description: c.description || '' })),
      })),
    });
    setOpen(true);
  };

  const submit = () => {
    form.validateFields().then(async (values) => {
      setSaving(true);
      try {
        const clean = (list?: OverviewModule[]): OverviewModule[] =>
          (list || [])
            .filter((m) => m.name && m.name.trim())
            .map((m) => ({
              name: m.name.trim(),
              description: m.description?.trim() || undefined,
              children: (m.children || []).filter((c) => c.name && c.name.trim()).map((c) => ({
                name: c.name.trim(),
                description: c.description?.trim() || undefined,
              })),
            }));
        await projectApi.updateOverview(projectId, {
          introMd: values.introMd?.trim() || null,
          modules: clean(values.modules),
        });
        message.success('项目概览已保存');
        setOpen(false);
        onSaved();
      } finally {
        setSaving(false);
      }
    });
  };

  const moduleEditor = (
    <Form.List name="modules">
      {(fields, { add: addModule, remove: removeModule }) => (
        <div>
          {fields.map((field) => (
            <div
              key={field.key}
              style={{
                border: '1px solid #f0f0f0',
                borderRadius: 8,
                padding: 10,
                marginBottom: 10,
                background: '#fafafa',
              }}
            >
              <Space style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
                <Typography.Text strong>一级模块</Typography.Text>
                <Button size="small" danger type="text" icon={<DeleteOutlined />} onClick={() => removeModule(field.name)}>
                  删除模块
                </Button>
              </Space>
              <Space direction="vertical" style={{ width: '100%' }} size={8}>
                <Form.Item
                  name={[field.name, 'name']}
                  rules={[{ required: true, message: '请填写模块名称' }]}
                  style={{ marginBottom: 0 }}
                >
                  <Input placeholder="模块名称，如：项目管理" />
                </Form.Item>
                <Form.Item name={[field.name, 'description']} style={{ marginBottom: 0 }}>
                  <Input placeholder="模块说明（可空）" />
                </Form.Item>
                <Form.List name={[field.name, 'children']}>
                  {(subFields, { add: addSub, remove: removeSub }) => (
                    <>
                      {subFields.map((sf) => (
                        <div key={sf.key} style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                          <span style={{ color: '#1677ff', fontWeight: 600 }}>└</span>
                          <Form.Item name={[sf.name, 'name']} style={{ marginBottom: 0, flex: 1 }}>
                            <Input placeholder="子模块名称" />
                          </Form.Item>
                          <Form.Item name={[sf.name, 'description']} style={{ marginBottom: 0, flex: 1 }}>
                            <Input placeholder="子模块说明（可空）" />
                          </Form.Item>
                          <Button size="small" type="text" danger icon={<DeleteOutlined />} onClick={() => removeSub(sf.name)} />
                        </div>
                      ))}
                      <Button size="small" type="dashed" icon={<PlusOutlined />} onClick={() => addSub()}>
                        添加子模块
                      </Button>
                    </>
                  )}
                </Form.List>
              </Space>
            </div>
          ))}
          <Button type="dashed" block icon={<PlusOutlined />} onClick={() => addModule()}>
            添加一级模块
          </Button>
        </div>
      )}
    </Form.List>
  );

  return (
    <div style={{ marginTop: 14 }}>
      <Card
        size="small"
        title="项目介绍"
        extra={
          canEdit ? (
            <Button size="small" icon={<EditOutlined />} onClick={openEdit}>
              编辑项目介绍 / 功能模块
            </Button>
          ) : null
        }
      >
        {introMd ? (
          <div style={mdStyle} className="overview-md">
            <ReactMarkdown>{introMd}</ReactMarkdown>
          </div>
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无项目介绍" />
        )}
      </Card>

      <Card size="small" title={`功能模块清单（${modules.length}）`} style={{ marginTop: 12 }}>
        {modules.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无功能模块清单" />
        ) : (
          modules.map((m, idx) => (
            <div
              key={idx}
              style={{
                border: '1px solid #f0f0f0',
                borderRadius: 8,
                padding: '8px 12px',
                marginBottom: 10,
              }}
            >
              <Space size={8} align="baseline">
                <b style={{ fontSize: 14 }}>{m.name}</b>
                {m.description ? <Typography.Text type="secondary">{m.description}</Typography.Text> : null}
              </Space>
              {(m.children || []).length > 0 && (
                <div style={{ marginTop: 6, paddingLeft: 8 }}>
                  {(m.children || []).map((c, ci) => (
                    <div key={ci} style={{ fontSize: 13, color: '#4e5969', padding: '2px 0' }}>
                      <span style={{ color: '#1677ff', marginRight: 6 }}>└</span>
                      {c.name}
                      {c.description ? (
                        <Typography.Text type="secondary" style={{ marginLeft: 8 }}>
                          {c.description}
                        </Typography.Text>
                      ) : null}
                    </div>
                  ))}
                </div>
              )}
            </div>
          ))
        )}
      </Card>

      <Modal
        title="编辑项目介绍与功能模块"
        open={open}
        width={780}
        onCancel={() => setOpen(false)}
        okText="保存"
        cancelText="取消"
        confirmLoading={saving}
        onOk={submit}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 8 }}>
          <Form.Item
            label="项目介绍（支持 Markdown：项目背景 / 用途 / 功能概览 / 应用场景）"
            name="introMd"
          >
            <Input.TextArea rows={8} placeholder={'## 项目背景\n...\n\n## 主要功能\n...\n\n## 应用场景\n...'} />
          </Form.Item>
          <Form.Item label="功能模块清单（一级模块 → 子模块）" style={{ marginBottom: 0 }}>
            {moduleEditor}
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
