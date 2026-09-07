-- ============================================================
-- V5：文件类型字典（FILE_TYPE）—— 与上传白名单、前端标签颜色组保持一致
-- 上传白名单见 AttachmentController.UPLOADABLE_EXTS；
-- 颜色组见 frontend/src/config/tagDict.ts 的 FILE_TYPE_TAGS（文件扩展名 → 颜色组）。
-- 说明：这些扩展名均可上传；其中可在线预览的类型见 docs/附件与预览方案.md，
--       Office/OFD 等暂仅支持下载。
-- ============================================================
INSERT INTO dict_item (dict_type, code, name, sort_no) VALUES
('FILE_TYPE', 'pdf',   'PDF 文档',        1),
('FILE_TYPE', 'doc',   'Word 97-2003',    2),
('FILE_TYPE', 'docx',  'Word 文档',       3),
('FILE_TYPE', 'xls',   'Excel 97-2003',   4),
('FILE_TYPE', 'xlsx',  'Excel 表格',      5),
('FILE_TYPE', 'ppt',   'PowerPoint 97-2003', 6),
('FILE_TYPE', 'pptx',  'PowerPoint 演示', 7),
('FILE_TYPE', 'txt',   '文本文件',        8),
('FILE_TYPE', 'csv',   'CSV 表格',        9),
('FILE_TYPE', 'md',    'Markdown',        10),
('FILE_TYPE', 'log',   '日志文件',        11),
('FILE_TYPE', 'json',  'JSON',            12),
('FILE_TYPE', 'xml',   'XML',             13),
('FILE_TYPE', 'html',  '网页文件',        14),
('FILE_TYPE', 'png',   'PNG 图片',        15),
('FILE_TYPE', 'jpg',   'JPEG 图片',       16),
('FILE_TYPE', 'jpeg',  'JPEG 图片',       17),
('FILE_TYPE', 'gif',   'GIF 动图',        18),
('FILE_TYPE', 'webp',  'WebP 图片',       19),
('FILE_TYPE', 'bmp',   'BMP 图片',        20),
('FILE_TYPE', 'ofd',   'OFD 版式文件',    21),
('FILE_TYPE', 'zip',   'ZIP 压缩包',      22),
('FILE_TYPE', 'rar',   'RAR 压缩包',      23),
('FILE_TYPE', '7z',    '7z 压缩包',       24);
