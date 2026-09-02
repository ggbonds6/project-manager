import { useState } from 'react';
import { Button, Card, Form, Input, message, Typography } from 'antd';
import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { Navigate, useNavigate } from 'react-router-dom';
import { useAuth } from '@/store/auth';
import { LoginParams } from '@/types';

export default function LoginPage() {
  const { user, ready, signIn } = useAuth();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);

  if (ready && user) {
    return <Navigate to="/projects" replace />;
  }

  const onFinish = async (values: LoginParams) => {
    setLoading(true);
    try {
      await signIn(values);
      message.success('登录成功');
      navigate('/projects', { replace: true });
    } catch {
      /* 错误提示已在拦截器处理 */
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      style={{
        height: '100%',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'linear-gradient(135deg,#1f3a5f 0%,#2f5d8a 100%)',
      }}
    >
      <Card style={{ width: 380, boxShadow: '0 8px 30px rgba(0,0,0,.2)' }}>
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <Typography.Title level={3} style={{ marginBottom: 4 }}>
            政府信息化项目管理系统
          </Typography.Title>
          <Typography.Text type="secondary">内部管理系统</Typography.Text>
        </div>
        <Form<LoginParams> onFinish={onFinish} size="large">
          <Form.Item name="account" rules={[{ required: true, message: '请输入账号' }]}>
            <Input prefix={<UserOutlined />} placeholder="账号" autoComplete="username" />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password
              prefix={<LockOutlined />}
              placeholder="密码"
              autoComplete="current-password"
            />
          </Form.Item>
          <Form.Item style={{ marginBottom: 8 }}>
            <Button type="primary" htmlType="submit" block loading={loading}>
              登 录
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
}
