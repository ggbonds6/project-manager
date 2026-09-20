package com.pmgt.ai.module.retrieval;

import java.util.List;

/**
 * 向量服务探活结果。字段对齐 Python 版 {@code vec_client.VecHealth}（ok / detail / models）。
 *
 * <p>注意 {@code ok=true} 也可能是"网关可达但模型不在列表里"——{@code detail} 里会说清楚，
 * 别只看布尔值。
 */
public record VecHealth(boolean ok, String detail, List<String> models) {
}
