package com.example.watermark.util;

import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.*;

/** DOCX 水印：使用页眉浮动文字，正文和已有页眉页脚保持在原位置。 */
public final class WordWatermarkUtil {
    private static final int[] HORIZONTAL_OFFSETS = {20, 200, 380};
    private static final int[] VERTICAL_OFFSETS = {60, 240, 420, 600};
    private static final double LIBREOFFICE_MIN_INFLATE_RATIO = 0.005D;
    private static final double DEFAULT_PAGE_WIDTH_POINTS = 595.3D;
    private static final double DEFAULT_PAGE_HEIGHT_POINTS = 841.9D;
    private static final Object POI_ZIP_SECURITY_LOCK = new Object();

    private WordWatermarkUtil() { }

    /** 校验实际 OOXML 类型，拒绝改后缀的 DOC、宏文档和模板。 */
    public static void validate(InputStream input) throws IOException {
        synchronized (POI_ZIP_SECURITY_LOCK) {
            validateInternal(input);
        }
    }

    private static void validateInternal(InputStream input) throws IOException {
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
        synchronized (POI_ZIP_SECURITY_LOCK) {
            return addTextWatermarkInternal(input, text);
        }
    }

    /**
     * 处理 LibreOffice 生成的受控 DOCX；临时放宽重复文本压缩率，普通上传仍使用 POI 默认阈值。
     */
    public static byte[] addTextWatermarkFromLibreOffice(InputStream input,
                                                          String text) throws IOException {
        synchronized (POI_ZIP_SECURITY_LOCK) {
            double originalRatio = ZipSecureFile.getMinInflateRatio();
            try {
                ZipSecureFile.setMinInflateRatio(LIBREOFFICE_MIN_INFLATE_RATIO);
                return addTiledImageWatermarkInternal(input, text);
            } finally {
                ZipSecureFile.setMinInflateRatio(originalRatio);
            }
        }
    }

