package com.pmgt.module.attach.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pmgt.common.api.R;
import com.pmgt.common.exception.BizException;
import com.pmgt.common.security.AuthContext;
import com.pmgt.common.security.RequireRole;
import com.pmgt.common.security.Role;
import com.pmgt.common.storage.AttachmentStorage;
import com.pmgt.module.attach.dto.AttachmentVO;
import com.pmgt.module.attach.entity.Attachment;
import com.pmgt.module.attach.mapper.AttachmentMapper;
import com.pmgt.module.log.service.OperationLogService;
import com.pmgt.module.project.entity.Payment;
import com.pmgt.module.project.entity.Project;
import com.pmgt.module.project.entity.ProjectPhase;
import com.pmgt.module.project.mapper.PaymentMapper;
import com.pmgt.module.project.mapper.ProjectMapper;
import com.pmgt.module.project.mapper.ProjectPhaseMapper;
import com.pmgt.module.system.entity.SysUser;
import com.pmgt.module.system.mapper.SysUserMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class AttachmentController {

    private static final Set<String> BIZ_TYPES = Set.of("PROJECT_PHASE", "PAYMENT", "PROJECT");

    /** 允许上传的文件扩展名（小写）。在线预览支持矩阵见 docs/附件与预览方案.md；
     *  暂不支持在线预览的类型（Word/Excel/PPT/OFD 等）仍允许上传，仅提供下载。 */
    private static final Set<String> UPLOADABLE_EXTS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "csv", "md", "log", "json", "xml", "html",
            "png", "jpg", "jpeg", "gif", "webp", "bmp",
            "ofd", "zip", "rar", "7z");
    private static final String ALLOWED_EXT_TEXT =
            UPLOADABLE_EXTS.stream().sorted().reduce((a, b) -> a + ", " + b).orElse("");

    private final AttachmentMapper attachmentMapper;
    private final ProjectMapper projectMapper;
    private final ProjectPhaseMapper phaseMapper;
    private final PaymentMapper paymentMapper;
    private final SysUserMapper userMapper;
    private final OperationLogService operationLogService;
    private final AttachmentStorage attachmentStorage;

    public AttachmentController(AttachmentMapper attachmentMapper,
                                ProjectMapper projectMapper,
                                ProjectPhaseMapper phaseMapper,
                                PaymentMapper paymentMapper,
                                SysUserMapper userMapper,
                                OperationLogService operationLogService,
                                AttachmentStorage attachmentStorage) {
        this.attachmentMapper = attachmentMapper;
        this.projectMapper = projectMapper;
        this.phaseMapper = phaseMapper;
        this.paymentMapper = paymentMapper;
        this.userMapper = userMapper;
        this.operationLogService = operationLogService;
        this.attachmentStorage = attachmentStorage;
    }

    @RequireRole({Role.ADMIN, Role.MANAGER})
    @PostMapping("/attachments/upload")
    public R<AttachmentVO> upload(@RequestParam("file") MultipartFile file,
                                  @RequestParam Long projectId,
                                  @RequestParam String bizType,
                                  @RequestParam Long bizId,
                                  @RequestParam(required = false) String attachType) {
        if (file.isEmpty()) {
            throw new BizException(400, "上传文件为空");
        }
        if (!BIZ_TYPES.contains(bizType)) {
            throw new BizException(400, "非法归属类型");
        }
        Project pj = projectMapper.selectById(projectId);
        if (pj == null) {
            throw new BizException(404, "项目不存在");
        }

        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String ext = extOf(original);
        if (!UPLOADABLE_EXTS.contains(ext.toLowerCase(Locale.ROOT))) {
            throw new BizException(400, "不支持的文件类型: ." + ext + "（允许上传：" + ALLOWED_EXT_TEXT + "）");
        }
        String storedName = UUID.randomUUID().toString().replace("-", "") + (ext.isEmpty() ? "" : "." + ext);
        YearMonth ym = YearMonth.now();
        String relKey = ym.getYear() + "/" + String.format("%02d", ym.getMonthValue()) + "/" + storedName;
        try {
            attachmentStorage.save(relKey, file.getInputStream(), file.getSize());
        } catch (Exception e) {
            throw new BizException(500, "文件保存失败: " + e.getMessage());
        }

        Attachment att = new Attachment();
        att.setBizType(bizType);
        att.setBizId(bizId);
        att.setAttachType(attachType);
        att.setFileName(original);
        att.setStoredName(storedName);
        att.setFilePath(relKey);
        att.setFileSize(file.getSize());
        att.setFileExt(ext);
        att.setUploadUserId(AuthContext.userId().orElse(null));
        att.setUploadTime(LocalDateTime.now());
        attachmentMapper.insert(att);

        operationLogService.log("PROJECT", projectId, "ATTACH_UPLOAD",
                "上传附件「" + original + "」(" + bizType + "#" + bizId + ")");
        return R.ok(toVO(att));
    }

    /** 按归属查询附件（阶段卡片附件列表） */
    @GetMapping("/attachments")
    public R<List<AttachmentVO>> listByBiz(@RequestParam String bizType, @RequestParam Long bizId) {
        List<Attachment> list = attachmentMapper.selectList(new LambdaQueryWrapper<Attachment>()
                .eq(Attachment::getBizType, bizType)
                .eq(Attachment::getBizId, bizId)
                .orderByDesc(Attachment::getUploadTime));
        return R.ok(list.stream().map(this::toVO).toList());
    }

    /** 项目附件中心：汇总项目级 + 各阶段 + 付款凭证 */
    @GetMapping("/projects/{projectId}/attachments")
    public R<List<AttachmentVO>> listByProject(@PathVariable Long projectId) {
        Project pj = projectMapper.selectById(projectId);
        if (pj == null) {
            throw new BizException(404, "项目不存在");
        }
        List<ProjectPhase> phases = phaseMapper.selectList(new LambdaQueryWrapper<ProjectPhase>()
                .eq(ProjectPhase::getProjectId, projectId));
        List<Payment> payments = paymentMapper.selectList(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getProjectId, projectId));
        Map<Long, String> phaseNames = phases.stream()
                .collect(Collectors.toMap(ProjectPhase::getId, ProjectPhase::getPhaseName));

        LambdaQueryWrapper<Attachment> qw = new LambdaQueryWrapper<>();
        qw.and(w -> {
            w.eq(Attachment::getBizType, "PROJECT").eq(Attachment::getBizId, projectId);
            if (!phases.isEmpty()) {
                w.or(o -> o.eq(Attachment::getBizType, "PROJECT_PHASE").in(Attachment::getBizId,
                        phases.stream().map(ProjectPhase::getId).toList()));
            }
            if (!payments.isEmpty()) {
                w.or(o -> o.eq(Attachment::getBizType, "PAYMENT").in(Attachment::getBizId,
                        payments.stream().map(Payment::getId).toList()));
            }
        });
        qw.orderByDesc(Attachment::getUploadTime);

        List<Attachment> list = attachmentMapper.selectList(qw);
        List<AttachmentVO> vos = new ArrayList<>();
        for (Attachment a : list) {
            AttachmentVO vo = toVO(a);
            if ("PROJECT_PHASE".equals(a.getBizType())) {
                vo.setPhaseId(a.getBizId());
                vo.setPhaseName(phaseNames.get(a.getBizId()));
            }
            vos.add(vo);
        }
        // 汇总父级总项目公用附件（供子项目共用查看）
        List<Long> ancestors = new ArrayList<>();
        Project cur = pj.getParentId() == null ? null : projectMapper.selectById(pj.getParentId());
        while (cur != null) {
            ancestors.add(cur.getId());
            cur = cur.getParentId() == null ? null : projectMapper.selectById(cur.getParentId());
        }
        if (!ancestors.isEmpty()) {
            List<Attachment> shared = attachmentMapper.selectList(new LambdaQueryWrapper<Attachment>()
                    .eq(Attachment::getBizType, "PROJECT")
                    .in(Attachment::getBizId, ancestors)
                    .orderByDesc(Attachment::getUploadTime));
            for (Attachment a : shared) {
                AttachmentVO vo = toVO(a);
                vo.setPhaseName("总项目公用附件");
                vos.add(vo);
            }
        }
        return R.ok(vos);
    }

    /** 下载/预览：disposition=attachment 下载，inline 内联预览（本地盘 / OBS 统一走存储抽象） */
    @GetMapping("/attachments/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable Long id,
                                             @RequestParam(defaultValue = "attachment") String disposition) {
        Attachment att = attachmentMapper.selectById(id);
        if (att == null) {
            throw new BizException(404, "附件不存在");
        }
        final InputStream in;
        try {
            in = attachmentStorage.open(att.getFilePath());
        } catch (FileNotFoundException e) {
            throw new BizException(404, "附件文件缺失");
        }
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        String ext = att.getFileExt() == null ? "" : att.getFileExt().toLowerCase(Locale.ROOT);
        if (Set.of("png", "jpg", "jpeg", "gif", "webp").contains(ext)) {
            mediaType = MediaType.parseMediaType("image/" + ("jpg".equals(ext) ? "jpeg" : ext));
        } else if ("pdf".equals(ext)) {
            mediaType = MediaType.APPLICATION_PDF;
        }
        String filename = URLEncoder.encode(att.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
        String cd = ("inline".equals(disposition) ? "inline" : "attachment")
                + "; filename*=UTF-8''" + filename;
        StreamingResponseBody body = out -> {
            try (InputStream opened = in) {
                opened.transferTo(out);
            } catch (Exception e) {
                throw new RuntimeException("读取附件失败", e);
            }
        };
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, cd)
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(att.getFileSize()))
                .body(body);
    }

    @RequireRole({Role.ADMIN, Role.MANAGER})
    @DeleteMapping("/attachments/{id}")
    public R<Void> delete(@PathVariable Long id) {
        Attachment att = attachmentMapper.selectById(id);
        if (att == null) {
            throw new BizException(404, "附件不存在");
        }
        attachmentMapper.deleteById(id);
        operationLogService.log("PROJECT", projectIdOf(att), "ATTACH_DELETE",
                "删除附件「" + att.getFileName() + "」(逻辑删除保留文件)");
        return R.ok();
    }

    private Long projectIdOf(Attachment att) {
        return switch (att.getBizType()) {
            case "PROJECT" -> att.getBizId();
            case "PROJECT_PHASE" -> {
                ProjectPhase ph = phaseMapper.selectById(att.getBizId());
                yield ph == null ? null : ph.getProjectId();
            }
            case "PAYMENT" -> {
                Payment pay = paymentMapper.selectById(att.getBizId());
                yield pay == null ? null : pay.getProjectId();
            }
            default -> null;
        };
    }

    private AttachmentVO toVO(Attachment a) {
        AttachmentVO vo = new AttachmentVO();
        vo.setId(a.getId());
        vo.setBizType(a.getBizType());
        vo.setBizId(a.getBizId());
        vo.setAttachType(a.getAttachType());
        vo.setFileName(a.getFileName());
        vo.setFileSize(a.getFileSize());
        vo.setFileExt(a.getFileExt());
        vo.setUploadUserId(a.getUploadUserId());
        if (a.getUploadUserId() != null) {
            SysUser u = userMapper.selectById(a.getUploadUserId());
            vo.setUploadUserName(u == null ? null : u.getName());
        }
        vo.setUploadTime(a.getUploadTime());
        return vo;
    }

    private String extOf(String name) {
        int i = name.lastIndexOf('.');
        if (i < 0 || i == name.length() - 1) {
            return "";
        }
        return name.substring(i + 1);
    }
}
