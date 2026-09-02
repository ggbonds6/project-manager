package com.pmgt.module.project.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pmgt.common.api.R;
import com.pmgt.common.exception.BizException;
import com.pmgt.common.security.RequireRole;
import com.pmgt.common.security.Role;
import com.pmgt.module.log.service.OperationLogService;
import com.pmgt.module.project.dto.PaymentSaveRequest;
import com.pmgt.module.project.dto.PaymentVO;
import com.pmgt.module.project.entity.Payment;
import com.pmgt.module.project.entity.Project;
import com.pmgt.module.project.mapper.PaymentMapper;
import com.pmgt.module.project.mapper.ProjectMapper;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class PaymentController {

    private static final java.util.Set<String> NODES = java.util.Set.of("PREPAY", "ARRIVAL", "FIRST_ACCEPT", "FINAL_ACCEPT", "WARRANTY");
    private static final java.util.Set<String> STATUS = java.util.Set.of("UNPAID", "PART", "PAID");

    private final PaymentMapper paymentMapper;
    private final ProjectMapper projectMapper;
    private final OperationLogService operationLogService;

    public PaymentController(PaymentMapper paymentMapper, ProjectMapper projectMapper, OperationLogService operationLogService) {
        this.paymentMapper = paymentMapper;
        this.projectMapper = projectMapper;
        this.operationLogService = operationLogService;
    }

    @GetMapping("/projects/{projectId}/payments")
    public R<List<PaymentVO>> listByProject(@PathVariable Long projectId) {
        return R.ok(paymentMapper.selectList(new LambdaQueryWrapper<Payment>()
                        .eq(Payment::getProjectId, projectId)
                        .orderByAsc(Payment::getId))
                .stream().map(PaymentVO::from).toList());
    }

    @RequireRole({Role.ADMIN})
    @PostMapping("/payments")
    public R<Long> create(@Valid @RequestBody PaymentSaveRequest req) {
        Project pj = projectMapper.selectById(req.getProjectId());
        if (pj == null) {
            throw new BizException(404, "项目不存在");
        }
        Payment p = new Payment();
        apply(p, req);
        paymentMapper.insert(p);
        operationLogService.log("PROJECT", pj.getId(), "PAYMENT_ADD",
                "新增付款记录「" + p.getNodeName() + "」计划 " + p.getPlanAmount() + " 元");
        return R.ok(p.getId());
    }

    @RequireRole({Role.ADMIN})
    @PutMapping("/payments/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody PaymentSaveRequest req) {
        Payment exist = paymentMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "付款记录不存在");
        }
        if (!exist.getProjectId().equals(req.getProjectId())) {
            throw new BizException(400, "付款记录归属项目不匹配");
        }
        apply(exist, req);
        paymentMapper.updateById(exist);
        operationLogService.log("PROJECT", exist.getProjectId(), "PAYMENT_UPDATE",
                "更新付款记录「" + exist.getNodeName() + "」");
        return R.ok();
    }

    @RequireRole({Role.ADMIN})
    @DeleteMapping("/payments/{id}")
    public R<Void> delete(@PathVariable Long id) {
        Payment exist = paymentMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "付款记录不存在");
        }
        paymentMapper.deleteById(id);
        operationLogService.log("PROJECT", exist.getProjectId(), "PAYMENT_DELETE",
                "删除付款记录「" + exist.getNodeName() + "」");
        return R.ok();
    }

    private void apply(Payment p, PaymentSaveRequest req) {
        if (!NODES.contains(req.getNodeCode())) {
            throw new BizException(400, "非法付款节点编码");
        }
        String status = req.getStatus();
        if (status == null || !STATUS.contains(status)) {
            status = req.getPaidAmount() != null && req.getPaidAmount().signum() > 0 ? "PAID" : "UNPAID";
        }
        p.setProjectId(req.getProjectId());
        p.setNodeCode(req.getNodeCode());
        p.setNodeName(req.getNodeName() == null || req.getNodeName().isBlank() ? req.getNodeCode() : req.getNodeName());
        p.setConditionDesc(req.getConditionDesc());
        p.setPlanAmount(req.getPlanAmount());
        p.setPlanDate(req.getPlanDate());
        p.setPaidAmount(req.getPaidAmount() == null ? java.math.BigDecimal.ZERO : req.getPaidAmount());
        p.setPaidDate(req.getPaidDate());
        p.setStatus(status);
        p.setRemark(req.getRemark());
    }
}
