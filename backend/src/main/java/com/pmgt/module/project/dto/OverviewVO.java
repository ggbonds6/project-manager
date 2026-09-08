package com.pmgt.module.project.dto;

import java.util.List;

/**
 * 项目概览 VO：README 式介绍 + 功能模块清单。
 */
public class OverviewVO {

    private String introMd;
    private List<OverviewModuleVO> modules;

    public String getIntroMd() { return introMd; }
    public void setIntroMd(String introMd) { this.introMd = introMd; }
    public List<OverviewModuleVO> getModules() { return modules; }
    public void setModules(List<OverviewModuleVO> modules) { this.modules = modules; }
}
