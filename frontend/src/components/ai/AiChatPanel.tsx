/**
 * 问答面板（**唯一一份对话实现**：悬浮窗与 `/ai` 页的「问答助手」页签共用）
 * ----------------------------------------------------------------
 * 设计取舍（对应方案 §3.2、§8.3、§10）：
 *  - 只有"消息流 + 范围 + 出处 + 工具轨迹 + 降级提示 + 清空"，不做多会话/停止生成/反馈按钮
 *    （P2 才做，留个不能用的按钮比没有更糟）；
 *  - "未找到"是**后端正常回答的一种结果**，按普通答案渲染；只有请求失败（HTTP/业务码非 0）才进错误态。
 *    两者外观必须区分：方案 §6 与 §9 明确"AI 服务不可用不许降级成没找到"；
 *  - 引用可点击：直接复用主系统既有 `AttachmentPreviewModal` 打开附件原文，并用其 `pageNo`
 *    参数定位到引用页码（PDF 走 `#page=N`）。引用缺 attachmentId 时用文档列表接口映射一次
 *    （见 `api/ai.ts` 的 resolveCitationAttachment）；映射不到就按"附件已删除"提示，**不做成坏链接**。
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, App, Button, Collapse, Empty, Input, Space, Spin, Tag, Typography } from 'antd';
import { ClearOutlined, DatabaseOutlined, SendOutlined } from '@ant-design/icons';
import ReactMarkdown from 'react-markdown';
import { aiApi } from '@/api/ai';
import AiScopePicker, { AiScopeState } from '@/components/ai/AiScopePicker';
import { useAttachmentPreview } from '@/components/ai/useAttachmentPreview';
import { AiChatAnswer, AiCitation, AiSystemDatum } from '@/types/ai';

/** 会话内的一条消息（不引状态管理库，符合 §10「对话流用 useState 局部状态」） */
export interface AiChatMessage {
  id: string;
  role: 'user' | 'ai';
  text: string;
  /** 仅 AI 消息：完整答案（含引用/轨迹/降级说明）；出错时为空 */
  answer?: AiChatAnswer;
  /** 仅 AI 消息：请求失败原因（与"未找到"是两回事） */
  error?: string;
}

interface Props {
  scope: AiScopeState;
  /** 切换该值会清空会话（例如换到另一个页面上下文） */
  scopeKey?: string;
  /** 面板高度（抽屉里算满，页面内给固定高度） */
  height?: number | string;
}

const WELCOME: AiChatMessage = {
  id: 'welcome',
  role: 'ai',
  text: [
    '你好，我可以基于**知识库里的附件原文**回答问题，并在答案里给出处（文件名 + 页码）。',
    '',
    '- 问题里的金额、页数等以原文为准；查不到我会直接说**未找到**，不会猜。',
    '- 顶部可选择提问范围（默认跟随当前项目页），范围之外的文档我不会读到。',
    '- 点击答案下方的出处标签，可直接打开附件原文并跳到对应页码。',
  ].join('\n'),
};

