import { api } from './http';
import {
  DashboardData,
  DeptRankRow,
  NameValue,
  StatsFilter,
  SummaryCards,
  YearMoneyRow,
} from '@/types/stats';

export const statsApi = {
  dashboard(): Promise<DashboardData> {
    return api.get<DashboardData>('/dashboard');
  },
  summary(filter?: StatsFilter): Promise<SummaryCards> {
    return api.get<SummaryCards>('/stats/summary', filter);
  },
  distributions(filter?: StatsFilter): Promise<{
    status: NameValue[];
    type: NameValue[];
    funnel: NameValue[];
    overdueProjects: number;
  }> {
    return api.get('/stats/distributions', filter);
  },
  yearMoney(filter?: StatsFilter): Promise<{ rows: YearMoneyRow[] }> {
    return api.get('/stats/year-money', filter);
  },
  deptRanking(filter?: StatsFilter): Promise<{ rows: DeptRankRow[] }> {
    return api.get('/stats/dept-ranking', filter);
  },
};
