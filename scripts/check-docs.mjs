#!/usr/bin/env node
/**
 * 文档体检（无第三方依赖，Node 18+）。
 *
 * 为什么要有它：这一轮整理文档时发现两类"静默把表格渲染打断"的问题，以及一处失效链接——
 * 都是靠临时脚本才发现的。把检查固化成工具，才能保证**以后不再出现**（写文档的人不一定记得规则）。
 *
 * 用法（仓库根执行）：
 *   node scripts/check-docs.mjs            # 全量体检，有问题时退出码 1
 *   node scripts/check-docs.mjs --quiet    # 只输出结论与问题清单
 *
 * 检查四项：
 *   1) 表格内空行 —— Markdown 里空行即结束表格，后面的行会被渲染成普通段落；
 *   2) 单元格内裸竖线 —— 未转义的 `|` 会被当成多一列（应写 `\|`）；
 *   3) 本地链接失效 —— 相对路径的 [文本](目标) 必须存在（http/https/mailto/#锚点 跳过）；
 *   4) ITERATION 一致性 —— 「迭代总表」数据行数必须等于「各迭代明细」段数（仓库硬约定）。
 */

import fs from 'node:fs';
import path from 'node:path';

const args = process.argv.slice(2);
const quiet = args.includes('--quiet');
const root = path.resolve(args.find((a) => !a.startsWith('--')) ?? '.');
const SKIP_DIRS = new Set(['.git', 'node_modules', 'target', 'work', 'dist', '.venv', '__pycache__', '.workbuddy', 'uploads']);

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (SKIP_DIRS.has(entry.name)) continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else if (entry.name.toLowerCase().endsWith('.md')) out.push(full);
  }
  return out;
}

const rel = (p) => path.relative(root, p).split(path.sep).join('/');
const isRow = (line) => line.trimStart().startsWith('|');
const unescapedPipes = (line) => (line.match(/(?<!\\)\|/g) ?? []).length;

const problems = { tableBlank: [], cellPipe: [], link: [], iteration: [] };
const files = walk(root);

for (const file of files) {
  const lines = fs.readFileSync(file, 'utf8').split('\n');

  // 1) 表格内空行 + 2) 单元格裸竖线
  for (let i = 0; i < lines.length; i++) {
    if (!isRow(lines[i])) continue;
    const headerPipes = unescapedPipes(lines[i]);
    // 往下扫描本表：允许空行，但若空行之后紧跟表格行，说明空行夹在表内
    let j = i + 1;
    while (j < lines.length) {
      if (isRow(lines[j])) {
        const pipes = unescapedPipes(lines[j]);
        if (pipes !== headerPipes) {
          problems.cellPipe.push(`${rel(file)}:${j + 1} 列数 ${pipes} != 表头 ${headerPipes}（单元格里的竖线要写成 \\|）`);
        }
        j++;
      } else if (lines[j].trim() === '') {
        let k = j;
        while (k < lines.length && lines[k].trim() === '') k++;
        if (k < lines.length && isRow(lines[k])) {
          for (let b = j; b < k; b++) problems.tableBlank.push(`${rel(file)}:${b + 1} 表格中间的空行（会让表格在此结束）`);
          j = k;
        } else break;
      } else break;
    }
    i = j;
  }

  // 3) 本地链接
  for (let i = 0; i < lines.length; i++) {
    for (const m of lines[i].matchAll(/\[[^\]]*\]\(([^)]+)\)/g)) {
      let target = m[1].trim();
      if (/^(https?:|mailto:|tel:|#)/.test(target)) continue;
      target = target.split('#')[0];
      if (!target) continue;
      const resolved = path.resolve(path.dirname(file), decodeURIComponent(target));
      if (!fs.existsSync(resolved)) problems.link.push(`${rel(file)}:${i + 1} -> ${m[1]}`);
    }
  }
}

// 4) ITERATION 总表 vs 明细
const iteration = path.join(root, 'ITERATION.md');
if (fs.existsSync(iteration)) {
  const lines = fs.readFileSync(iteration, 'utf8').split('\n');
  const start = lines.findIndex((l) => l.startsWith('| 迭代'));
  const rows = [];
  let end = start;
  for (let i = start + 1; i < lines.length; i++) {
    if (isRow(lines[i])) {
      if (/^\|\s*(v\d|ai-|docs-)/.test(lines[i])) rows.push(lines[i]);
      end = i;
    } else break;
  }
  const segments = lines.filter((l) => l.startsWith('### '));
  if (start >= 0 && rows.length !== segments.length) {
    problems.iteration.push(
      `ITERATION.md 迭代总表数据行 ${rows.length} != 各迭代明细段数 ${segments.length}（仓库硬约定要求一一对应）`,
    );
  }
  if (!quiet) console.log(`ITERATION：总表 ${rows.length} 行 / 明细 ${segments.length} 段`);
}

const total = Object.values(problems).reduce((n, arr) => n + arr.length, 0);
const titles = { tableBlank: '表格内空行', cellPipe: '单元格裸竖线', link: '本地链接失效', iteration: 'ITERATION 一致性' };
for (const [key, arr] of Object.entries(problems)) {
  if (!arr.length) continue;
  console.log(`\n❌ ${titles[key]}（${arr.length}）`);
  arr.forEach((p) => console.log(`   ${p}`));
}
console.log(`\n体检 ${files.length} 份 Markdown，问题 ${total} 处 ${total === 0 ? '✅' : '← 需处理'}`);
process.exit(total === 0 ? 0 : 1);
