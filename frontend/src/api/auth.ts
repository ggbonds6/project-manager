import { api } from './http';
import { CurrentUser, LoginParams, LoginResult } from '@/types';

export const authApi = {
  login(params: LoginParams): Promise<LoginResult> {
    return api.post<LoginResult>('/auth/login', params);
  },
  me(): Promise<CurrentUser> {
    return api.get<CurrentUser>('/auth/me');
  },
};
