package com.pmgt.module.ai.dto;

import lombok.Data;

import java.util.List;

/**
 * §9 #2 / #4 的分页信封 {@code {total, records:[...]}}。
 *
 * <p>主系统既有的分页接口用 MyBatis-Plus 的 {@code Page}（含 current/size/pages 等）。
 * 这里刻意只给 {@code total + records}：AI 文档库的数据源在 AI 服务侧（无分页参数），
 * 主系统的分页是「取全量再切」，带上 current/size 反而会让人误以为做了数据库分页。
 * 前端契约 §9 也只要求这两个字段。
 */
@Data
public class AiPageVO<T> {

    private long total;
    private List<T> records;

    public AiPageVO(List<T> records) {
        this.records = records == null ? List.of() : records;
        this.total = this.records.size();
    }

    /**
     * 分页构造：{@code total} 是<b>过滤后的全量条数</b>，{@code records} 只放当前页。
     *
     * <p>为什么要有它：早先只有 {@code AiPageVO(records)}，分页时传进去的是「当前页的记录」，
     * 于是第 2 页的 total 变成 1——前端分页组件会算出"只有 1 条"，
     * 后面的页直接点不到（这是只有分页测试才能发现的缺陷）。
     * 参数取 total 而不是"全量 List"，是为了让「任务实体 → VO」这种转换也能直接分页。
     */
    public static <T> AiPageVO<T> of(long total, List<T> pageRecords) {
        AiPageVO<T> vo = new AiPageVO<>(pageRecords);
        vo.total = total;
        return vo;
    }
}
