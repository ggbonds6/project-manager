-- ============================================================
-- V2 种子数据：部门 / 基础字典 / 硬件·软件阶段模板
-- （预置演示用户由 DataInitializer 在应用启动时以 BCrypt 写入）
-- ============================================================

-- 部门
INSERT INTO sys_dept (parent_id, name, order_no) VALUES
(0, '单位本级', 1),
(1, '信息化处', 2),
(1, '财务处', 3),
(1, '综合办公室', 4);

-- 字典：甲方单位
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES
('OWNER_UNIT', 'XX-001', '市政务服务中心', 1),
('OWNER_UNIT', 'XX-002', '市大数据局', 2),
('OWNER_UNIT', 'XX-003', '市教育局', 3),
('OWNER_UNIT', 'XX-004', '市卫健委', 4),
('OWNER_UNIT', 'XX-005', '市公安局', 5);

-- 字典：资金来源
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES
('FUND_SOURCE', 'GOV', '财政拨款', 1),
('FUND_SOURCE', 'SELF', '单位自筹', 2),
('FUND_SOURCE', 'UPPER', '上级补助', 3);

-- 字典：招标方式
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES
('BID_TYPE', 'OPEN', '公开招标', 1),
('BID_TYPE', 'NEGO', '竞争性磋商', 2),
('BID_TYPE', 'NEGO_T', '竞争性谈判', 3),
('BID_TYPE', 'INQUIRY', '询价', 4),
('BID_TYPE', 'SINGLE', '单一来源', 5);

-- 字典：文档类别
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES
('ATTACH_TYPE', 'APPROVAL', '立项批复', 1),
('ATTACH_TYPE', 'FEASIBILITY', '可研报告', 2),
('ATTACH_TYPE', 'BID_DOC', '招标文件', 3),
('ATTACH_TYPE', 'EVAL_RECORD', '评标记录', 4),
('ATTACH_TYPE', 'WIN_NOTICE', '中标通知书', 5),
('ATTACH_TYPE', 'CONTRACT', '合同扫描件', 6),
('ATTACH_TYPE', 'ARRIVAL_DOC', '到货签收单', 7),
('ATTACH_TYPE', 'ACCEPT_REPORT', '验收报告', 8),
('ATTACH_TYPE', 'TEST_REPORT', '测试报告', 9),
('ATTACH_TYPE', 'IMPLEMENT_PLAN', '实施方案', 10),
('ATTACH_TYPE', 'DEBUG_RECORD', '调试记录', 11),
('ATTACH_TYPE', 'PAY_VOUCHER', '付款凭证', 12),
('ATTACH_TYPE', 'COMPLETE_DOC', '竣工资料', 13),
('ATTACH_TYPE', 'SOURCE_CODE', '源代码移交', 14),
('ATTACH_TYPE', 'WARRANTY_DOC', '质保服务记录', 15),
('ATTACH_TYPE', 'OTHER', '其他', 99);

-- 字典：付款节点
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES
('PAY_NODE', 'PREPAY', '预付款', 1),
('PAY_NODE', 'ARRIVAL', '到货款', 2),
('PAY_NODE', 'FIRST_ACCEPT', '初验款', 3),
('PAY_NODE', 'FINAL_ACCEPT', '终验款', 4),
('PAY_NODE', 'WARRANTY', '质保金', 5);

-- 字典：项目来源
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES
('PROJECT_SOURCE', 'UPPER', '上级下发', 1),
('PROJECT_SOURCE', 'SELF', '单位自建', 2),
('PROJECT_SOURCE', 'HORIZONTAL', '横向项目', 3);

-- ============================================================
-- 阶段模板：HW 硬件项目（9 阶段）
-- ============================================================
INSERT INTO phase_template (project_type, phase_name, sort_no, weight, pay_node, description, attach_type_hints, skipable) VALUES
('HW', '立项申报', 1, 5, NULL, '项目批复、资金概算获批', 'APPROVAL,FEASIBILITY,OTHER', 0),
('HW', '招标采购', 2, 10, NULL, '招标/磋商/询价完成并公示', 'BID_DOC,EVAL_RECORD,WIN_NOTICE', 0),
('HW', '合同签订', 3, 5, 'PREPAY', '合同盖章生效', 'CONTRACT,OTHER', 0),
('HW', '到货验收', 4, 20, 'ARRIVAL', '设备到场并签收、核验清单', 'ARRIVAL_DOC,OTHER', 0),
('HW', '安装调试', 5, 20, NULL, '集成部署、联调完成', 'IMPLEMENT_PLAN,DEBUG_RECORD,OTHER', 0),
('HW', '初验', 6, 15, 'FIRST_ACCEPT', '初验通过', 'ACCEPT_REPORT,OTHER', 0),
('HW', '试运行', 7, 5, NULL, '硬件类按需，可跳过', 'OTHER', 1),
('HW', '终验', 8, 15, 'FINAL_ACCEPT', '终验通过、资产移交', 'ACCEPT_REPORT,COMPLETE_DOC,OTHER', 0),
('HW', '质保运维', 9, 5, 'WARRANTY', '质保期内服务、质保金到期支付', 'WARRANTY_DOC,PAY_VOUCHER', 0);

-- ============================================================
-- 阶段模板：SW 软件项目（11 阶段）
-- ============================================================
INSERT INTO phase_template (project_type, phase_name, sort_no, weight, pay_node, description, attach_type_hints, skipable) VALUES
('SW', '立项申报', 1, 5, NULL, '项目批复、预算获批', 'APPROVAL,FEASIBILITY,OTHER', 0),
('SW', '需求调研与规格', 2, 10, NULL, '需求确认、基线化', 'OTHER', 0),
('SW', '招标采购', 3, 10, NULL, '同硬件（自建可跳过）', 'BID_DOC,EVAL_RECORD,WIN_NOTICE', 0),
('SW', '合同签订', 4, 5, 'PREPAY', '合同生效', 'CONTRACT,OTHER', 0),
('SW', '设计与评审', 5, 10, NULL, '概设/详设/库表/接口定稿', 'OTHER', 0),
('SW', '开发实现', 6, 15, NULL, '编码完成、内部提测', 'OTHER', 0),
('SW', '测试与测评', 7, 10, NULL, '系统测试/第三方测评/等保测评通过', 'TEST_REPORT,OTHER', 0),
('SW', '部署上线与试运行', 8, 10, NULL, '上线、用户培训、试运行期满', 'OTHER', 0),
('SW', '初验', 9, 10, 'FIRST_ACCEPT', '初验通过', 'ACCEPT_REPORT,OTHER', 0),
('SW', '终验', 10, 10, 'FINAL_ACCEPT', '终验通过，资料/源代码移交', 'ACCEPT_REPORT,COMPLETE_DOC,SOURCE_CODE,OTHER', 0),
('SW', '质保运维', 11, 5, 'WARRANTY', '质保期服务、质保金支付', 'WARRANTY_DOC,PAY_VOUCHER', 0);
