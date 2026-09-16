# syntax=docker/dockerfile:1
# ─────────────────────────────────────────────────────────────────
# PM AI Service 镜像
#
# 用途：本机 / 内网 x86_64 机器上的**验证环境**（先把 OCR 与文档解析跑通）。
# 正式环境若上 GPU 推理，可基于同一份代码另建镜像，本文件仍可作为基础。
#
# 关键点：镜像内**自带 OCR 模型**（rapidocr-onnxruntime 把模型打包在 wheel 里），
#        构建时跑一次真实识别 —— 构建成功即代表离线可用。
# ─────────────────────────────────────────────────────────────────
FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    OMP_NUM_THREADS=4

# libgl1/libglib2.0-0：OpenCV 依赖（RapidOCR 图像处理链路，slim 镜像默认没有）
# libgomp1：onnxruntime 的 OpenMP 运行时
RUN apt-get update && apt-get install -y --no-install-recommends \
        libgl1 \
        libglib2.0-0 \
        libgomp1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 先装依赖（代码改动时不必重装依赖，利用层缓存）
COPY pyproject.toml README.md ./
COPY src/ ./src/
RUN pip install --upgrade pip setuptools wheel \
    && pip install -e .

COPY scripts/ ./scripts/
COPY static/ ./static/

# 构建期冒烟自检：确认打包正确 + OCR 真的能识别（失败会让构建中断）
RUN python scripts/selfcheck_ocr.py

EXPOSE 8100

# 说明：容器内不写死 --port，交由 .env 的 AI_PORT 控制（compose 里映射）
CMD ["uvicorn", "pm_ai.api:app", "--host", "0.0.0.0", "--port", "8100"]
