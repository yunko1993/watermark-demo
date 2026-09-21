package com.example.watermark.util;

import com.spire.doc.Document;
import com.spire.doc.FileFormat;
import com.spire.doc.HeaderFooter;
import com.spire.doc.HeadersFooters;
import com.spire.doc.Section;
import com.spire.doc.documents.HorizontalOrigin;
import com.spire.doc.documents.Paragraph;
import com.spire.doc.documents.ShapeType;
import com.spire.doc.documents.TextWrappingStyle;
import com.spire.doc.documents.VerticalOrigin;
import com.spire.doc.fields.ShapeObject;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** 旧版 Word 水印：处理二进制 DOC，以及内容实际为 DOC/WPS 的 .wps 文件。 */
public final class LegacyWordWatermarkUtil {
    private static final int[] HORIZONTAL_POSITIONS = {20, 200, 380};
    private static final int[] VERTICAL_POSITIONS = {60, 240, 420, 600};
    private static final Color WATERMARK_COLOR = new Color(240, 240, 240);
    private static final Object SPIRE_LOCK = new Object();

    private LegacyWordWatermarkUtil() { }

    /**
     * 按文件内容校验旧 Word 格式，不依赖扩展名判断。
     *
     * @param input DOC/WPS 原件输入流，由调用方关闭
     * @param wpsExtension true 时按 WPS 兼容格式读写，false 时按 DOC 读写
     */
    public static void validate(InputStream input, boolean wpsExtension) {
        byte[] source = readAndValidateContainer(input);
        synchronized (SPIRE_LOCK) {
            Document document = null;
            try {
                document = new Document(new ByteArrayInputStream(source), format(wpsExtension));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("DOC/WPS 无法读取，请检查是否损坏、加密或仅修改了扩展名", e);
            } finally {
                dispose(document);
            }
        }
    }

    /**
     * 在旧 Word 的各类页眉中追加 3 × 4 浅灰斜向水印，正文和原件均不回写。
     *
     * @param input DOC/WPS 原件输入流，由调用方关闭
     * @param text 服务端生成的水印内容
     * @param wpsExtension true 时按 WPS 兼容格式读写，false 时按 DOC 读写
     * @return 保持原二进制格式的水印副本
     */
    public static byte[] addTextWatermark(InputStream input, String text, boolean wpsExtension) {
        byte[] source = readAndValidateContainer(input);
        synchronized (SPIRE_LOCK) {
            Document document = null;
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                FileFormat format = format(wpsExtension);
                document = new Document(new ByteArrayInputStream(source), format);
                appendTiledWatermarks(document, text);
                document.saveToStream(output, format);
                return output.toByteArray();
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("DOC/WPS 无法处理，请检查文件格式及完整性", e);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("生成 DOC/WPS 水印副本失败", e);
            } finally {
                dispose(document);
            }
        }
    }

    private static void appendTiledWatermarks(Document document, String text) {
        ShapeObject template = createTemplate(document, text);
        Set<HeaderFooter> processed = Collections.newSetFromMap(new IdentityHashMap<HeaderFooter, Boolean>());
        for (int sectionIndex = 0; sectionIndex < document.getSections().getCount(); sectionIndex++) {
            Section section = document.getSections().get(sectionIndex);
            HeadersFooters headers = section.getHeadersFooters();
            for (HeaderFooter header : Arrays.asList(headers.getHeader(), headers.getFirstPageHeader(),
                    headers.getEvenHeader(), headers.getOddHeader())) {
                if (header != null && processed.add(header)) {
                    appendGrid(header, template);
                }
            }
        }
    }

    private static ShapeObject createTemplate(Document document, String text) {
        ShapeObject template = new ShapeObject(document, ShapeType.Text_Plain_Text);
        template.setWidth(220);
        template.setHeight(38);
        template.setRotation(315);
        template.setBehindText(true);
        template.setTextWrappingStyle(TextWrappingStyle.Behind);
        template.setHorizontalOrigin(HorizontalOrigin.Page);
        template.setVerticalOrigin(VerticalOrigin.Page);
        template.getWordArt().setText(text);
        template.getWordArt().setFontFamily("Microsoft YaHei");
        template.getWordArt().setSize(24);
        // 旧 DOC 对透明色兼容不一致，使用等效浅灰并置于正文后方。
        template.setFillColor(WATERMARK_COLOR);
        template.setFillTransparency(0);
        template.setStrokeColor(WATERMARK_COLOR);
        template.setStrokeWeight(0.1);
        return template;
    }

    private static void appendGrid(HeaderFooter header, ShapeObject template) {
        Paragraph paragraph = header.addParagraph();
        for (int verticalPosition : VERTICAL_POSITIONS) {
            for (int horizontalPosition : HORIZONTAL_POSITIONS) {
                ShapeObject shape = (ShapeObject) template.deepClone();
                shape.setHorizontalPosition(horizontalPosition);
                shape.setVerticalPosition(verticalPosition);
                paragraph.getChildObjects().add(shape);
            }
        }
    }

    private static FileFormat format(boolean wpsExtension) {
        return wpsExtension ? FileFormat.Wps : FileFormat.Doc;
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

    private static void dispose(Document document) {
        if (document != null) {
            document.dispose();
        }
    }
}