export default function AiChatPanel({ scope, scopeKey, height }: Props) {
  // 用 App.useApp() 而不是静态 message：与主系统其它组件一致，且能拿到 ConfigProvider 主题
  const { message } = App.useApp();
  /** 附件原文预览（与 /ai 文档库共用同一份实现） */
  const preview = useAttachmentPreview();

  const [messages, setMessages] = useState<AiChatMessage[]>([WELCOME]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  /** 会话 id 由后端返回，继续提问时带上（P0 后端可忽略，但不影响契约） */
  const [conversationId, setConversationId] = useState<string | null>(null);
  /** 正在解析附件 id 的引用 key（防止连点重复触发映射请求） */
  const [openingKey, setOpeningKey] = useState<string | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);

  // 上下文切换（如从项目 A 页到项目 B 页、或用户手工改了范围）：清空会话，
  // 避免上一轮的引用与本轮范围不符——引用必须与"当前范围"这句承诺一致，否则会误导用户。
  const prevScopeRef = useRef(scopeKey);
  useEffect(() => {
    if (prevScopeRef.current === scopeKey) return;
    prevScopeRef.current = scopeKey;
    setMessages([WELCOME]);
    setConversationId(null);
    setInput('');
    // 静默清空会让用户以为"对话丢了"，明确告知原因
    void message.info('提问范围已变化，已清空当前会话避免引用与范围不一致');
  }, [scopeKey, message]);

  // 新消息后滚到底部（对话流最常见的可用性细节）
  useEffect(() => {
    const el = listRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages, sending]);

  const send = useCallback(async () => {
    const q = input.trim();
    if (!q || sending) return;
    const userMsg: AiChatMessage = { id: `u-${Date.now()}`, role: 'user', text: q };
    setMessages((prev) => [...prev, userMsg]);
    setInput('');
    setSending(true);
    try {
      // 范围就是 projectId：传 null 表示"全部可访问范围"，由后端解析权限（§9 #9）
      const res = await aiApi.chat({
        question: q,
        projectId: scope.projectId,
        conversationId,
      });
      if (res.conversationId) setConversationId(res.conversationId);
      setMessages((prev) => [
        ...prev,
        { id: `a-${Date.now()}`, role: 'ai', text: res.answer || '', answer: res },
      ]);
    } catch (e) {
      // 请求失败 = 服务/网络/权限问题，明确区别于"未找到"
      setMessages((prev) => [
        ...prev,
        {
          id: `e-${Date.now()}`,
          role: 'ai',
          text: '',
          error: (e as Error).message || 'AI 服务调用失败',
        },
      ]);
    } finally {
      setSending(false);
    }
  }, [input, sending, scope.projectId, conversationId]);

  /**
   * 点击引用出处 → 打开附件原文并定位到引用页码。
   *
   * 复用的是主系统既有的 `AttachmentPreviewModal`（PDF/图片/Office/文本各有渲染分支），
   * 因此只需要一个最小可用的 AttachmentItem：id + 文件名（fileExt 由文件名推出）。
   * fileSize 未知不影响渲染——该字段只用于"大文件先提示"那一步。
   *
   * attachmentId 的取得顺序：引用里直接给 → 文档列表接口按 docId/文件名映射一次（带缓存，见 useAttachmentPreview）。
   * 两者都拿不到（文档已删除等）时，**不打开也不跳转**，明确提示附件已删除。
   */
  const openCitation = useCallback(
    async (citation: AiCitation) => {
      const key = `${citation.docId || citation.filename || ''}-${citation.index}`;
      setOpeningKey(key);
      try {
        await preview.open({
          attachmentId: citation.attachmentId,
          docId: citation.docId,
          filename: citation.filename,
          pageNo: citation.pageNo,
        });
      } finally {
        setOpeningKey(null);
      }
    },
    [preview],
  );

  const clear = () => {
    setMessages([WELCOME]);
    setConversationId(null);
  };

  // 高度：抽屉里给满高，页面内默认 60vh
  const bodyHeight = height ?? '60vh';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: bodyHeight }}>
      {/* 顶部：范围必须显式可见（安全相关） */}
      <div
        style={{
          padding: '8px 12px',
          borderBottom: '1px solid #f0f0f0',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: 8,
        }}
      >
        <AiScopePicker scope={scope} compact />
        <Button size="small" icon={<ClearOutlined />} onClick={clear} disabled={sending}>
          清空会话
        </Button>
      </div>

      {/* 消息流 */}
      <div ref={listRef} style={{ flex: 1, overflow: 'auto', padding: 12, background: '#fafbfc' }}>
        {messages.length === 0 ? <Empty description="还没有对话" /> : null}
        {messages.map((m) => (
          <MessageBubble key={m.id} msg={m} onCitationClick={openCitation} openingKey={openingKey} />
        ))}
        {sending ? (
          <div style={{ padding: '8px 4px', color: '#8c8c8c', fontSize: 12 }}>
            <Spin size="small" /> 正在检索知识库并生成回答…
          </div>
        ) : null}
      </div>

      {/* 输入区 */}
      <div style={{ padding: 8, borderTop: '1px solid #f0f0f0', background: '#fff' }}>
        <Space.Compact style={{ width: '100%' }}>
          <Input.TextArea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="就当前范围提问，例如：这个项目的合同金额和付款节点是什么？"
            autoSize={{ minRows: 1, maxRows: 4 }}
            onPressEnter={(e) => {
              // Enter 发送，Shift+Enter 换行（与主流问答窗一致）
              if (!e.shiftKey) {
                e.preventDefault();
                void send();
              }
            }}
            disabled={sending}
          />
          <Button
            type="primary"
            icon={<SendOutlined />}
            onClick={() => void send()}
            loading={sending}
            disabled={!input.trim()}
          >
            发送
          </Button>
        </Space.Compact>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          答案只来自知识库原文与系统数据；查不到会明确说"未找到"。
        </Typography.Text>
      </div>

      {/* 引用预览：就地打开，不跳页、不打断当前范围与对话 */}
      {preview.el}
    </div>
  );
}