    private static byte[] addTextWatermarkInternal(InputStream input,
                                                    String text) throws IOException {
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

    private static byte[] addTiledImageWatermarkInternal(InputStream input,
                                                          String text) throws IOException {
        try (XWPFDocument document = new XWPFDocument(input);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            validateType(document);
            List<CTSectPr> sections = collectSections(document);
            Map<STHdrFtr.Enum, XWPFHeader> inherited = new HashMap<>();
            Set<String> processed = new HashSet<>();
            int id = 0;
            for (CTSectPr section : sections) {
                double[] pageSize = pageSizeInPoints(section);
                XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(document, section);
                for (STHdrFtr.Enum type : new STHdrFtr.Enum[]{STHdrFtr.DEFAULT, STHdrFtr.FIRST, STHdrFtr.EVEN}) {
                    XWPFHeader header = policy.getHeader(type);
                    if (header == null) { header = inherited.get(type); }
                    if (header == null) { header = policy.createHeader(type); }
                    inherited.put(type, header);
                    if (processed.add(header.getPackagePart().getPartName().getName())) {
                        appendTiledImageWatermark(header, text, ++id, pageSize[0], pageSize[1]);
                    }
                }
            }
            document.write(out);
            return out.toByteArray();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("LibreOffice 中间 DOCX 无法处理", e);
        }
    }

    private static List<CTSectPr> collectSections(XWPFDocument document) {
        List<CTSectPr> sections = new ArrayList<>();
        CTBody body = document.getDocument().getBody();
        for (CTP paragraph : body.getPList()) {
            if (paragraph.isSetPPr() && paragraph.getPPr().isSetSectPr()) {
                sections.add(paragraph.getPPr().getSectPr());
            }
        }
        sections.add(body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr());
        return sections;
    }

    private static double[] pageSizeInPoints(CTSectPr section) {
        if (!section.isSetPgSz()) {
            return new double[]{DEFAULT_PAGE_WIDTH_POINTS, DEFAULT_PAGE_HEIGHT_POINTS};
        }
        try {
            BigInteger width = new BigInteger(section.getPgSz().getW().toString());
            BigInteger height = new BigInteger(section.getPgSz().getH().toString());
            return new double[]{width.doubleValue() / 20D, height.doubleValue() / 20D};
        } catch (RuntimeException e) {
            return new double[]{DEFAULT_PAGE_WIDTH_POINTS, DEFAULT_PAGE_HEIGHT_POINTS};
        }
    }

    private static void appendTiledImageWatermark(XWPFHeader header, String text, int headerIndex,
                                                   double pageWidthPoints,
                                                   double pageHeightPoints) throws IOException {
        byte[] image = createTiledWatermarkImage(text, pageWidthPoints, pageHeightPoints);
        try {
            String relationId = header.addPictureData(image, Document.PICTURE_TYPE_PNG);
            String shapeId = "LibreOfficeWatermark_" + headerIndex + "_"
                    + UUID.randomUUID().toString().replace("-", "");
            String xml = "<w:p xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
                    + "xmlns:v=\"urn:schemas-microsoft-com:vml\" "
                    + "xmlns:o=\"urn:schemas-microsoft-com:office:office\" "
                    + "xmlns:w10=\"urn:schemas-microsoft-com:office:word\">"
                    + "<w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"1\" w:lineRule=\"exact\"/></w:pPr>"
                    + "<w:r><w:rPr><w:noProof/></w:rPr><w:pict>"
                    + "<v:rect id=\"" + shapeId + "\" stroked=\"f\" o:allowincell=\"f\" "
                    + "style=\"position:absolute;left:0;top:0;width:"
                    + pointValue(pageWidthPoints) + "pt;height:" + pointValue(pageHeightPoints)
                    + "pt;z-index:-251654144;mso-wrap-style:none;"
                    + "mso-position-horizontal:absolute;mso-position-horizontal-relative:page;"
                    + "mso-position-vertical:absolute;mso-position-vertical-relative:page\">"
                    + "<v:imagedata r:id=\"" + relationId + "\" o:title=\"\"/>"
                    + "<w10:wrap type=\"none\"/><o:lock v:ext=\"edit\" aspectratio=\"t\"/>"
                    + "</v:rect></w:pict></w:r></w:p>";
            CTP paragraph = CTP.Factory.parse(xml,
                    new org.apache.xmlbeans.XmlOptions().setLoadReplaceDocumentElement(null));
            header.createParagraph().getCTP().set(paragraph);
        } catch (InvalidFormatException | org.apache.xmlbeans.XmlException e) {
            throw new IOException("创建 LibreOffice 兼容水印图片失败", e);
        }
    }

    private static byte[] createTiledWatermarkImage(String text, double pageWidthPoints,
                                                    double pageHeightPoints) throws IOException {
        double safeWidth = Math.max(200D, Math.min(pageWidthPoints, 2000D));
        double safeHeight = Math.max(200D, Math.min(pageHeightPoints, 3000D));
        double scale = Math.min(2D, Math.min(2400D / safeWidth, 3400D / safeHeight));
        int width = (int) Math.round(safeWidth * scale);
        int height = (int) Math.round(safeHeight * scale);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Font font = new Font("Microsoft YaHei", Font.PLAIN,
                    Math.max(24, (int) Math.round(24D * scale)));
            if (font.canDisplayUpTo(text) >= 0) {
                font = new Font(Font.DIALOG, Font.PLAIN, font.getSize());
            }
            graphics.setFont(font);
            graphics.setColor(new Color(128, 128, 128));
            graphics.setComposite(AlphaComposite.SrcOver.derive(0.12F));
            FontMetrics metrics = graphics.getFontMetrics();
            for (int row = 0; row < 4; row++) {
                for (int column = 0; column < 3; column++) {
                    double x = width * (column * 2D + 1D) / 6D;
                    double y = height * (row * 2D + 1D) / 8D;
                    AffineTransform original = graphics.getTransform();
                    graphics.rotate(-Math.PI / 4D, x, y);
                    graphics.drawString(text, (float) (x - metrics.stringWidth(text) / 2D),
                            (float) (y + metrics.getAscent() / 2D));
                    graphics.setTransform(original);
                }
            }
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IOException("当前 JRE 不支持 PNG 编码");
            }
            return output.toByteArray();
        }
    }

    private static String pointValue(double points) {
        return String.format(Locale.ROOT, "%.2f", points);
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
