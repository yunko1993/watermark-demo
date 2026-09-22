package com.example.watermark.util;

import com.aspose.words.Document;
import com.aspose.words.HeaderFooter;
import com.aspose.words.HeaderFooterType;
import com.aspose.words.Paragraph;
import com.aspose.words.RelativeHorizontalPosition;
import com.aspose.words.RelativeVerticalPosition;
import com.aspose.words.SaveFormat;
import com.aspose.words.Section;
import com.aspose.words.Shape;
import com.aspose.words.ShapeType;
import com.aspose.words.WrapType;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Aspose 旧版 Word 水印：处理二进制 DOC，以及内容实际为 DOC 的 .wps 文件。 */
public final class AsposeLegacyWordWatermarkUtil {
    private static final int[] HORIZONTAL_POSITIONS = {20, 200, 380};
    private static final int[] VERTICAL_POSITIONS = {60, 240, 420, 600};
    private static final Color WATERMARK_COLOR = new Color(240, 240, 240);

    private AsposeLegacyWordWatermarkUtil() { }

    /**
     * 按文件内容校验旧 Word 格式，不依赖扩展名判断。
     *
     * @param input DOC 或 DOC 兼容 WPS 原件输入流，由调用方关闭
     * @param wpsExtension 是否使用 .wps 扩展名；当前仅接受内容实际为 DOC 的 WPS 文件
     */
    public static void validate(InputStream input, boolean wpsExtension) {
        byte[] source = readAndValidateContainer(input);
        try {
            new Document(new ByteArrayInputStream(source));
        } catch (Exception e) {
            throw new IllegalArgumentException("DOC/WPS 无法读取，请检查是否损坏、加密或仅修改了扩展名", e);
        }
    }

    /**
     * 在旧 Word 的有效页眉中追加 3 × 4 浅灰斜向水印，返回 DOC 二进制副本。
     *
     * @param input DOC 或 DOC 兼容 WPS 原件输入流，由调用方关闭
     * @param text 服务端生成的水印内容
     * @param wpsExtension 是否使用 .wps 扩展名；输出仍保持原件使用的 DOC 二进制容器
     * @return 保持 DOC 二进制格式的水印副本
     */
    public static byte[] addTextWatermark(InputStream input, String text, boolean wpsExtension) {
        byte[] source = readAndValidateContainer(input);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Document document = new Document(new ByteArrayInputStream(source));
            appendTiledWatermarks(document, text);
            document.save(output, SaveFormat.DOC);
            return output.toByteArray();
        } catch (Exception e) {
            throw new IllegalArgumentException("DOC/WPS 无法处理，请检查文件格式及完整性", e);
        }
    }

    private static void appendTiledWatermarks(Document document, String text) throws Exception {
        Shape template = createTemplate(document, text);
        Set<HeaderFooter> processed = Collections.newSetFromMap(new IdentityHashMap<HeaderFooter, Boolean>());

        for (int sectionIndex = 0; sectionIndex < document.getSections().getCount(); sectionIndex++) {
            Section section = document.getSections().get(sectionIndex);
            appendToEffectiveHeader(document, sectionIndex, HeaderFooterType.HEADER_PRIMARY, template, processed);

            // 兼容性说明：启用首页/奇偶页独立页眉时，水印必须写入对应页眉，否则部分页面不会显示。
            if (section.getPageSetup().getDifferentFirstPageHeaderFooter()) {
                appendToEffectiveHeader(document, sectionIndex, HeaderFooterType.HEADER_FIRST, template, processed);
            }
            if (section.getPageSetup().getOddAndEvenPagesHeaderFooter()) {
                appendToEffectiveHeader(document, sectionIndex, HeaderFooterType.HEADER_EVEN, template, processed);
            }
        }
    }

    private static void appendToEffectiveHeader(Document document, int sectionIndex, int headerType,
                                                Shape template, Set<HeaderFooter> processed) {
        HeaderFooter header = findEffectiveHeader(document, sectionIndex, headerType);
        if (header == null) {
            Section section = document.getSections().get(sectionIndex);
            header = new HeaderFooter(document, headerType);
            section.getHeadersFooters().add(header);
        }
        if (processed.add(header)) {
            appendGrid(document, header, template);
        }
    }

    private static HeaderFooter findEffectiveHeader(Document document, int sectionIndex, int headerType) {
        for (int index = sectionIndex; index >= 0; index--) {
            HeaderFooter header = document.getSections().get(index).getHeadersFooters()
                    .getByHeaderFooterType(headerType);
            if (header != null) {
                return header;
            }
        }
        return null;
    }

    private static Shape createTemplate(Document document, String text) throws Exception {
        Shape template = new Shape(document, ShapeType.TEXT_PLAIN_TEXT);
        template.setWidth(220);
        template.setHeight(38);
        template.setRotation(315);
        template.setBehindText(true);
        template.setAllowOverlap(true);
        template.setWrapType(WrapType.NONE);
        template.setRelativeHorizontalPosition(RelativeHorizontalPosition.PAGE);
        template.setRelativeVerticalPosition(RelativeVerticalPosition.PAGE);
        template.getTextPath().setText(text);
        template.getTextPath().setFontFamily("Microsoft YaHei");
        template.getTextPath().setSize(24);
        template.getFill().setColor(WATERMARK_COLOR);
        template.setStrokeColor(WATERMARK_COLOR);
        template.setStrokeWeight(0.1);
        return template;
    }

    private static void appendGrid(Document document, HeaderFooter header, Shape template) {
        Paragraph paragraph = new Paragraph(document);
        header.appendChild(paragraph);
        int shapeIndex = 0;
        for (int verticalPosition : VERTICAL_POSITIONS) {
            for (int horizontalPosition : HORIZONTAL_POSITIONS) {
                Shape shape = (Shape) template.deepClone(true);
                shape.setName("AsposeWatermark_" + shapeIndex++);
                shape.setLeft(horizontalPosition);
                shape.setTop(verticalPosition);
                paragraph.appendChild(shape);
            }
        }
    }

    private static byte[] readAndValidateContainer(InputStream input) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            byte[] source = output.toByteArray();
            try (POIFSFileSystem fileSystem = new POIFSFileSystem(new ByteArrayInputStream(source))) {
                if (!fileSystem.getRoot().hasEntry("WordDocument")) {
                    throw new IllegalArgumentException("文件不是 Word 97-2003/WPS 二进制文档");
                }
            }
            return source;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("DOC/WPS 无法读取，请检查是否损坏、加密或仅修改了扩展名", e);
        }
    }
}
