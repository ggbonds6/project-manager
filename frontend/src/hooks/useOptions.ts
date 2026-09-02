import { useEffect, useState } from 'react';
import { dictApi, userApi } from '@/api/project';
import { DictItem, UserOption } from '@/types';

const dictCache = new Map<string, Promise<DictItem[]>>();
const userPromise = userApi.list();

/** 字典选项（带模块级缓存） */
export function useDict(type: string): { options: DictItem[]; reload: () => void } {
  const [options, setOptions] = useState<DictItem[]>([]);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let cancelled = false;
    let p = dictCache.get(type);
    if (!p) {
      p = dictApi.list(type);
      dictCache.set(type, p);
    }
    p.then((list) => {
      if (!cancelled) setOptions(list);
    }).catch(() => {
      dictCache.delete(type);
    });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [type, tick]);

  return { options, reload: () => setTick((t) => t + 1) };
}

export function useUsers(): UserOption[] {
  const [users, setUsers] = useState<UserOption[]>([]);
  useEffect(() => {
    let cancelled = false;
    userPromise.then((list) => {
      if (!cancelled) setUsers(list);
    });
    return () => {
      cancelled = true;
    };
  }, []);
  return users;
}
