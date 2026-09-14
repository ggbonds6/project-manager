# samples —— 待测附件目录

把要验证的附件（PDF / 图片）直接放到这个目录，容器内可读，路径是 **`/samples`**。

> ⚠️ **本目录内容不入库**（`.gitignore` 已排除）：附件里可能有身份证号、社保记录等敏感信息。

## 怎么用

```bash
# 1) 先在宿主机放附件（子目录也可以，会递归扫描）
cp <某个项目的附件>/*.pdf samples/

# 2) 附件构成摸底（不需要模型，最先跑）
bash scripts/docker-verify.sh inventory

# 3) 单份识别试（重点看金额、日期、编号 + 低置信行）
bash scripts/docker-verify.sh ocr 中标通知书.pdf --save
bash scripts/docker-verify.sh ocr 身份证扫描件.pdf --dpi 400 --save

# 4) 批量跑（出汇总表与逐份文本）
bash scripts/docker-verify.sh ocr-many --limit 20
```

## 建议先放什么

**先挑三类各一份**，它们能覆盖不同难点：

| 类型 | 举例 | 验证目的 |
| --- | --- | --- |
| 电子版正文 | 项目批复、中标通知书 | 文本层是否可直接用（不走 OCR） |
| 扫描件正文 | 扫描的合同、评标记录 | 通用 OCR 质量 |
| 证件/表格 | 身份证、社保缴纳记录、资格证书 | **最难点**：小字、数字、表格结构 |

跑完后 `work/` 下会有识别文本（`*.ocr.txt`），拿它和原件逐字比对，重点核对**数字**。
