import { api } from './http';
import {
  AttachmentItem,
  ContractItem,
  DictItem,
  LogItem,
  PageResult,
  PaymentItem,
  PhaseItem,
  ProjectDetail,
  ProjectForm,
  ProjectListItem,
  UserOption,
} from '@/types';

export interface ProjectQuery {
  page?: number;
  size?: number;
  keyword?: string;
  type?: string;
  status?: string;
  ownerUnit?: string;
  managerUserId?: number;
  year?: number;
  /** 查某父项目的子项目 */
  parentId?: number;
}

export const projectApi = {
  page(params: ProjectQuery): Promise<PageResult<ProjectListItem>> {
    return api.get<PageResult<ProjectListItem>>('/projects', params);
  },
  detail(id: number | string): Promise<ProjectDetail> {
    return api.get<ProjectDetail>(`/projects/${id}`);
  },
  create(data: ProjectForm): Promise<number> {
    return api.post<number>('/projects', data);
  },
  update(id: number | string, data: ProjectForm): Promise<void> {
    return api.put<void>(`/projects/${id}`, data);
  },
  remove(id: number | string): Promise<void> {
    return api.del<void>(`/projects/${id}`);
  },
  updatePhase(projectId: number | string, phaseId: number, data: Partial<PhaseItem>): Promise<void> {
    return api.put<void>(`/projects/${projectId}/phases/${phaseId}`, data);
  },
  logs(id: number | string): Promise<LogItem[]> {
    return api.get<LogItem[]>(`/projects/${id}/logs`);
  },
};

export const dictApi = {
  list(type: string): Promise<DictItem[]> {
    return api.get<DictItem[]>('/dicts', { type });
  },
};

export const userApi = {
  list(keyword?: string, role?: string): Promise<UserOption[]> {
    return api.get<UserOption[]>('/users', { keyword, role });
  },
};

export const paymentApi = {
  listByProject(projectId: number | string): Promise<PaymentItem[]> {
    return api.get<PaymentItem[]>(`/projects/${projectId}/payments`);
  },
  create(data: PaymentItem): Promise<number> {
    return api.post<number>('/payments', data);
  },
  update(id: number, data: PaymentItem): Promise<void> {
    return api.put<void>(`/payments/${id}`, data);
  },
  remove(id: number): Promise<void> {
    return api.del<void>(`/payments/${id}`);
  },
};

export const contractApi = {
  listByProject(projectId: number | string): Promise<ContractItem[]> {
    return api.get<ContractItem[]>(`/projects/${projectId}/contracts`);
  },
  get(id: number): Promise<ContractItem> {
    return api.get<ContractItem>(`/contracts/${id}`);
  },
  /** 合同覆盖的(子)项目 */
  covered(id: number): Promise<{ id: number; name: string; code: string }[]> {
    return api.get<{ id: number; name: string; code: string }[]>(`/contracts/${id}/projects`);
  },
  /** data.projectIds 为该合同覆盖的(子)项目 */
  create(data: Partial<ContractItem> & { projectIds?: number[] }): Promise<number> {
    return api.post<number>('/contracts', data);
  },
  update(id: number, data: Partial<ContractItem> & { projectIds?: number[] }): Promise<void> {
    return api.put<void>(`/contracts/${id}`, data);
  },
  remove(id: number): Promise<void> {
    return api.del<void>(`/contracts/${id}`);
  },
};

export const attachmentApi = {
  listByBiz(bizType: string, bizId: number): Promise<AttachmentItem[]> {
    return api.get<AttachmentItem[]>('/attachments', { bizType, bizId });
  },
  listByProject(projectId: number | string): Promise<AttachmentItem[]> {
    return api.get<AttachmentItem[]>(`/projects/${projectId}/attachments`);
  },
  upload(params: {
    projectId: number | string;
    bizType: string;
    bizId: number;
    attachType?: string;
    file: File;
  }): Promise<AttachmentItem> {
    const form = new FormData();
    form.append('file', params.file);
    form.append('projectId', String(params.projectId));
    form.append('bizType', params.bizType);
    form.append('bizId', String(params.bizId));
    if (params.attachType) {
      form.append('attachType', params.attachType);
    }
    return api.upload<AttachmentItem>('/attachments/upload', form);
  },
  remove(id: number): Promise<void> {
    return api.del<void>(`/attachments/${id}`);
  },
};

/** 附件访问 URL（带 token 的 query 形式，支持 <a>/<img>） */
export function attachmentUrl(id: number, disposition: 'download' | 'inline' = 'download'): string {
  const token = localStorage.getItem('pm_token') || '';
  return `/api/attachments/${id}/download?disposition=${disposition}&token=${encodeURIComponent(token)}`;
}
