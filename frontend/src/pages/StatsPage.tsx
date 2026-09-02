import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button, Card, Col, Row, Select, Space, Spin, Statistic, Empty, Tag, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ReloadOutlined } from '@ant-design/icons';
import type { EChartsOption } from 'echarts';
import EChart from '@/components/EChart';
import { statsApi } from '@/api/stats';
import { useDict } from '@/hooks/useOptions';
import { fmtWan } from '@/utils/format';
import { NameValue, StatsFilter } from '@/types/stats';
import { PROJECT_STATUS, PROJECT_TYPES } from '@/types';

const YEARS = [2024, 2025, 2026];
const PALETTE = ['#2f5d8a', '#5b8ff9', '#61daa9', '#f6bd16', '#7262fd', '#78d3f8', '#9661bc', '#f6903d', '#008685', '#f08bb4'];

const STATUS_TEXT: Record<string, string> = Object.fromEntries(
  Object.entries(PROJECT_STATUS).map(([k, v]) => [k, v.text]),
);
const STATUS_COLOR: Record<string, string> = {
  RUN: '#1677ff',
  DONE: '#52c41a',
  PAUSE: '#faad14',
  STOP: '#ff4d4f',
};

export default function StatsPage() {
  const { options: units } = useDict('OWNER_UNIT');
  const [filter, setFilter] = useState<StatsFilter>({});
  const [loading, setLoading] = useState(false);

  const [summary, setSummary] = useState<Record<string, number> | null>(null);
  const [dist, setDist] = useState<{ status: NameValue[]; type: NameValue[]; funnel: NameValue[] } | null>(null);
  const [yearMoney, setYearMoney] = useState<{ year: number; budget: number; contract: number; paid: number }[]>([]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [s, d, ym] = await Promise.all([
        statsApi.summary(filter),
        statsApi.distributions(filter),
        statsApi.yearMoney(filter),
      ]);
      setSummary(s as unknown as Record<string, number>);
      setDist(d);
      setYearMoney(ym.rows);
    } finally {
      setLoading(false);
    }
  }, [filter]);

  useEffect(() => {
    load();
  }, [load]);

  const hasAnyFilter = Object.values(filter).some((v) => v !== undefined && v !== '');

  // ---------- 图表配置 ----------
  const pieStatus: EChartsOption = useMemo(
    () => ({
      tooltip: { trigger: 'item' },
      legend: { bottom: 0 },
      color: (dist?.status || []).map((d) => STATUS_COLOR[d.name] || PALETTE[0]),
      series: [
        {
          name: '项目状态',
          type: 'pie',
          radius: ['42%', '68%'],
          label: { formatter: '{b}\n{c} 个' },
          data: dist?.status || [],
        },
      ],
    }),
    [dist],
  );

  const pieType: EChartsOption = useMemo(
    () => ({
      tooltip: { trigger: 'item' },
      legend: { bottom: 0 },
      color: ['#2f5d8a', '#9661bc'],
      series: [
        {
          name: '项目类型',
          type: 'pie',
          radius: ['42%', '68%'],
          label: { formatter: '{b}: {d}%' },
          data: (dist?.type || []).map((d) => ({ name: PROJECT_TYPES[d.name] || d.name, value: d.value })),
        },
      ],
    }),
    [dist],
  );

  const funnelOption: EChartsOption = useMemo(
    () => ({
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      grid: { left: 8, right: 50, top: 20, bottom: 8, containLabel: true },
      xAxis: { type: 'value', minInterval: 1 },
      yAxis: { type: 'category', inverse: true, data: (dist?.funnel || []).map((d) => d.name) },
      series: [
        {
          name: '项目数',
          type: 'bar',
          barWidth: 16,
          itemStyle: { color: '#5b8ff9', borderRadius: [0, 4, 4, 0] },
          label: { show: true, position: 'right' },
          data: (dist?.funnel || []).map((d) => d.value),
        },
      ],
    }),
    [dist],
  );

  const yearOption: EChartsOption = useMemo(() => {
    const w = (v: number) => Number((v / 10000).toFixed(2));
    return {
      tooltip: { trigger: 'axis' },
      legend: { bottom: 0 },
      grid: { left: 8, right: 16, top: 30, bottom: 30, containLabel: true },
      xAxis: { type: 'category', data: yearMoney.map((r) => `${r.year}`) },
      yAxis: { type: 'value', name: '万元', nameTextStyle: { align: 'right' } },
      series: [
        { name: '预算', type: 'bar', data: yearMoney.map((r) => w(r.budget)), itemStyle: { color: '#91caff' } },
        { name: '合同', type: 'bar', data: yearMoney.map((r) => w(r.contract)), itemStyle: { color: '#2f5d8a' } },
        { name: '实付', type: 'bar', data: yearMoney.map((r) => w(r.paid)), itemStyle: { color: '#f6bd16' } },
      ],
    };
  }, [yearMoney]);

  const moneyCards = [
    { t: '项目总数', v: summary?.projectTotal ?? 0 },
    { t: '进行中', v: summary?.running ?? 0 },
    { t: '本年度新建', v: summary?.newThisYear ?? 0 },
    { t: '累计合同(万)', v: fmtWan(summary?.contractTotal) },
    { t: '累计实付(万)', v: fmtWan(summary?.paidTotal) },
    { t: '资金执行率', v: `${summary?.execRate ?? 0}%` },
  ];

  return (
    <div>
      <Card size="small" style={{ marginBottom: 12 }}>
        <Space wrap>
          <Select
            placeholder="年度"
            allowClear
            style={{ width: 110 }}
            value={filter.year}
            onChange={(v) => setFilter((f) => ({ ...f, year: v }))}
            options={YEARS.map((y) => ({ value: y, label: `${y} 年` }))}
          />
          <Select
            placeholder="类型"
            allowClear
            style={{ width: 120 }}
            value={filter.type}
            onChange={(v) => setFilter((f) => ({ ...f, type: v }))}
            options={Object.entries(PROJECT_TYPES).map(([value, label]) => ({ value, label }))}
          />
          <Select
            placeholder="状态"
            allowClear
            style={{ width: 120 }}
            value={filter.status}
            onChange={(v) => setFilter((f) => ({ ...f, status: v }))}
            options={Object.entries(STATUS_TEXT).map(([value, label]) => ({ value, label }))}
          />
          <Select
            placeholder="甲方单位"
            allowClear
            showSearch
            style={{ width: 180 }}
            value={filter.ownerUnit}
            onChange={(v) => setFilter((f) => ({ ...f, ownerUnit: v }))}
            options={units.map((d) => ({ value: d.name, label: d.name }))}
          />
          {hasAnyFilter && (
            <Button
              onClick={() => {
                setFilter({});
                setLoading(true);
              }}
            >
              重置
            </Button>
          )}
          <Button icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
        </Space>
      </Card>

      <Spin spinning={loading}>
        <Row gutter={[12, 12]}>
          {moneyCards.map((mc) => (
            <Col key={mc.t} xs={8} md={4}>
              <Card size="small">
                <Statistic title={mc.t} value={mc.v} valueStyle={{ fontSize: 20 }} />
              </Card>
            </Col>
          ))}
        </Row>

        <Row gutter={[12, 12]} style={{ marginTop: 12 }}>
          <Col xs={24} md={8}>
            <Card size="small" title="项目状态构成">
              {(dist?.status?.length || 0) > 0 ? <EChart option={pieStatus} height={280} /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} />}
            </Card>
          </Col>
          <Col xs={24} md={8}>
            <Card size="small" title="硬件 / 软件构成">
              {(dist?.type?.length || 0) > 0 ? <EChart option={pieType} height={280} /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} />}
            </Card>
          </Col>
          <Col xs={24} md={8}>
            <Card size="small" title="流程阶段分布（漏斗）">
              {(dist?.funnel?.length || 0) > 0 ? (
                <EChart option={funnelOption} height={280} />
              ) : (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} />
              )}
            </Card>
          </Col>
        </Row>

        <Row gutter={[12, 12]} style={{ marginTop: 12 }}>
          <Col span={24}>
            <Card size="small" title="按立项年度：预算 vs 合同 vs 实付（万元）">
              <EChart option={yearOption} height={340} />
            </Card>
          </Col>
        </Row>
      </Spin>
    </div>
  );
}
