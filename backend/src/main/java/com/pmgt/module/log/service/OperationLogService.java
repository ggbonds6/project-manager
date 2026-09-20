package com.pmgt.module.log.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pmgt.common.security.AuthContext;
import com.pmgt.module.log.entity.OperateLog;
import com.pmgt.module.log.mapper.OperateLogMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 操作留痕（轻量实现：业务方显式调用）。
 */
@Service
public class OperationLogService {

    private final OperateLogMapper operateLogMapper;

    public OperationLogService(OperateLogMapper operateLogMapper) {
        this.operateLogMapper = operateLogMapper;
    }

    public void log(String bizType, Long bizId, String action, String detail) {
        AuthContext.Current current = AuthContext.get();
        log(bizType, bizId, action, detail,
                current == null ? null : current.userId(),
                current == null ? null : current.name());
    }

    /**
     * 显式指定操作人（后台线程用）。
     *
     * <p>为什么需要它：{@code AuthContext} 是 ThreadLocal，只存在于处理 HTTP 请求的那个线程。
     * 上传完成后的自动解析发生在<b>后台线程</b>，若走原方法，日志会写出一条「操作人为空」的记录——
     * 审计上等于查不到"谁触发的这次解析"。
     *
     * <p>只加重载、不改原方法：既有调用点（十余处）行为必须保持不变。
     */
    public void log(String bizType, Long bizId, String action, String detail,
                    Long userId, String userName) {
        OperateLog log = new OperateLog();
        log.setUserId(userId);
        log.setUserName(userName);
        log.setBizType(bizType);
        log.setBizId(bizId);
        log.setAction(action);
        log.setDetail(detail);
        operateLogMapper.insert(log);
    }

    /** 项目维度操作日志（详情页"操作日志"页签） */
    public List<OperateLog> listByProject(Long projectId) {
        return operateLogMapper.selectList(new LambdaQueryWrapper<OperateLog>()
                .eq(OperateLog::getBizType, "PROJECT")
                .eq(OperateLog::getBizId, projectId)
                .orderByDesc(OperateLog::getId));
    }
}
