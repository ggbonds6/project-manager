import axios, { AxiosError, AxiosResponse } from 'axios';
import { message } from 'antd';
import { R } from '@/types';

export const TOKEN_KEY = 'pm_token';
export const USER_KEY = 'pm_user';

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

function gotoLogin() {
  clearAuth();
  if (window.location.pathname !== '/login') {
    window.location.href = '/login';
  }
}

const http = axios.create({
  baseURL: '/api',
  timeout: 30000,
});

http.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

http.interceptors.response.use(
  ((resp: AxiosResponse) => {
    const body = resp.data as R<unknown>;
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) {
        return body.data;
      }
      if (body.code === 401) {
        message.error('登录已过期，请重新登录');
        gotoLogin();
      } else {
        message.error(body.message || '请求失败');
      }
      return Promise.reject(new Error(body.message || '请求失败'));
    }
    return body;
  }) as unknown as (resp: AxiosResponse) => AxiosResponse | Promise<AxiosResponse>,
  (err: AxiosError<{ message?: string }>) => {
    const status = err.response?.status;
    const msg = err.response?.data?.message || err.message || '网络错误';
    if (status === 401) {
      message.error('登录已过期，请重新登录');
      gotoLogin();
    } else {
      message.error(msg);
    }
    return Promise.reject(new Error(msg));
  },
);

export const api = {
  get<T>(url: string, params?: object): Promise<T> {
    return http.get(url, { params }) as Promise<T>;
  },
  post<T>(url: string, data?: object): Promise<T> {
    return http.post(url, data) as Promise<T>;
  },
  put<T>(url: string, data?: object): Promise<T> {
    return http.put(url, data) as Promise<T>;
  },
  del<T>(url: string, params?: object): Promise<T> {
    return http.delete(url, { params }) as Promise<T>;
  },
  upload<T>(url: string, formData: FormData): Promise<T> {
    return http.post(url, formData) as Promise<T>;
  },
};
