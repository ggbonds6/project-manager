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
        OperateLog log = new OperateLog();
        AuthContext.Current current = AuthContext.get();
        if (current != null) {
            log.setUserId(current.userId());
            log.setUserName(current.name());
        }
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