/* ==================== 单条消息 ==================== */

/**
 * 工具名 → 中文可读文案（§11.3：AI 侧只有一个受控查询工具 `query_business_data`）。
 *
 * 为什么不直接展示 `t.name`：那是给开发看的英文标识（`query_business_data`），
 * 用户看不懂，且会与 markdown 里的 `[1]` 引用挤在一起造成误解。
 * 未收录的名字**原样回落到 name**，不吞信息（与 `aiIndexStatusMeta` 同一取舍：
 * 后端将来新增工具时页面至少还能看到原始标识）。
 */
const AI_TOOL_NAME_TEXT: Record<string, string> = {
  query_business_data: '查询系统数据',
  search_documents: '检索文档',
  read_page: '读取原文页',
  calculate: '计算',
};

export function aiToolNameText(name?: string | null): string {
  if (!name) return '未知工具';
  return AI_TOOL_NAME_TEXT[name] || name;
}

/**
 * 系统数据一条值：`value` + `unit`。
 * 契约里 `unit` 有的场景会跟着行数据一起给（如 `个`/`万元`），为 `null` 时不要渲染空串占位。
 */
function systemDatumValue(d: AiSystemDatum): string {
  const value = d.value === null || d.value === undefined || d.value === '' ? '-' : String(d.value);
  return d.unit ? `${value} ${d.unit}` : value;
}

/**
 * 「系统数据」区块（§11 受控查询结果，与「文档依据」严格分开）。
 *
 * 审计硬要求：**口径（caliber）与数据时间（dataTime）必须与数字同时可见**，
 * 否则一个孤零零的数字无法对账（§11.4：「模型必须能原话转述」）。
 * 因此二者不做折叠、不做 tooltip 隐藏，直接排在每条数据下方。
 *
 * 视觉上与「出处（citations）」的区别：本块是**灰底 + 数据库图标 + 独立小标题**，
 * 出处是白底蓝色 Tag —— 用户一眼能分清"这个数字来自系统表"还是"这句话来自某份文档第 N 页"。
 */
function SystemDataBlock({ data }: { data: AiSystemDatum[] }) {
  return (
    <div
      style={{
        marginTop: 10,
        padding: '8px 10px',
        background: '#f6f8fa',
        border: '1px solid #e6ebf0',
        borderLeft: '3px solid #2f5d8a',
        borderRadius: 6,
      }}
    >
      <div style={{ fontSize: 12, fontWeight: 600, color: '#2f5d8a', marginBottom: 4 }}>
        <DatabaseOutlined /> 来自系统数据
      </div>
      {data.map((d, i) => (
        // key 用下标而不只是 label：同一块数据里 label 允许重复，用 label 会触发 React 重复 key 警告
        <div
          key={`${d.label}-${i}`}
          style={{
            padding: '3px 0',
            borderTop: i === 0 ? 'none' : '1px dashed #e6ebf0',
          }}
        >
          <div style={{ fontSize: 13, color: '#1f2329' }}>
            <span style={{ color: '#595959' }}>{d.label}：</span>
            <b>{systemDatumValue(d)}</b>
          </div>
          {/* 口径与数据时间：审计要求，必须同时显示，不折叠不省略 */}
          <div style={{ fontSize: 12, color: '#8c8c8c', lineHeight: 1.6 }}>
            <span>口径：{d.caliber || '未提供'}</span>
            <span style={{ margin: '0 6px' }}>·</span>
            <span>数据时间：{d.dataTime || '未提供'}</span>
            {d.scope ? (
              <>
                <span style={{ margin: '0 6px' }}>·</span>
                <span>范围：{d.scope}</span>
              </>
            ) : null}
          </div>
        </div>
      ))}
    </div>
  );
}

