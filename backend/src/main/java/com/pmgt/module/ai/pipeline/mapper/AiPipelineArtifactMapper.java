package com.pmgt.module.ai.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pmgt.module.ai.pipeline.entity.AiPipelineArtifact;

/**
 * 产物行 Mapper。
 *
 * <p>服务层只调用 {@code insert} / {@code selectList}——<b>没有 update 路径</b>（§4.1 P4：产物不可变）。
 * 这不是靠约定，而是靠"本表没有任何 UPDATE 语句"这一事实。
 */
public interface AiPipelineArtifactMapper extends BaseMapper<AiPipelineArtifact> {
}
