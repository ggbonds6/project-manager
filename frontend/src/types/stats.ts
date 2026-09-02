export interface StatsFilter {
  year?: number;
  type?: string;
  status?: string;
  ownerUnit?: string;
}

export interface SummaryCards {
  projectTotal: number;
  running: number;
  done: number;
  pause: number;
  stop: number;
  newThisYear: number;
  overdueCount: number;
  budgetTotal: number;
  contractTotal: number;
  paidTotal: number;
  execRate: number;
}

export interface NameValue {
  name: string;
  value: number;
}

export interface YearMoneyRow {
  year: number;
  budget: number;
  contract: number;
  paid: number;
}

export interface DeptRankRow {
  name: string;
  count: number;
  contract: number;
}

export interface DashboardData {
  cards: SummaryCards;
  myTodos: {
    projectId: number;
    projectName: string;
    phaseName: string;
    percent: number;
    planFinishDate?: string | null;
    overdue: boolean;
  }[];
  upcoming: {
    projectId: number;
    projectName: string;
    phaseName: string;
    planDate?: string | null;
    status: string;
  }[];
  overdue: {
    projectId: number;
    projectName: string;
    phaseName: string;
    planDate?: string | null;
    days: number;
  }[];
  recent: {
    id: number;
    code: string;
    name: string;
    status: string;
    currentPhaseName?: string | null;
    updateTime?: string | null;
  }[];
}
