package com.example.watermark.util;

import com.spire.doc.Document;
import com.spire.doc.FileFormat;
import com.spire.doc.HeaderFooter;
import com.spire.doc.Section;
import com.spire.doc.fields.ShapeObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class LegacyWordWatermarkUtilTest {
    @Test
    void preservesDocTextAndAddsTwelveShapesToEachHeader() {
        byte[] source = createLegacyDocument(FileFormat.Doc);
        byte[] result = LegacyWordWatermarkUtil.addTextWatermark(
                new ByteArrayInputStream(source), "添加水印-2026-09-21", false);

        assertArrayEquals(new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0},
                Arrays.copyOf(result, 4));
        Document document = new Document(new ByteArrayInputStream(result), FileFormat.Doc);
        try {
            assertTrue(document.getText().contains("旧版 Word 正文保持不变"));
            Section section = document.getSections().get(0);
            assertTrue(countShapes(section.getHeadersFooters().getHeader()) >= 12);
        } finally {
            document.dispose();
        }
    }

    @Test
    void supportsWpsFormatAndRejectsInvalidBinary() {
        byte[] source = createLegacyDocument(FileFormat.Wps);
        LegacyWordWatermarkUtil.validate(new ByteArrayInputStream(source), true);
        byte[] result = LegacyWordWatermarkUtil.addTextWatermark(
                new ByteArrayInputStream(source), "添加水印-2026-09-21", true);

        Document document = new Document(new ByteArrayInputStream(result), FileFormat.Wps);
        try {
            assertTrue(document.getText().contains("旧版 Word 正文保持不变"));
            assertTrue(countShapes(document.getSections().get(0).getHeadersFooters().getHeader()) >= 12);
        } finally {
            document.dispose();
        }
        assertThrows(IllegalArgumentException.class,
                () -> LegacyWordWatermarkUtil.validate(new ByteArrayInputStream(new byte[]{1, 2, 3}), false));
    }

    private static byte[] createLegacyDocument(FileFormat format) {
        Document document = new Document();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addSection().addParagraph().appendText("旧版 Word 正文保持不变");
            document.saveToStream(output, format);
            return output.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } finally {
            document.dispose();
        }
    }

    private static int countShapes(HeaderFooter header) {
        int count = 0;
        for (int paragraphIndex = 0; paragraphIndex < header.getParagraphs().getCount(); paragraphIndex++) {
            for (int objectIndex = 0;
                 objectIndex < header.getParagraphs().get(paragraphIndex).getChildObjects().getCount();
                 objectIndex++) {
                if (header.getParagraphs().get(paragraphIndex).getChildObjects().get(objectIndex)
                        instanceof ShapeObject) {
                    count++;
                }
            }
        }
        return count;
    }
}
