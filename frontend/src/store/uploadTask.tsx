import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { attachmentApi } from '@/api/project';
import { UploadTaskItem } from '@/types';

export interface UploadStartParams {
  projectId: number | string;
  bizType: string;
  bizId: number;
  attachType?: string;
  file: File;
}

/** 正在「传输到服务器」阶段的状态（此时还没有任务 id，进度来自 axios onUploadProgress） */
export interface TransferState {
  name: string;
  pct: number;
}

interface UploadTaskState {
  /** 当前面板/轮询对应的项目 */
  projectId: number | string | null;
  setProjectId: (id: number | string | null) => void;
  tasks: UploadTaskItem[];
  transfer: TransferState | null;
  /** 进行中（PENDING/UPLOADING）任务数，用于按钮角标 */
  activeCount: number;
  startUpload: (p: UploadStartParams) => Promise<void>;
  refresh: () => Promise<void>;
}

const Ctx = createContext<UploadTaskState>({
  projectId: null,
  setProjectId: () => {},
  tasks: [],
  transfer: null,
  activeCount: 0,
  startUpload: async () => {},
  refresh: async () => {},
});

const ACTIVE = new Set(['PENDING', 'UPLOADING']);
const POLL_INTERVAL_MS = 1500;

/**
 * 全局上传任务状态。
 *
 * 目的：上传不再要求用户"一直开着上传弹窗"。
 *  - 文件提交后立刻登记到本 Context，由这里负责轮询进度；
 *  - 用户可关闭任何弹窗、切到别的页面，上传照常在后台跑完；
 *  - 任何地方都能通过 {@link useUploadTasks} 读到任务列表与进行中数量（角标）。
 *
 * 轮询只在"存在进行中任务"时进行，且随 {@link projectId} 切换重置。
 */
export function UploadTaskProvider({ children }: { children: ReactNode }) {
  const [projectId, setProjectId] = useState<number | string | null>(null);
  const [tasks, setTasks] = useState<UploadTaskItem[]>([]);
  const [transfer, setTransfer] = useState<TransferState | null>(null);
  const activeRef = useRef(false);

  const refresh = useCallback(async () => {
    if (projectId === null || projectId === undefined || projectId === '') {
      return;
    }
    try {
      const list = await attachmentApi.listUploadTasks(projectId, 50);
      setTasks(list);
      activeRef.current = list.some((t) => ACTIVE.has(t.status));
    } catch {
      /* 列表拉取失败不打断上传流程；下一轮轮询会重试 */
    }
  }, [projectId]);

  // 切换项目时立即刷新一次
  useEffect(() => {
    activeRef.current = false;
    void refresh();
  }, [refresh]);

  // 仅在存在进行中任务时轮询
  useEffect(() => {
    if (projectId === null || projectId === undefined || projectId === '') {
      return;
    }
    const timer = window.setInterval(() => {
      if (activeRef.current) {
        void refresh();
      }
    }, POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [projectId, refresh]);

  const startUpload = useCallback(
    async (p: UploadStartParams) => {
      setTransfer({ name: p.file.name, pct: 0 });
      try {
        const t = await attachmentApi.upload(p, (pct) => {
          setTransfer({ name: p.file.name, pct });
        });
        setTransfer(null);
        // 立刻并入列表（不等轮询），并标记有进行中任务以便开启轮询
        setTasks((prev) => [t, ...prev.filter((x) => x.id !== t.id)]);
        activeRef.current = true;
        void refresh();
      } catch (e) {
        setTransfer(null);
        throw e;
      }
    },
    [refresh],
  );

  const activeCount = tasks.filter((t) => ACTIVE.has(t.status)).length;

  return (
    <Ctx.Provider
      value={{ projectId, setProjectId, tasks, transfer, activeCount, startUpload, refresh }}
    >
      {children}
    </Ctx.Provider>
  );
}

export function useUploadTasks(): UploadTaskState {
  return useContext(Ctx);
}
