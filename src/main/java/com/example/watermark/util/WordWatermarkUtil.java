package com.example.watermark.util;

import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/** DOCX 水印：使用页眉浮动文字，正文和已有页眉页脚保持在原位置。 */
public final class WordWatermarkUtil {
    private static final int[] HORIZONTAL_OFFSETS = {20, 200, 380};
    private static final int[] VERTICAL_OFFSETS = {60, 240, 420, 600};

    private WordWatermarkUtil() { }

    /** 校验实际 OOXML 类型，拒绝改后缀的 DOC、宏文档和模板。 */
    public static void validate(InputStream input) throws IOException {
        try (XWPFDocument document = new XWPFDocument(input)) {
            validateType(document);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("DOCX 无法读取，请检查是否损坏、加密或仅修改了扩展名", e);
        }
    }

    /**
     * 为各节有效页眉追加水印，返回可编辑 DOCX；不修改输入文件。
     * 首次出现的首页/偶数页页眉也补齐，以兼容文档已有的差异页设置。
     * @param input DOCX 原件输入流，由调用方关闭
     * @param text 服务端生成的水印内容
     * @return 带水印的 DOCX 字节
     */
    public static byte[] addTextWatermark(InputStream input, String text) throws IOException {
        try (XWPFDocument document = new XWPFDocument(input);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            validateType(document);
            List<CTSectPr> sections = new ArrayList<>();
            CTBody body = document.getDocument().getBody();
            for (CTP paragraph : body.getPList()) {
                if (paragraph.isSetPPr() && paragraph.getPPr().isSetSectPr()) {
                    sections.add(paragraph.getPPr().getSectPr());
                }
            }
            sections.add(body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr());

            // 页眉未显式指定时继承前一节；共享页眉只追加一次，避免多节重复叠印。
            Map<STHdrFtr.Enum, XWPFHeader> inherited = new HashMap<>();
            Set<String> processed = new HashSet<>();
            int id = 0;
            for (CTSectPr section : sections) {
                XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(document, section);
                for (STHdrFtr.Enum type : new STHdrFtr.Enum[]{STHdrFtr.DEFAULT, STHdrFtr.FIRST, STHdrFtr.EVEN}) {
                    XWPFHeader header = policy.getHeader(type);
                    if (header == null) { header = inherited.get(type); }
                    if (header == null) { header = policy.createHeader(type); }
                    inherited.put(type, header);
                    if (processed.add(header.getPackagePart().getPartName().getName())) {
                        appendTiledWatermark(header, text, ++id);
                    }
                }
            }
            document.write(out);
            return out.toByteArray();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("DOCX 无法处理，请检查文件格式及完整性", e);
        }
    }

    private static void validateType(XWPFDocument document) {
        String contentType = document.getPackagePart().getContentType();
        if (!"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml".equals(contentType)) {
            throw new IllegalArgumentException("仅支持标准 DOCX，不支持宏文档或模板改名上传");
        }
    }

    /**
     * 在页眉中追加相对页面左上角定位的水印矩阵。
     *
     * <p>Word/WPS 会在 center 定位时忽略多个形状的 margin 偏移，因此这里使用页面坐标的
     * absolute 定位。各形状使用唯一 ID，避免办公软件合并或丢弃同名 VML 对象。水印放在
     * 正文后方，不改变正文分页和原页眉内容。</p>
     */
    private static void appendTiledWatermark(XWPFHeader header, String text, int headerIndex) throws IOException {
        String shapeTypeId = "WatermarkType" + headerIndex;
        String shapePrefix = "WatermarkDemo_" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder shapes = new StringBuilder();
        int shapeIndex = 0;
        for (int verticalOffset : VERTICAL_OFFSETS) {
            for (int horizontalOffset : HORIZONTAL_OFFSETS) {
                shapes.append(createWatermarkShape(
                        shapeTypeId, shapePrefix + "_" + ++shapeIndex,
                        text, horizontalOffset, verticalOffset));
            }
        }

        String xml = "<w:p xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
                + "xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:o=\"urn:schemas-microsoft-com:office:office\">"
                + "<w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"1\" w:lineRule=\"exact\"/></w:pPr>"
                + "<w:r><w:rPr><w:noProof/></w:rPr><w:pict>"
                + "<v:shapetype id=\"" + shapeTypeId + "\" coordsize=\"1600,21600\" o:spt=\"136\" "
                + "adj=\"10800\" path=\"m0,0l1600,0m0,21600l1600,21600e\">"
                + "<v:path textpathok=\"t\"/><v:textpath on=\"t\" fitshape=\"t\"/></v:shapetype>"
                + shapes + "</w:pict></w:r></w:p>";
        try {
            // 取 w:p 根元素的内容，避免把整个 XML 文档再嵌套进另一个 w:p。
            CTP paragraph = CTP.Factory.parse(xml,
                    new org.apache.xmlbeans.XmlOptions().setLoadReplaceDocumentElement(null));
            header.createParagraph().getCTP().set(paragraph);
        } catch (org.apache.xmlbeans.XmlException e) {
            throw new IOException("创建 Word 水印形状失败", e);
        }
    }

    private static String createWatermarkShape(String shapeTypeId, String shapeId, String text,
                                               int horizontalOffset, int verticalOffset) {
        return "<v:shape id=\"" + shapeId + "\" type=\"#" + shapeTypeId + "\" "
                + "style=\"position:absolute;left:0;text-align:left;width:220pt;height:38pt;rotation:315;"
                + "z-index:-251654144;visibility:visible;mso-wrap-style:none;mso-wrap-edited:f;"
                + "mso-position-horizontal:absolute;mso-position-horizontal-relative:page;"
                + "mso-position-vertical:absolute;mso-position-vertical-relative:page;"
                + "margin-left:" + horizontalOffset + "pt;margin-top:" + verticalOffset + "pt\" "
                + "fillcolor=\"#808080\" stroked=\"f\" o:allowincell=\"f\">"
                + "<v:fill opacity=\"0.12\"/>"
                + "<v:textpath style=\"font-family:&quot;Microsoft YaHei&quot;;font-size:1pt\" "
                + "string=\"" + escapeXml(text) + "\"/>"
                + "<o:lock v:ext=\"edit\" aspectratio=\"t\"/></v:shape>";
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
