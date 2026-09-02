package com.pmgt.module.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pmgt.common.api.R;
import com.pmgt.module.system.entity.DictItem;
import com.pmgt.module.system.mapper.DictItemMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dicts")
public class DictController {

    private final DictItemMapper dictItemMapper;

    public DictController(DictItemMapper dictItemMapper) {
        this.dictItemMapper = dictItemMapper;
    }

    /** 按字典类型读取全部启用项 */
    @GetMapping
    public R<List<DictItem>> listByType(@RequestParam String type) {
        List<DictItem> list = dictItemMapper.selectList(new LambdaQueryWrapper<DictItem>()
                .eq(DictItem::getDictType, type)
                .orderByAsc(DictItem::getSortNo));
        return R.ok(list);
    }
}
