/**
 * 全局悬浮 AI 图标 + 右侧抽屉
 * ----------------------------------------------------------------
 * 方案 §3.2：挂在 `layout/MainLayout.tsx`（与左侧菜单同级），任何页面右下角一个小图标，
 * 点开右侧 Drawer，**不打断当前操作**（不跳页、不遮全屏）。
 *
 * 对话实现只有一份（`AiChatPanel`），悬浮窗与 `/ai` 页共用（§10 明确"不许写两份"）。
 * 可见性：AI 与知识库面向 ADMIN/MANAGER（VIEWER 只读角色不出现入口，与菜单同一判定）；
 * 前端隐藏只是顺手，真正的接口鉴权在主系统后端。
 */
import { useState } from 'react';
import { Badge, Drawer, Tooltip } from 'antd';
import { CloseOutlined, RobotOutlined } from '@ant-design/icons';
import AiChatPanel from '@/components/ai/AiChatPanel';
import { useAiScope } from '@/components/ai/AiScopePicker';
import { Role } from '@/types';

/** 是否可使用 AI 与知识库（菜单、悬浮入口共用一处判定） */
export function canUseAi(role?: Role | null): boolean {
  return role === 'ADMIN' || role === 'MANAGER';
}

export default function AiChatDrawer() {
  const [open, setOpen] = useState(false);
  const scope = useAiScope();

  // 范围切换时清空会话：上一轮的引用可能已经不在此范围内，留着会误导
  const scopeKey = String(scope.projectId ?? 'all');

  return (
    <>
      <Tooltip title={open ? '收起 AI 问答' : 'AI 问答（基于知识库与附件原文）'} placement="left">
        <button
          type="button"
          aria-label="AI 问答"
          onClick={() => setOpen((v) => !v)}
          style={{
            position: 'fixed',
            right: 24,
            bottom: 24,
            width: 52,
            height: 52,
            borderRadius: '50%',
            border: 'none',
            cursor: 'pointer',
            background: 'linear-gradient(135deg,#2f5d8a,#1677ff)',
            color: '#fff',
            fontSize: 22,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            boxShadow: '0 6px 16px rgba(22,119,255,.35)',
            // 低于 antd Modal/Drawer(1000+) 与消息提示(2000+)，避免盖住预览弹窗
            zIndex: 900,
          }}
        >
          <Badge dot={!open} offset={[-2, 4]}>
            {open ? <CloseOutlined /> : <RobotOutlined />}
          </Badge>
        </button>
      </Tooltip>

      <Drawer
        title="AI 知识问答"
        placement="right"
        width={520}
        open={open}
        onClose={() => setOpen(false)}
        // 不销毁：切来切去时保留本轮对话（清空由面板内的"清空会话"负责）
        destroyOnClose={false}
        styles={{ body: { padding: 0, overflow: 'hidden' } }}
      >
        {/* 高度占满抽屉 body：抽屉自身滚动会与消息流滚动冲突，故这里交给面板内部滚动 */}
        <AiChatPanel scope={scope} scopeKey={scopeKey} height="100%" />
      </Drawer>
    </>
  );
}
