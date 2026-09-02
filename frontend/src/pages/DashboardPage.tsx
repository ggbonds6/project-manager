import { useEffect, useState } from 'react';
import { Card, Col, Empty, List, Row, Spin, Statistic, Tag, Typography } from 'antd';
import {
  AlertOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  DatabaseOutlined,
  FundOutlined,
  PlayCircleOutlined,
  PlusCircleOutlined,
  ProjectOutlined,
  ScheduleOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { statsApi } from '@/api/stats';
import { fmtDate, fmtDateTime, fmtWan } from '@/utils/format';
import { DashboardData } from '@/types/stats';
import { PROJECT_STATUS } from '@/types';

export default function DashboardPage() {
  const navigate = useNavigate();
  const [data, setData] = useState<DashboardData | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    statsApi
      .dashboard()
      .then(setData)
      .finally(() => setLoading(false));
  }, []);

  if (loading || !data) {
    return (
      <div style={{ textAlign: 'center', padding: 80 }}>
        <Spin size="large" tip="加载中..." />
      </div>
    );
  }

  const c = data.cards;

  const cards: { t: string; v: number | string; icon: React.ReactNode; color?: string; suffix?: string }[] = [
    { t: '项目总数', v: c.projectTotal, icon: <DatabaseOutlined />, color: '#2f5d8a' },
    { t: '进行中', v: c.running, icon: <PlayCircleOutlined />, color: '#1677ff' },
    { t: '本年新建', v: c.newThisYear, icon: <PlusCircleOutlined />, color: '#13c2c2' },
    { t: '已完结', v: c.done, icon: <CheckCircleOutlined />, color: '#52c41a' },
    { t: '暂停/中止', v: c.pause + c.stop, icon: <ClockCircleOutlined />, color: '#faad14' },
    { t: '逾期预警', v: c.overdueCount, icon: <AlertOutlined />, color: '#ff4d4f' },
    { t: '累计合同', v: fmtWan(c.contractTotal), icon: <FundOutlined />, suffix: '万元', color: '#722ed1' },
    { t: '累计实付', v: fmtWan(c.paidTotal), icon: <SyncOutlined />, suffix: '万元', color: '#eb2f96' },
  ];

  return (
    <div>
      <Row gutter={[12, 12]}>
        {cards.map((card) => (
          <Col key={card.t} xs={12} sm={12} md={6} lg={3}>
            <Card size="small">
              <Statistic
                title={
                  <span>
                    {card.icon} {card.t}
                  </span>
                }
                value={card.v}
                suffix={card.suffix}
                valueStyle={{ color: card.color, fontSize: 22 }}
              />
            </Card>
          </Col>
        ))}
      </Row>

      <Row gutter={[12, 12]} style={{ marginTop: 12 }}>
        <Col xs={24} lg={8}>
          <Card
            size="small"
            title={
              <span>
                <ProjectOutlined /> 我的待办（{data.myTodos.length}）
              </span>
            }
          >
            {data.myTodos.length === 0 ? (
              <Empty description="暂无待推进阶段" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              <List
                size="small"
                dataSource={data.myTodos.slice(0, 10)}
                renderItem={(t) => (
                  <List.Item
                    style={{ cursor: 'pointer', paddingLeft: 4 }}
                    onClick={() => navigate(`/projects/${t.projectId}`)}
                  >
                    <List.Item.Meta
                      title={
                        <span>
                          {t.projectName}
                          {t.overdue ? <Tag color="red" style={{ marginLeft: 6 }}>逾期</Tag> : null}
                        </span>
                      }
                      description={`${t.phaseName} · 进度 ${t.percent}% · 计划完成 ${fmtDate(t.planFinishDate)}`}
                    />
                  </List.Item>
                )}
              />
            )}
          </Card>
        </Col>

        <Col xs={24} lg={8}>
          <Card
            size="small"
            title={
              <span>
                <ScheduleOutlined /> 60 天内计划初验/终验（{data.upcoming.length}）
              </span>
            }
          >
            {data.upcoming.length === 0 ? (
              <Empty description="暂无近期验收计划" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              <List
                size="small"
                dataSource={data.upcoming}
                renderItem={(u) => (
                  <List.Item style={{ cursor: 'pointer', paddingLeft: 4 }} onClick={() => navigate(`/projects/${u.projectId}`)}>
                    <List.Item.Meta
                      title={u.projectName}
                      description={`${u.phaseName} · ${fmtDate(u.planDate)}`}
                    />
                  </List.Item>
                )}
              />
            )}
          </Card>
        </Col>

        <Col xs={24} lg={8}>
          <Card
            size="small"
            title={
              <span>
                <AlertOutlined /> 逾期预警（{data.overdue.length}）
              </span>
            }
          >
            {data.overdue.length === 0 ? (
              <Empty description="无逾期阶段" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              <List
                size="small"
                dataSource={data.overdue}
                renderItem={(o) => (
                  <List.Item style={{ cursor: 'pointer', paddingLeft: 4 }} onClick={() => navigate(`/projects/${o.projectId}`)}>
                    <List.Item.Meta
                      title={
                        <span>
                          {o.projectName} <Tag color="red">{o.days} 天</Tag>
                        </span>
                      }
                      description={`${o.phaseName} · 计划 ${fmtDate(o.planDate)}`}
                    />
                  </List.Item>
                )}
              />
            )}
          </Card>
        </Col>
      </Row>

      <Card size="small" style={{ marginTop: 12 }} title="最近更新">
        {data.recent.length === 0 ? (
          <Empty description="暂无项目" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <Row gutter={[8, 8]}>
            {data.recent.map((p) => {
              const st = PROJECT_STATUS[p.status];
              return (
                <Col key={p.id} xs={24} md={8} lg={4}>
                  <Card
                    size="small"
                    hoverable
                    style={{ background: '#fafafa' }}
                    onClick={() => navigate(`/projects/${p.id}`)}
                  >
                    <Typography.Text ellipsis style={{ width: '100%' }}>
                      {p.name}
                    </Typography.Text>
                    <div style={{ marginTop: 4, fontSize: 12, color: '#8c8c8c' }}>
                      <span style={{ fontFamily: 'Consolas,monospace' }}>{p.code}</span>{' '}
                      {st ? <Tag color={st.color}>{st.text}</Tag> : null}
                      <br />
                      当前：{p.currentPhaseName || '-'}
                    </div>
                    <div style={{ fontSize: 12, color: '#bfbfbf' }}>{fmtDateTime(p.updateTime)}</div>
                  </Card>
                </Col>
              );
            })}
          </Row>
        )}
      </Card>
    </div>
  );
}
