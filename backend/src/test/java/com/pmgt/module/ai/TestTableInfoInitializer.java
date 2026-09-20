package com.pmgt.module.ai;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.pmgt.module.ai.entity.AiAskLog;
import com.pmgt.module.ai.entity.AttachmentAiTask;
import com.pmgt.module.attach.entity.Attachment;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 让 MyBatis-Plus 的 Lambda 缓存可用（仅测试用）。
 *
 * <p>为什么需要它：{@code LambdaUpdateWrapper.set(Attachment::getAiDocId, null)} 这类写法
 * 依赖 MyBatis-Plus 在**启动时**通过 mapper 注册初始化的 {@code TableInfo} 缓存。
 * 纯 Mockito 单测不启动 Spring/MyBatis，缓存是空的，于是抛
 * {@code can not find lambda cache for this entity}——这不是业务缺陷，
 * 而是"单测里没有框架启动过程"。这里把同样的初始化显式做一遍，
 * 好处是：<b>顺带验证了实体上的 @TableName/@TableId/@TableLogic 注解配置是正确的</b>
 * （注解写错时初始化就会失败或列名映射不对）。
 */
public final class TestTableInfoInitializer {

    private TestTableInfoInitializer() {
    }

    /** 幂等：MyBatis-Plus 的初始化本身是 synchronized + 可重复调用的。 */
    public static void init() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Attachment.class);
        TableInfoHelper.initTableInfo(assistant, AttachmentAiTask.class);
        TableInfoHelper.initTableInfo(assistant, AiAskLog.class);
    }
}
