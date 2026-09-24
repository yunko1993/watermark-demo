# Word 原格式水印：实现与验证记录

更新日期：2026-09-21。本文描述当前仓库已实现的方案，运行方式见 [README](../README.md)。

## 当前结论

Java 服务端可以在保持文件格式的前提下，给 DOCX、DOC 和已验证的 DOC 兼容 WPS 文件添加平铺文字水印。业务流程为 MinIO 原件输入流 → 添加水印 → 同格式附件响应，不包含 Word 转 PDF 步骤。

| 格式 | 当前实现 | 验证状态 |
| --- | --- | --- |
| DOCX | Apache POI XWPF，页眉中的 VML 文字形状 | 已验证多节页眉继承、原有内容保留及本地 WPS 平铺显示 |
| DOC | Aspose.Words / Free Spire.Doc / LibreOffice 可选，页眉文字形状 | 已验证真实 Word 97–2003 二进制样本 |
| WPS | Aspose.Words / Free Spire.Doc / LibreOffice 可选，页眉文字形状 | 仅支持 DOC 兼容容器样本；不能外推所有 WPS 历史格式 |

## DOCX 实现

入口为 `WordWatermarkUtil.addTextWatermark`：

1. 读取并校验标准 DOCX 内容类型，拒绝宏文档或模板改名上传。
2. 收集文档各节，对默认、首页、偶数页页眉解析继承关系。
3. 在有效页眉中追加一组 3 × 4 的 VML 文字形状；相同页眉部件只处理一次。
4. 以 DOCX 写出到内存，不覆盖 MinIO 原件。

水印放在正文后方，使用 45° 旋转、12% 不透明度和独立形状 ID。WPS 对 `center` 定位与 margin 偏移的组合存在兼容差异，因此实现使用相对页面的绝对坐标。

实现保留原文件已有形状；如果上传的是此前生成的水印版，会出现旧水印与新水印叠加。这与对同一个无水印 MinIO 原件反复下载不同：后者每次都重新读取原件，不累计叠加。

## DOC / WPS 实现

入口为 Aspose、Spire 或 `LibreOfficeLegacyWordWatermarkService` 三条独立路径：

1. Apache POI POIFS 校验 OLE2 容器及 `WordDocument` 数据流。
2. Aspose/Spire 直接读写 Word97 容器；LibreOffice 路径临时导出 DOCX，使用 POI 插入透明平铺 PNG 页眉形状后再通过 `MS Word 97` 过滤器导回。
3. 在页眉中追加 3 × 4 的文字形状，置于正文后方。
4. 最终保持 DOC/WPS 扩展名与 OLE2 容器，原件不回写。

实际样本分析中，DOC 和 WPS 都包含 `WordDocument` 与 `1Table`，符合 Word 97–2003 二进制兼容结构。这证明此类 `.wps` 文件可处理，不意味着所有使用 `.wps` 后缀的文件均属于同一格式。

Spire 对部分非法输入可能较宽容，所以不能仅以 SDK 未抛出异常作为有效文件的证明。当前代码额外执行容器校验。Demo 将 Spire 调用串行化；生产场景需要进一步评估并发、超时和资源隔离。

## 方案选择依据

| 方案 | 适用范围 | 本仓库选择 |
| --- | --- | --- |
| Apache POI XWPF | DOCX 结构编辑 | 用于 DOCX，不依赖客户端 Office |
| Apache POI HWPF | 旧 DOC 的读取及有限写入 | 未用于 DOC 水印绘图；缺少直接适用的高层水印 API |
| Free Spire.Doc | DOC/WPS 原格式读写、形状操作 | 已集成对比接口，有免费版规模限制 |
| Aspose.Words | 商业 Word 文档处理 | 已集成 DOC 及 DOC 兼容型 WPS 对比接口 |
| LibreOffice Headless | Word97 与 DOCX 格式桥接 | 已集成对比接口；服务端需安装程序、字体并控制超时及并发 |

## 交付前需验证的项目

- 银河麒麟 V3 的准确发行版、CPU 架构、JDK 与可用字体。
- 多页、多节、横竖混排、特殊纸张、首页不同及奇偶页不同的真实文件。
- 添加水印前后的正文、表格、图片、页码、分页和页眉页脚。
- 目标客户端的 Word/WPS 版本及实际打印效果。
- 超过 Free Spire.Doc 免费版 500 段落 / 25 表格范围的处理策略与授权。

当前只有本地环境和已提供样本的验证结果，不能视为所有文档无损或麒麟端已经验收。

## 参考资料

- [Apache POI Word 组件](https://poi.apache.org/components/document/)
- [Spire.Doc Java 示例仓库](https://github.com/eiceblue/Spire.Doc-for-Java)
- [Spire.Doc 多处文字水印示例](https://www.e-iceblue.com/Tutorials/Java/Spire.Doc-for-Java/Program-Guide/Watermark/Java-insert-multiple-text-watermarks-to-Word-document.html)
- [Free Spire.Doc 产品及限制说明](https://www.e-iceblue.com/Introduce/free-doc-for-java.html)
- [Aspose.Words 格式支持](https://docs.aspose.com/words/java/supported-document-formats/)
