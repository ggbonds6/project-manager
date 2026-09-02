import { Card, Empty } from 'antd';

export default function DashboardPage() {
  return (
    <Card>
      <Empty description="工作台（M5 里程碑实现：汇总卡 / 我的待办 / 近期验收 / 逾期预警）" />
    </Card>
  );
}
