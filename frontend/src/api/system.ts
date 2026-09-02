import { api } from './http';
import {
  AdminUser,
  DeptRow,
  DictRow,
  DictTypeInfo,
  LogRow,
  PhaseTemplateRow,
} from '@/types/system';
import { PageResult } from '@/types';

export const systemApi = {
  // ---------- 用户 ----------
  users(params: { page?: number; size?: number; keyword?: string; role?: string; status?: number }): Promise<PageResult<AdminUser>> {
    return api.get<PageResult<AdminUser>>('/admin/users', params);
  },
  createUser(data: { account: string; name: string; deptId?: number | null; role: string; password: string }): Promise<number> {
    return api.post<number>('/admin/users', data);
  },
  updateUser(id: number, data: { name: string; deptId?: number | null; role: string; status?: number }): Promise<void> {
    return api.put<void>(`/admin/users/${id}`, data);
  },
  resetPassword(id: number, password: string): Promise<void> {
    return api.put<void>(`/admin/users/${id}/password`, { password });
  },
  deleteUser(id: number): Promise<void> {
    return api.del<void>(`/admin/users/${id}`);
  },

  // ---------- 部门 ----------
  createDept(data: { parentId?: number; name: string; orderNo?: number }): Promise<number> {
    return api.post<number>('/depts', data);
  },
  updateDept(id: number, data: Partial<DeptRow>): Promise<void> {
    return api.put<void>(`/depts/${id}`, data);
  },
  deleteDept(id: number): Promise<void> {
    return api.del<void>(`/depts/${id}`);
  },

  // ---------- 字典 ----------
  dictTypes(): Promise<DictTypeInfo[]> {
    return api.get<DictTypeInfo[]>('/admin/dicts/types');
  },
  dictList(type: string): Promise<DictRow[]> {
    return api.get<DictRow[]>('/admin/dicts', { type });
  },
  createDict(data: Partial<DictRow>): Promise<number> {
    return api.post<number>('/admin/dicts', data);
  },
  updateDict(id: number, data: Partial<DictRow>): Promise<void> {
    return api.put<void>(`/admin/dicts/${id}`, data);
  },
  deleteDict(id: number): Promise<void> {
    return api.del<void>(`/admin/dicts/${id}`);
  },

  // ---------- 阶段模板 ----------
  createTemplate(data: Partial<PhaseTemplateRow>): Promise<number> {
    return api.post<number>('/phase-templates', data);
  },
  updateTemplate(id: number, data: Partial<PhaseTemplateRow>): Promise<void> {
    return api.put<void>(`/phase-templates/${id}`, data);
  },
  deleteTemplate(id: number): Promise<void> {
    return api.del<void>(`/phase-templates/${id}`);
  },

  // ---------- 操作日志 ----------
  logs(params: { page?: number; size?: number; userName?: string; bizType?: string; action?: string; keyword?: string }): Promise<PageResult<LogRow>> {
    return api.get<PageResult<LogRow>>('/admin/logs', params);
  },
};
