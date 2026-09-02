package com.pmgt.module.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pmgt.common.api.R;
import com.pmgt.common.exception.BizException;
import com.pmgt.common.security.RequireRole;
import com.pmgt.common.security.Role;
import com.pmgt.module.log.service.OperationLogService;
import com.pmgt.module.system.entity.DictItem;
import com.pmgt.module.system.mapper.DictItemMapper;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequireRole({Role.ADMIN})
@RestController
@RequestMapping("/api/admin/dicts")
public class AdminDictController {

    private final DictItemMapper dictItemMapper;
    private final OperationLogService operationLogService;

    public AdminDictController(DictItemMapper dictItemMapper, OperationLogService operationLogService) {
        this.dictItemMapper = dictItemMapper;
        this.operationLogService = operationLogService;
    }

    /** 全部字典类型（编码 + 项数） */
    @GetMapping("/types")
    public R<List<Map<String, Object>>> types() {
        List<DictItem> all = dictItemMapper.selectList(new LambdaQueryWrapper<DictItem>().orderByAsc(DictItem::getDictType));
        return R.ok(all.stream()
                .collect(Collectors.groupingBy(DictItem::getDictType, Collectors.counting()))
                .entrySet().stream()
                .map(e -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("type", e.getKey());
                    row.put("count", e.getValue());
                    return row;
                })
                .toList());
    }

    @GetMapping
    public R<List<DictItem>> list(@RequestParam String type) {
        return R.ok(dictItemMapper.selectList(new LambdaQueryWrapper<DictItem>()
                .eq(DictItem::getDictType, type)
                .orderByAsc(DictItem::getSortNo)));
    }

    @PostMapping
    public R<Long> create(@RequestBody DictItem item) {
        if (!StringUtils.hasText(item.getDictType()) || !StringUtils.hasText(item.getCode()) || !StringUtils.hasText(item.getName())) {
            throw new BizException(400, "类型/编码/名称均不能为空");
        }
        dictItemMapper.insert(item);
        operationLogService.log("DICT", item.getId(), "DICT_CREATE",
                "新增字典项 " + item.getDictType() + "/" + item.getCode() + "=" + item.getName());
        return R.ok(item.getId());
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody DictItem item) {
        DictItem exist = dictItemMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "字典项不存在");
        }
        item.setId(id);
        item.setCreateTime(exist.getCreateTime());
        dictItemMapper.updateById(item);
        operationLogService.log("DICT", id, "DICT_UPDATE", "更新字典项 " + exist.getDictType() + "/" + exist.getCode());
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        DictItem exist = dictItemMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "字典项不存在");
        }
        dictItemMapper.deleteById(id);
        operationLogService.log("DICT", id, "DICT_DELETE", "删除字典项 " + exist.getDictType() + "/" + exist.getCode());
        return R.ok();
    }
}
