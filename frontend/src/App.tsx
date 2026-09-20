import { Navigate, Route, Routes } from 'react-router-dom';
import LoginPage from '@/pages/LoginPage';
import MainLayout from '@/layout/MainLayout';
import DashboardPage from '@/pages/DashboardPage';
import ProjectsPage from '@/pages/projects/ProjectsPage';
import ProjectDetailPage from '@/pages/projects/ProjectDetailPage';
import StatsPage from '@/pages/StatsPage';
import SystemPage from '@/pages/SystemPage';
import AiPage from '@/pages/ai/AiPage';
import RequireAuth from '@/components/RequireAuth';

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/"
        element={
          <RequireAuth>
            <MainLayout />
          </RequireAuth>
        }
      >
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="dashboard" element={<DashboardPage />} />
        <Route path="projects" element={<ProjectsPage />} />
        <Route path="projects/:id" element={<ProjectDetailPage />} />
        <Route path="stats" element={<StatsPage />} />
        {/* AI 与知识库（P0）：菜单只对 ADMIN/MANAGER 显示，页面内部另有角色判定 */}
        <Route path="ai" element={<AiPage />} />
        <Route path="system" element={<SystemPage />} />
        <Route path="*" element={<Navigate to="/projects" replace />} />
      </Route>
    </Routes>
  );
}
