/**
 * 「AI 与知识库」用的项目选项
 * ----------------------------------------------------------------
 * 悬浮问答的范围选择器（全部 / 某项目）与 `/ai` 文档库/任务队列的项目筛选都要这一份列表，
 * 三处各拉一次太浪费、还容易出现「范围选择器里没有某项目」的不一致，故在此共用并做模块级缓存
 * （与 `hooks/useOptions.ts` 的字典缓存同一思路）。
 */
import { useEffect, useState } from 'react';
import { projectApi } from '@/api/project';
import { ProjectListItem } from '@/types';

/** 一次性拉取的容量：项目管理系统为百级项目，500 足够覆盖范围选择场景 */
const PAGE_SIZE = 500;

let cache: Promise<ProjectListItem[]> | null = null;

function load(): Promise<ProjectListItem[]> {
  if (!cache) {
    cache = projectApi
      .page({ page: 1, size: PAGE_SIZE })
      .then((res) => res.records)
      .catch((e) => {
        // 失败后清缓存，下次进入还能重试（否则一次网络抖动会让选择器永久为空）
        cache = null;
        throw e;
      });
  }
  return cache;
}

export function useAiProjects(): { projects: ProjectListItem[]; loading: boolean } {
  const [projects, setProjects] = useState<ProjectListItem[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    load()
      .then((list) => {
        if (!cancelled) setProjects(list);
      })
      .catch(() => {
        /* 拦截器已提示；选择器退化为只有「全部」 */
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return { projects, loading };
}
