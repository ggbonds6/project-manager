import { Navigate, Route, Routes } from 'react-router-dom';
import LoginPage from '@/pages/LoginPage';
import MainLayout from '@/layout/MainLayout';
import DashboardPage from '@/pages/DashboardPage';
import ProjectsPage from '@/pages/projects/ProjectsPage';
import StatsPage from '@/pages/StatsPage';
import SystemPage from '@/pages/SystemPage';
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
        <Route index element={<Navigate to="/projects" replace />} />
        <Route path="dashboard" element={<DashboardPage />} />
        <Route path="projects" element={<ProjectsPage />} />
        <Route path="stats" element={<StatsPage />} />
        <Route path="system" element={<SystemPage />} />
        <Route path="*" element={<Navigate to="/projects" replace />} />
      </Route>
    </Routes>
  );
}
