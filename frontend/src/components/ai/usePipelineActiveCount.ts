/**
 * 「进行中」流水线数量（`/ai` 页「流水线」签的角标）
 * ----------------------------------------------------------------
 * 借鉴 `data-classification/src/App.tsx`（**只读参考、未改它**）：
 * 顶栏一个常驻入口 + `Badge` 显示进行中数量，独立 **5s 轮询只取计数**（开销极小）。
 *
 * 本项目的落地位置是 `/ai` 页的页签标签：顶栏属于共享布局 `MainLayout`（全局布局不是 AI 页面），
 * 往那里塞 AI 专用入口会越过"只改 AI 模块"的边界（§4.9.2① 的落地形态本身就是"加计数角标"）。
 *
 * 两条自我约束：
 *  ① 页面不可见时暂停（`useVisiblePolling`），避免后台标签页白打接口；
 *  ② **失败熔断**：接口还没部署/权限不足时，错误提示由列表页统一给出，角标这里只统计一次失败就停下，
 *     不让"每 5 秒弹一次报错"成为新的噪音源。
 */
import { useCallback, useState } from 'react';
import { aiApi } from '@/api/ai';
import { AI_PIPELINE_TASK_ACTIVE_QUERY } from '@/types/ai';
import { useVisiblePolling } from '@/components/ai/useVisiblePolling';

/** 角标轮询间隔（与参考项目 App.tsx 的 5s 一致） */
const BADGE_POLL_MS = 5000;

/** 计数用的取数窗口：后端分页时以 `total` 为准，返回裸数组时就数 records（≤50 条足够表达"有几个在跑"） */
const COUNT_WINDOW = 50;

export function usePipelineActiveCount(enabled = true): number {
  const [count, setCount] = useState(0);
  /** 一次失败即熔断（下次挂载/刷新页面会重试），避免后端未就绪时反复报错 */
  const [degraded, setDegraded] = useState(false);

  const tick = useCallback(async () => {
    try {
      /*
       * **一次请求问完"进行中"**：任务级"进行中" = QUEUED + RUNNING 两个状态，
       * 后端 `GET /api/ai/runs?status=` 支持逗号分隔多值。
       *
       * 以前这里是两次请求，其中一次发的是 **阶段级** 的 `PENDING`（"排队中"）——
       * 那是 `ai_pipeline_stage.status` 的值，任务级没有它，真机后端直接 400：
       * `status 非法：PENDING；合法值：QUEUED/RUNNING/…`（前端此前从未连真后端跑过，
       * 构建与类型检查都发现不了）。现在类型上已把任务级/阶段级拆成两套枚举，见 `types/ai.ts`。
       */
      const res = await aiApi.pipelineRuns({
        status: AI_PIPELINE_TASK_ACTIVE_QUERY,
        page: 1,
        size: COUNT_WINDOW,
      });
      setCount(Math.max(res.total || 0, res.records.length));
    } catch {
      setDegraded(true);
    }
  }, []);

  useVisiblePolling(tick, {
    intervalMs: BADGE_POLL_MS,
    enabled: enabled && !degraded,
    immediate: true,
  });

  return count;
}
