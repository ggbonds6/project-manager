# syntax=docker/dockerfile:1
# ─────────────────────────────────────────────────────────────────
# PM AI Service 镜像
#
# 用途：本机 / 内网机器上的**验证环境**（文档解析 + 平台 OCR + 抽取问答）。
#
# 关键点（2026-09-18 起）：
#   - **镜像内不含任何本地 OCR 引擎**：识别全部走内网平台 OCR（HTTP），
#     因此不再需要 OpenCV / onnxruntime 的运行时依赖（libgl1 / libglib2.0-0 / libgomp1），
#     镜像更小、构建更快，也不再在构建期下载/打包 OCR 模型；
#   - 构建期只校验"包能导入"——真实识别依赖外部平台，构建期连不上，
#     请用 `python scripts/check_env.py` 在**运行时**自检（见 README §2.1）。
# ─────────────────────────────────────────────────────────────────
FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

# 先装依赖（代码改动时不必重装依赖，利用层缓存）
COPY pyproject.toml README.md ./
COPY src/ ./src/
RUN pip install --upgrade pip setuptools wheel \
    && pip install -e .

COPY scripts/ ./scripts/
COPY static/ ./static/

# 构建期冒烟自检：只确认包结构正确、能 import
# （平台 OCR 与大模型都要运行时才连得上，构建期验证不了）
RUN python -c "import pm_ai, pm_ai.api, pm_ai.document, pm_ai.platform_ocr; print('pm_ai import ok')"

EXPOSE 8100

# 说明：容器内不写死 --port，交由 .env 的 AI_PORT 控制（compose 里映射）
CMD ["uvicorn", "pm_ai.api:app", "--host", "0.0.0.0", "--port", "8100"]
