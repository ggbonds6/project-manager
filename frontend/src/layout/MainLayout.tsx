import { useMemo } from 'react';
import { Layout, Menu, Dropdown, Avatar, Space, Typography } from 'antd';
import {
  DashboardOutlined,
  ProjectOutlined,
  BarChartOutlined,
  SettingOutlined,
  UserOutlined,
  LogoutOutlined,
} from '@ant-design/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '@/store/auth';

const { Header, Sider, Content } = Layout;

const MENU = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: '工作台' },
  { key: '/projects', icon: <ProjectOutlined />, label: '项目管理' },
  { key: '/stats', icon: <BarChartOutlined />, label: '项目统计' },
  { key: '/system', icon: <SettingOutlined />, label: '系统管理', adminOnly: true },
];

export default function MainLayout() {
  const { user, signOut } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const selectedKey = useMemo(() => {
    const seg = '/' + (location.pathname.split('/')[1] || '');
    return MENU.some((m) => m.key === seg) ? seg : '/projects';
  }, [location.pathname]);

  const items = MENU.filter((m) => !m.adminOnly || user?.role === 'ADMIN').map((m) => ({
    key: m.key,
    icon: m.icon,
    label: m.label,
  }));

  const userItems = [
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: '退出登录',
      onClick: () => {
        signOut();
        navigate('/login', { replace: true });
      },
    },
  ];

  return (
    <Layout style={{ height: '100vh' }}>
      <Sider collapsible breakpoint="lg" theme="dark">
        <div
          style={{
            height: 56,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: '#fff',
            fontWeight: 600,
            fontSize: 15,
            whiteSpace: 'nowrap',
            overflow: 'hidden',
          }}
        >
          项目管理系统
        </div>
        <Menu
          theme="dark"
          mode="inline"
          items={items}
          selectedKeys={[selectedKey]}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            background: '#fff',
            padding: '0 24px',
            display: 'flex',
            justifyContent: 'flex-end',
            alignItems: 'center',
            boxShadow: '0 1px 4px rgba(0,21,41,.08)',
          }}
        >
          <Dropdown menu={{ items: userItems }}>
            <Space style={{ cursor: 'pointer' }}>
              <Avatar size="small" icon={<UserOutlined />} style={{ background: '#2f5d8a' }} />
              <Typography.Text>{user?.name || '未登录'}</Typography.Text>
            </Space>
          </Dropdown>
        </Header>
        <Content style={{ padding: 16, overflow: 'auto' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
