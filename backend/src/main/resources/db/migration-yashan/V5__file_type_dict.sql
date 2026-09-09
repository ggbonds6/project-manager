-- ============================================================
-- V5 (yashan)：文件类型字典（FILE_TYPE）
-- 由 MySQL 版 V5__file_type_dict.sql 转换（多行 VALUES 拆为逐行 INSERT）。
-- 与上传白名单、前端标签颜色组保持一致：
--   上传白名单见 AttachmentController.UPLOADABLE_EXTS；
--   颜色组见 frontend/src/config/tagDict.ts 的 FILE_TYPE_TAGS。
-- ============================================================
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES ('FILE_TYPE', 'pdf',  'PDF 文档', 1);
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES ('FILE_TYPE', 'doc',  'Word 97-2003', 2);
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES ('FILE_TYPE', 'docx', 'Word 文档', 3);
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES ('FILE_TYPE', 'xls',  'Excel 97-2003', 4);
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES ('FILE_TYPE', 'xlsx', 'Excel 表格', 5);
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES ('FILE_TYPE', 'ppt',  'PowerPoint 97-2003', 6);
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES ('FILE_TYPE', 'pptx', 'PowerPoint 演示', 7);
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES ('FILE_TYPE', 'txt',  '文本文件', 8);
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES ('FILE_TYPE', 'csv',  'CSV 表格', 9);
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES ('FILE_TYPE', 'md',   'Markdown', 10);
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES ('FILE_TYPE', 'log',  '日志文件', 11);
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES ('FILE_TYPE', 'json', 'JSON', 12);
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES ('FILE_TYPE', 'xml',  'XML', 13);
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES ('FILE_TYPE', 'html', '网页文件', 14);
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES ('FILE_TYPE', 'png',  'PNG 图片', 15);
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES ('FILE_TYPE', 'jpg',  'JPEG 图片', 16);
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES ('FILE_TYPE', 'jpeg', 'JPEG 图片', 17);
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES ('FILE_TYPE', 'gif',  'GIF 动图', 18);
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES ('FILE_TYPE', 'webp', 'WebP 图片', 19);
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES ('FILE_TYPE', 'bmp',  'BMP 图片', 20);
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES ('FILE_TYPE', 'ofd',  'OFD 版式文件', 21);
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES ('FILE_TYPE', 'zip',  'ZIP 压缩包', 22);
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES ('FILE_TYPE', 'rar',  'RAR 压缩包', 23);
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES ('FILE_TYPE', '7z',   '7z 压缩包', 24);
