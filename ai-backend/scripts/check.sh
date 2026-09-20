#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────
# PM AI Backend（Java 版）· 质量门：编译 + 单元测试（+ 可选打包）
#
# 用法（在仓库任意位置执行，脚本会自己定位 ai-backend/）：
#   bash scripts/check.sh            # 编译 + 单元测试
#   bash scripts/check.sh package    # 再打一个 fat jar
#
# 依赖：JDK 17 + Maven。本机在 E:\env（JAVA_HOME=E:\env\jdk\jdk-17，Maven 3.9.9）。
# 与 Python 版质量门的差别：Java 侧用 Maven 自己的 test 生命周期，
# 不需要额外装 lint 工具（编译期类型检查已覆盖大半）。
# ─────────────────────────────────────────────────────────────────
set -euo pipefail

cd "$(dirname "$0")/.."   # → ai-backend/
ROOT="$(pwd)"

c_ok()  { printf '\033[32m%s\033[0m\n' "$*"; }
c_err() { printf '\033[31m%s\033[0m\n' "$*"; }

if [[ -z "${JAVA_HOME:-}" && -d "E:/env/jdk/jdk-17" ]]; then
  export JAVA_HOME="E:/env/jdk/jdk-17"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if ! command -v mvn >/dev/null 2>&1; then
  c_err "没找到 mvn。本机 Maven 在 E:\\env\\apache-maven-3.9.9\\bin（确认已在 PATH 上）。"
  exit 1
fi

echo "── 质量门：mvn test（${ROOT}）──"
mvn -B test

if [[ "${1:-}" == "package" ]]; then
  echo
  echo "── 打包 fat jar ──"
  mvn -B -DskipTests package
fi

echo
c_ok "质量门通过：ai-backend 编译 + 单元测试"
