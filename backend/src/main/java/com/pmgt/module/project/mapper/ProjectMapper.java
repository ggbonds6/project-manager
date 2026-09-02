package com.pmgt.module.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pmgt.module.project.entity.Project;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ProjectMapper extends BaseMapper<Project> {

    /**
     * 物理统计指定前缀编号数量（含逻辑删除行），用于生成不撞唯一索引的下一编号。
     */
    @Select("SELECT COUNT(*) FROM project WHERE code LIKE CONCAT(#{like}, '%')")
    long countAllByCodeLike(@Param("like") String like);
}
