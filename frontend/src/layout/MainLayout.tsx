import { useMemo } from 'react';
import { Layout, Menu, Dropdown, Avatar, Space, Typography } from 'antd';
import {
  DashboardOutlined,
  ProjectOutlined,
  BarChartOutlined,
  RobotOutlined,
  SettingOutlined,
  UserOutlined,
  LogoutOutlined,
} from '@ant-design/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '@/store/auth';
import AiChatDrawer, { canUseAi } from '@/components/ai/AiChatDrawer';
import { CurrentUser } from '@/types';

const { Header, Sider, Content } = Layout;

/**
 * 菜单项。
 *
 * `visibleFor` 取代了早期的 `adminOnly` 布尔位：AI 与知识库要"管理员 + 项目经理"可见，
 * 用谓词表达比继续加布尔字段更耐用（下一个需要第三种可见性的菜单不必再改过滤逻辑）。
 * 前端隐藏只是顺手，真正的拦截在主系统后端。
 */
interface MenuItem {
  key: string;
  icon: JSX.Element;
  label: string;
  visibleFor?: (user: CurrentUser | null) => boolean;
}

const MENU: MenuItem[] = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: '工作台' },
  { key: '/projects', icon: <ProjectOutlined />, label: '项目管理' },
  { key: '/stats', icon: <BarChartOutlined />, label: '项目统计' },
  {
    key: '/ai',
    icon: <RobotOutlined />,
    label: 'AI 与知识库',
    // 与悬浮问答入口同一判定（canUseAi），避免"有菜单没图标"这种不一致
    visibleFor: (u) => canUseAi(u?.role),
  },
  { key: '/system', icon: <SettingOutlined />, label: '系统管理', visibleFor: (u) => u?.role === 'ADMIN' },
];

export default function MainLayout() {
  const { user, signOut } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const selectedKey = useMemo(() => {
    const seg = '/' + (location.pathname.split('/')[1] || '');
    return MENU.some((m) => m.key === seg) ? seg : '/projects';
  }, [location.pathname]);

  const items = MENU.filter((m) => !m.visibleFor || m.visibleFor(user)).map((m) => ({
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

      {/* 全局悬浮 AI 问答：挂在布局层，任何页面右下角可用（不打断当前操作） */}
      {canUseAi(user?.role) ? <AiChatDrawer /> : null}
    </Layout>
  );
}
