package com.pmgt.module.project.dto;

import java.util.List;

/**
 * 保存项目概览：项目介绍（Markdown）+ 功能模块清单（二级）。
 */
public class OverviewSaveRequest {

    private String introMd;
    private List<OverviewModuleVO> modules;

    public String getIntroMd() { return introMd; }
    public void setIntroMd(String introMd) { this.introMd = introMd; }
    public List<OverviewModuleVO> getModules() { return modules; }
    public void setModules(List<OverviewModuleVO> modules) { this.modules = modules; }
}
