package com.pmgt.module.ai.pipeline.dto;

import lombok.Data;

import java.util.List;

/**
 * 流水线运行列表的分页信封 {@code {total, page, size, records}}。
 *
 * <p>与仓库既有的 {@code AiPageVO}（{@code {total, records}}）刻意不同：本接口是
 * <b>数据库分页</b>（{@code page/size} 真的下推到 SQL），把 {@code page/size} 回显出来
 * 前端才知道"自己在第几页"，也不会误以为拿的是全量。
 */
@Data
public class PipelineRunPageVO {

    /** 过滤条件下的总行数（不是本页条数）。 */
    private long total;
    private int page;
    private int size;
    private List<PipelineRunVO> records;

    public static PipelineRunPageVO of(long total, int page, int size, List<PipelineRunVO> records) {
        PipelineRunPageVO vo = new PipelineRunPageVO();
        vo.total = total;
        vo.page = page;
        vo.size = size;
        vo.records = records == null ? List.of() : records;
        return vo;
    }
}
