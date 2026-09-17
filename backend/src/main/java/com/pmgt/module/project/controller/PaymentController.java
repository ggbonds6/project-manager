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
import com.pmgt.module.project.service.ContractLinkService;
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
import java.util.Set;

@RestController
@RequestMapping("/api")
public class PaymentController {

    private static final Set<String> NODES = Set.of("PREPAY", "ARRIVAL", "FIRST_ACCEPT", "FINAL_ACCEPT", "WARRANTY");
    private static final Set<String> STATUS = Set.of("UNPAID", "PART", "PAID");

    private final PaymentMapper paymentMapper;
    private final ProjectMapper projectMapper;
    private final ContractLinkService contractLinkService;
    private final OperationLogService operationLogService;

    public PaymentController(PaymentMapper paymentMapper, ProjectMapper projectMapper,
                             ContractLinkService contractLinkService,
                             OperationLogService operationLogService) {
        this.paymentMapper = paymentMapper;
        this.projectMapper = projectMapper;
        this.contractLinkService = contractLinkService;
        this.operationLogService = operationLogService;
    }

    /**
     * 项目(子项目)付款列表：= 该项目自身登记付款 + 其**可见合同链**（自身多份合同 +
     * 父级总项目共享合同）上的付款，保证"子项目各有主合同/监理/测评合同"与
     * "父级共享合同"两种口径都能看到对应里程碑。
     */
    @GetMapping("/projects/{projectId}/payments")
    public R<List<PaymentVO>> listByProject(@PathVariable Long projectId) {
        Project pj = projectMapper.selectById(projectId);
        if (pj == null) {
            throw new BizException(404, "项目不存在");
        }
        Set<Long> contractIds = contractLinkService.visibleContractIds(projectId);
        LambdaQueryWrapper<Payment> qw = new LambdaQueryWrapper<>();
        if (contractIds.isEmpty()) {
            qw.eq(Payment::getProjectId, projectId);
        } else {
            qw.and(w -> w.eq(Payment::getProjectId, projectId)
                    .or(o -> o.in(Payment::getContractId, contractIds)));
        }
        qw.orderByAsc(Payment::getId);
        return R.ok(paymentMapper.selectList(qw).stream().map(PaymentVO::from).toList());
    }

    @RequireRole({Role.ADMIN})
    @PostMapping("/payments")
    public R<Long> create(@Valid @RequestBody PaymentSaveRequest req) {
        Project pj = projectMapper.selectById(req.getProjectId());
        if (pj == null) {
            throw new BizException(404, "项目不存在");
        }
        Long contractId = resolveContractId(req, pj);
        Payment p = new Payment();
        apply(p, req);
        p.setContractId(contractId);
        paymentMapper.insert(p);
        operationLogService.log("PROJECT", pj.getId(), "PAYMENT_ADD",
                "新增付款记录「" + p.getNodeName() + "」计划 " + p.getPlanAmount() + " 元（合同 #" + contractId + "）");
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
        if (req.getContractId() != null && exist.getContractId() != null
                && !exist.getContractId().equals(req.getContractId())) {
            throw new BizException(400, "付款记录归属合同不允许变更，请删除后重建");
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

    /**
     * 解析付款归属合同：
     * ① 请求显式指定 → 校验必须是该项目**可见**的合同（防张冠李戴）；
     * ② 未指定 → 取项目主合同（project.contract_id，由 ContractLinkService 维护）；
     * ③ 仍没有 → 若该项目可见合同只有一份则用它；多份则要求明确选择。
     */
    private Long resolveContractId(PaymentSaveRequest req, Project pj) {
        Set<Long> visible = contractLinkService.visibleContractIds(pj.getId());
        if (req.getContractId() != null) {
            if (!visible.isEmpty() && !visible.contains(req.getContractId())) {
                throw new BizException(400, "所选合同不属于该项目，请重新选择");
            }
            return req.getContractId();
        }
        if (pj.getContractId() != null) {
            return pj.getContractId();
        }
        if (visible.size() == 1) {
            return visible.iterator().next();
        }
        if (visible.size() > 1) {
            throw new BizException(400, "该项目有多份合同，请在付款记录中明确选择所属合同");
        }
        throw new BizException(400, "该项目尚未登记/关联合同，请先创建合同并关联后再登记付款");
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
        // 付款过程信息（V11）：资金情况以"付款"为主线，这些字段是付款留痕
        p.setPayMethod(normalize(req.getPayMethod()));
        p.setHandler(req.getHandler());
        p.setInvoiceNo(req.getInvoiceNo());
        p.setVoucherNo(req.getVoucherNo());
        p.setPayeeName(req.getPayeeName());
        p.setPayeeBank(req.getPayeeBank());
        p.setPayeeAccount(req.getPayeeAccount());
    }

    /** 付款方式：空串归一为 null；非法值不拦（字典可扩展），仅做去空格 */
    private String normalize(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
