import { Alert, Card } from 'antd';

export default function ProjectsPage() {
  return (
    <Card title="项目管理">
      <Alert
        type="info"
        showIcon
        message="M3 里程碑实现"
        description="项目列表/卡片视图、分类筛选与关键字检索、分页、新建项目。"
      />
    </Card>
  );
}