function MessageBubble({
  msg,
  onCitationClick,
  openingKey,
}: {
  msg: AiChatMessage;
  onCitationClick: (c: AiCitation) => void;
  openingKey: string | null;
}) {
  const isUser = msg.role === 'user';
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: isUser ? 'flex-end' : 'flex-start',
        marginBottom: 10,
      }}
    >
      <div
        style={{
          maxWidth: isUser ? '82%' : '94%',
          background: isUser ? '#2f5d8a' : '#fff',
          color: isUser ? '#fff' : '#1f2329',
          borderRadius: 8,
          padding: '8px 12px',
          boxShadow: '0 1px 2px rgba(0,0,0,.06)',
          fontSize: 13,
          lineHeight: 1.7,
          wordBreak: 'break-word',
        }}
      >
        {msg.error ? (
          <Alert
            type="error"
            showIcon
            message="AI 服务调用失败"
            description={
              <>
                <div>{msg.error}</div>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  这是服务/网络/权限问题，<b>不代表知识库里没有资料</b>；可在「AI 与知识库 → 服务自检」查看服务状态。
                </Typography.Text>
              </>
            }
          />
        ) : isUser ? (
          <span style={{ whiteSpace: 'pre-wrap' }}>{msg.text}</span>
        ) : (
          <>
            {/* markdown 渲染复用既有依赖 react-markdown（§10 不新增依赖） */}
            <div className="pm-md-body">
              <ReactMarkdown>{msg.text}</ReactMarkdown>
            </div>

            {msg.answer?.degraded ? (
              <Alert
                type="warning"
                showIcon
                style={{ marginTop: 8 }}
                message="本次回答处于降级状态"
                description={msg.answer.notice || '检索能力不完整，结论仅供参考，请以原文为准。'}
              />
            ) : msg.answer?.notice ? (
              <Alert type="info" showIcon style={{ marginTop: 8 }} message={msg.answer.notice} />
            ) : null}

            {msg.answer?.systemData?.length ? (
              <SystemDataBlock data={msg.answer.systemData} />
            ) : null}

            {msg.answer?.citations?.length ? (
              <div style={{ marginTop: 8 }}>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  出处（点击打开附件原文并跳到对应页）：
                </Typography.Text>
                <div style={{ marginTop: 4, display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                  {msg.answer.citations.map((c) => {
                    const clickable = !!(c.attachmentId || c.docId || c.filename);
                    const key = `${c.docId || c.filename || ''}-${c.index}`;
                    const label = `${c.filename || c.docId || '未知文档'}${
                      c.pageNo ? ` 第 ${c.pageNo} 页` : ''
                    }`;
                    return clickable ? (
                      <Tag
                        key={key}
                        color="blue"
                        style={{ cursor: 'pointer', marginInlineEnd: 0 }}
                        title={c.snippet || '点击打开附件原文'}
                        onClick={() => onCitationClick(c)}
                      >
                        [{c.index}] {label}
                        {openingKey === key ? ' 打开中…' : ''}
                        {typeof c.score === 'number' ? ` · ${c.score.toFixed(2)}` : ''}
                      </Tag>
                    ) : (
                      // 拿不到任何可定位信息：如实说明，不做成可点的坏链接
                      <Tag key={key} style={{ marginInlineEnd: 0 }}>
                        [{c.index}] {label}（附件已删除）
                      </Tag>
                    );
                  })}
                </div>
              </div>
            ) : null}

            {msg.answer?.toolTrace?.length ? (
              <Collapse
                ghost
                size="small"
                style={{ marginTop: 4 }}
                items={[
                  {
                    key: 'trace',
                    label: (
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        依据与检索过程（{msg.answer.toolTrace.length} 步
                        {typeof msg.answer.elapsedMs === 'number'
                          ? ` · 耗时 ${(msg.answer.elapsedMs / 1000).toFixed(1)}s`
                          : ''}
                        ）
                      </Typography.Text>
                    ),
                    children: (
                      <div style={{ fontSize: 12, color: '#595959' }}>
                        {msg.answer.toolTrace.map((t, i) => (
                          <div key={`${t.name}-${i}`} style={{ marginBottom: 2 }}>
                            {/* 工具名转中文（如 query_business_data → 查询系统数据），未收录的原样回落 */}
                            <Tag>{aiToolNameText(t.name)}</Tag>
                            {/* summary 由后端给中文摘要；取不到时回落到原始工具名（不看不懂的空白） */}
                            {t.summary || t.name}
                            {/* 命中数只对检索类工具有意义；受控查询返回的是行/聚合值，后端不给 hitCount */}
                            {typeof t.hitCount === 'number' ? ` · 命中 ${t.hitCount} 条` : ''}
                            {typeof t.elapsedMs === 'number' ? ` · ${t.elapsedMs}ms` : ''}
                          </div>
                        ))}
                      </div>
                    ),
                  },
                ]}
              />
            ) : null}
          </>
        )}
      </div>
    </div>
  );
}

