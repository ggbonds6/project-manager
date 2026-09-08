package com.pmgt.module.project.dto;

import java.util.List;

/**
 * 功能模块节点（通用为两级：一级模块 → 子模块）。description 可空。
 */
public class OverviewModuleVO {

    private String name;
    private String description;
    private List<OverviewModuleVO> children;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<OverviewModuleVO> getChildren() { return children; }
    public void setChildren(List<OverviewModuleVO> children) { this.children = children; }
}
