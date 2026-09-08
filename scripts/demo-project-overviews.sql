-- ============================================================
-- 演示数据：为几个演示项目填充「项目概览」（项目介绍 Markdown + 二级功能模块清单）
-- 说明：仅用于本地演示库；正式部署后在系统界面维护即可。
-- 按项目名称匹配（deleted=0），可重复执行（存在则跳过）。
-- ============================================================

INSERT INTO project_overview (project_id, intro_md, modules_json)
SELECT p.id,
'## 项目背景\n区政府推进智慧园区建设，要求新建园区实现基础设施智能化、安防一体化与能源精细化管理，并为后续智慧应用预留统一平台底座。\n\n## 建设内容\n本工程为“智慧园区”总项目，按 5 个子项目分别实施：楼宇自控(BA)、综合布线、视频监控、一卡通门禁、信息发布与导视；每个子项目独立签订合同、独立验收。\n\n## 总体架构\n感知层（传感/摄像/门禁）→ 网络层（综合布线/物联网）→ 平台层（集成管理平台）→ 应用层（安防、能耗、发布）。\n\n## 应用场景\n- 园区日常安防巡更与应急联动\n- 楼宇设备（空调/照明）自动控制与能耗统计\n- 人员出入与访客管理\n- 公共区域信息发布与导视',
'[{"name":"基础设施","description":"网络与机房底座","children":[{"name":"综合布线系统","description":"光缆+六类双绞线，覆盖楼宇/主干"},{"name":"机房基础设施","description":"UPS、精密空调、动环监控"}]},{"name":"安防子系统","description":"园区安全防范","children":[{"name":"视频监控","description":"枪机/半球/云台与存储回放"},{"name":"一卡通门禁","description":"刷卡/人脸、梯控与访客"},{"name":"信息发布与导视","description":"LCD 发布屏、触摸导视"}]},{"name":"智能集成","description":"设备与平台联动","children":[{"name":"楼宇自控 BA","description":"冷热源/空调/照明/电梯监测"},{"name":"集成管理平台","description":"子系统统一接入与联动策略"}]}]'
FROM project p WHERE p.deleted=0 AND p.name='智慧园区一体化建设（总项目）'
AND NOT EXISTS (SELECT 1 FROM project_overview o WHERE o.project_id=p.id);

INSERT INTO project_overview (project_id, intro_md, modules_json)
SELECT p.id,
'## 项目背景\n为满足政务云业务增长与安全合规要求，在现有云平台基础上扩容资源池并补充灾备与安全能力。\n\n## 建设范围\n总项目包含云资源池扩容、灾备中心建设、安全等保改造三个子项目，独立实施与验收。\n\n## 目标\n- 计算/存储/网络资源扩容，支撑新增业务上云\n- 关键系统同城/异地灾备\n- 通过等级保护测评并落实安全加固',
'[{"name":"云资源池扩容","description":"算力与存储扩容","children":[{"name":"计算资源","description":"服务器与虚拟化扩容"},{"name":"存储资源","description":"块/对象存储扩容"},{"name":"网络资源","description":"交换机/负载均衡扩容"}]},{"name":"灾备能力","description":"业务连续性保障","children":[{"name":"同城容灾","description":"双活/异步复制"},{"name":"备份恢复","description":"数据备份与演练"}]},{"name":"安全防护","description":"等保合规","children":[{"name":"边界防护","description":"防火墙/入侵检测"},{"name":"等保测评","description":"二级测评与整改"}]}]'
FROM project p WHERE p.deleted=0 AND p.name='市政务云平台扩容工程（总项目）'
AND NOT EXISTS (SELECT 1 FROM project_overview o WHERE o.project_id=p.id);

