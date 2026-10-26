import axios, { AxiosError, AxiosResponse } from 'axios';
import type { InternalAxiosRequestConfig } from 'axios';
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

/** 统一注入 `Authorization`：普通实例与静默实例共用，避免两处漂移 */
function withAuth(config: InternalAxiosRequestConfig): InternalAxiosRequestConfig {
  const token = getToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
}

const http = axios.create({
  baseURL: '/api',
  timeout: 30000,
});

http.interceptors.request.use(withAuth);
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

/**
 * 静默实例：**失败不弹全局 toast**，只把错误抛给调用方。
 *
 * 为什么需要它：全局拦截器对失败一律 `message.error(...)`，这是给"用户主动点的操作"用的；
 * 但探测类请求（典型是附件可用性批量探测 `GET /api/attachments/availability`）失败时
 * 正确的行为是**静默降级**（不显示标签），而不是每次进附件列表都弹一句"请求失败"。
 * 单独一个实例比给每个调用点加开关更不容易被误用：要静默就得显式选它。
 */
const silentHttp = axios.create({
  baseURL: '/api',
  timeout: 30000,
});

silentHttp.interceptors.request.use(withAuth);
silentHttp.interceptors.response.use(
  ((resp: AxiosResponse) => {
    const body = resp.data as R<unknown>;
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) {
        return body.data;
      }
      // 401 仍要回登录页：静默的是"提示"，不是"会话失效"这件事
      if (body.code === 401) {
        gotoLogin();
      }
      return Promise.reject(new Error(body.message || '请求失败'));
    }
    return body;
  }) as unknown as (resp: AxiosResponse) => AxiosResponse | Promise<AxiosResponse>,
  (err: AxiosError<{ message?: string }>) => {
    if (err.response?.status === 401) {
      gotoLogin();
    }
    return Promise.reject(new Error(err.response?.data?.message || err.message || '网络错误'));
  },
);

export const api = {
  /**
   * `opts.timeoutMs`：个别接口（如深度自检要真去 ping 平台模型，几秒到十几秒）需要比
   * 全局 30s 更宽的超时。不传就沿用全局默认，不影响其它调用方。
   *
   * `opts.silent`：`true` 时失败**不弹全局 toast**（走 {@link silentHttp}），
   * 由调用方自己决定怎么降级——只给探测类请求用，别拿它掩盖用户主动操作的失败。
   */
  get<T>(url: string, params?: object, opts?: { timeoutMs?: number; silent?: boolean }): Promise<T> {
    const client = opts?.silent ? silentHttp : http;
    return client.get(url, {
      params,
      ...(opts?.timeoutMs ? { timeout: opts.timeoutMs } : {}),
    }) as Promise<T>;
  },
  /**
   * `opts.timeoutMs`：与 `get` 同一语义——个别接口需要比全局 30s 更宽的超时
   * （典型是 `POST /api/ai/chat`：一次问答包含多轮工具调用，实测 10s 起、复杂问题更久，
   * 全局 30s 会在中途把请求掐断，用户看到的是"AI 服务调用失败"）。
   * 不传就沿用全局默认，不影响其它调用方。
   */
  post<T>(url: string, data?: object, opts?: { timeoutMs?: number }): Promise<T> {
    return http.post(url, data, {
      ...(opts?.timeoutMs ? { timeout: opts.timeoutMs } : {}),
    }) as Promise<T>;
  },
  put<T>(url: string, data?: object): Promise<T> {
    return http.put(url, data) as Promise<T>;
  },
  del<T>(url: string, params?: object): Promise<T> {
    return http.delete(url, { params }) as Promise<T>;
  },
  /**
   * 上传文件。
   *
   * 注意：全局 axios timeout 是 30s，对大文件（几百 MB 级，上限见后端 max-file-size）远远不够——传输本身就可能超过 30s。
   * 故这里默认放宽到 10 分钟，并支持 onProgress 上报真实传输进度。
   * （服务端已改为「先暂存 + 立即返回任务」，真正写对象存储发生在后台，不占用本请求时长。）
   */
  upload<T>(
    url: string,
    formData: FormData,
    opts?: { onProgress?: (percent: number) => void; timeoutMs?: number },
  ): Promise<T> {
    return http.post(url, formData, {
      timeout: opts?.timeoutMs ?? 600000,
      onUploadProgress: (e) => {
        if (opts?.onProgress && e.total) {
          opts.onProgress(Math.min(99, Math.round((e.loaded * 100) / e.total)));
        }
      },
    }) as Promise<T>;
  },
};
