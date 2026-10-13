package com.pmgt.module.ai.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pmgt.module.ai.pipeline.entity.AiPipelineStage;

/**
 * 阶段行 Mapper。
 *
 * <p>刻意<b>不加自定义 SQL</b>：阶段的"同键更新同一行"由服务层用
 * {@code LambdaQueryWrapper(unique key)} + {@code updateById} 表达，
 * 领取/续约/终态这几个必须"受影响行数 = 1"的语句也走 MyBatis-Plus 的
 * {@code update(null, wrapper)}（与既有的 {@code AiTaskService} 同风格，
 * 不引入新的方言写法，见设计文档 §5.7 的"崖山 Oracle 模式"注意事项）。
 */
public interface AiPipelineStageMapper extends BaseMapper<AiPipelineStage> {
}