INSERT INTO project_overview (project_id, intro_md, modules_json)
SELECT p.id,
'## 项目背景\n推进全区中小学教育信息化基础设施与应用升级，实现优质教学资源共享与教务管理数字化。\n\n## 主要功能\n覆盖教学、教务、家校协同与教育大数据，面向教师、学生、家长与教育局管理端。\n\n## 应用场景\n- 常态化多媒体教学与线上资源学习\n- 学籍/成绩/选课等教务管理\n- 家长接收通知、作业与成长档案\n- 局端教学质量分析',
'[{"name":"教学应用","description":"教与学核心场景","children":[{"name":"在线课堂","description":"直播/点播与互动教学"},{"name":"资源共享","description":"课件/微课资源中心"},{"name":"作业练习","description":"作业布置批改与错题本"}]},{"name":"教务管理","description":"校务与学籍","children":[{"name":"学籍管理","description":"学生档案与异动"},{"name":"成绩分析","description":"考试成绩与质量报告"},{"name":"选课排课","description":"走班选课与课表"}]},{"name":"家校与数据","description":"连接家长与决策","children":[{"name":"家校沟通","description":"通知/请假/成长档案"},{"name":"教育大数据","description":"局校两级数据看板"}]}]'
FROM project p WHERE p.deleted=0 AND p.name='中小学教育信息化改造（单项目）'
AND NOT EXISTS (SELECT 1 FROM project_overview o WHERE o.project_id=p.id);

INSERT INTO project_overview (project_id, intro_md, modules_json)
SELECT p.id,
'## 项目背景\n对园区楼宇机电设备实施自动监测与控制，实现按需运行与能耗节约，纳入智慧园区集成平台统一管理。\n\n## 覆盖范围\n暖通空调、冷热源、照明、给排水与电梯系统的联网监控，具备策略控制与异常告警。',
'[{"name":"设备监测","description":"机电设备联网采集","children":[{"name":"暖通空调","description":"空调机组/风机盘管状态"},{"name":"冷热源","description":"主机/水泵/冷却塔监测"},{"name":"给排水","description":"水箱水位与水泵状态"}]},{"name":"控制策略","description":"按需自动运行","children":[{"name":"时间表控制","description":"作息/节假日排程"},{"name":"联动策略","description":"温度/能耗触发控制"},{"name":"告警联动","description":"异常告警与工单"}]}]'
FROM project p WHERE p.deleted=0 AND p.name='楼宇自控与BA'
AND NOT EXISTS (SELECT 1 FROM project_overview o WHERE o.project_id=p.id);

INSERT INTO project_overview (project_id, intro_md, modules_json)
SELECT p.id,
'## 项目背景\n为园区办公/公共区域建设统一人员出入与门禁管理体系，支持刷卡与人脸识别，兼容访客与内部人员管理，并提供考勤与消费扩展能力。\n\n## 特点\n与视频监控联动留痕，支持分级授权与远程管控。',
'[{"name":"通行管理","description":"出入控制核心","children":[{"name":"门禁控制","description":"刷卡/人脸/二维码通行"},{"name":"电梯控制","description":"梯控与楼层授权"},{"name":"访客管理","description":"预约/登记/临时授权"}]},{"name":"人员与扩展","description":"组织与增值应用","children":[{"name":"人员档案","description":"人员信息与卡片管理"},{"name":"考勤消费","description":"考勤与一卡通消费"},{"name":"联动记录","description":"门禁-视频联动留痕"}]}]'
FROM project p WHERE p.deleted=0 AND p.name='一卡通门禁'
AND NOT EXISTS (SELECT 1 FROM project_overview o WHERE o.project_id=p.id);

INSERT INTO project_overview (project_id, intro_md, modules_json)
SELECT p.id,
'## 项目背景\n对政务云现有资源池进行扩容，解决计算/存储/网络资源紧张问题，扩容过程不影响存量业务，并具备统一监控与配额管理。\n\n## 说明\n本项目为总项目（市政务云平台扩容工程）子项目之一，独立合同与验收。',
'[{"name":"资源扩容","description":"容量与性能提升","children":[{"name":"计算扩容","description":"新增服务器与虚拟化节点"},{"name":"存储扩容","description":"块存储与对象存储扩容"},{"name":"网络扩容","description":"核心/接入交换机与负载"}]},{"name":"运营管理","description":"扩容后运维保障","children":[{"name":"资源监控","description":"CPU/内存/存储用量监控"},{"name":"配额管理","description":"项目/租户资源配额"},{"name":"迁移服务","description":"存量业务平滑迁移"}]}]'
FROM project p WHERE p.deleted=0 AND p.name='云资源池扩容'
AND NOT EXISTS (SELECT 1 FROM project_overview o WHERE o.project_id=p.id);
