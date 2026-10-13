/**
 * 可见性感知的轮询（任务流视图专用）
 * ----------------------------------------------------------------
 * 为什么需要它（与参考项目 data-classification 的差异点）：
 * 参考项目是"打开弹窗就 1.5s 轮询"，**没管页面是否可见**；而本项目的解析流水线最长 30 min，
 * 用户常把 `/ai` 页挂在后台标签页里——不可见时还按 1.5s 打接口只是白耗后端与网关
 * （nginx/网关都有超时与连接数预算，见《AI前端与集成方案》§4.3）。
 * 因此这里统一：**页面隐藏即暂停、回到可见立即补一次并恢复**。
 *
 * 调用的三条纪律（任务书"轮询"一节）：
 *  ① 打开抽屉 → **先取一次全量**（`immediate=false` 时由调用方自己 await，避免双请求）；
 *  ② 终态/失败即停止 → 调用方把 `enabled` 置为 false（`isPipelineTerminal`）;
 *  ③ 连续失败要能停 → 由调用方在 `fn` 里自行计数并置 `enabled=false`（见 PipelineFlowDrawer）。
 */
import { useEffect, useRef } from 'react';

export interface VisiblePollingOptions {
  /** 轮询间隔（毫秒）。任务流列表 3s、详情 1.5s（§4.9.1 与参考项目一致） */
  intervalMs: number;
  /** 是否启用；终态、抽屉关闭、接口不可用时应置 false（置 false 会立刻清掉定时器） */
  enabled: boolean;
  /** 挂载/恢复可见时是否**立即**先跑一次（默认 false：调用方通常已经手动取过一次） */
  immediate?: boolean;
}

export function useVisiblePolling(fn: () => void | Promise<void>, opts: VisiblePollingOptions): void {
  // 用 ref 持有回调：调用方每次渲染都会新建闭包，直接进依赖数组会让轮询被反复重建
  const fnRef = useRef(fn);
  fnRef.current = fn;

  const { intervalMs, enabled, immediate = false } = opts;

  useEffect(() => {
    if (!enabled) return;

    let timer: number | null = null;
    const tick = () => {
      void fnRef.current();
    };
    const start = () => {
      if (timer !== null) return;
      timer = window.setInterval(tick, intervalMs);
    };
    const stop = () => {
      if (timer === null) return;
      window.clearInterval(timer);
      timer = null;
    };

    const onVisibilityChange = () => {
      if (document.hidden) {
        stop();
      } else {
        // 回到可见：先补一次（用户看到的就是最新状态），再恢复定时器
        tick();
        start();
      }
    };

    if (document.hidden) {
      // 打开时页面就在后台：不建立定时器，等 visibilitychange 再开始
    } else {
      if (immediate) tick();
      start();
    }
    document.addEventListener('visibilitychange', onVisibilityChange);

    return () => {
      stop();
      document.removeEventListener('visibilitychange', onVisibilityChange);
    };
  }, [intervalMs, enabled, immediate]);
}
