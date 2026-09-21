package com.example.watermark.util;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class PdfWatermarkUtilTest {
    public static byte[] sample() throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int rotation : new int[]{0, 90, 180, 270}) {
                PDPage page = new PDPage(PDRectangle.A4);
                page.setRotation(rotation);
                page.setCropBox(new PDRectangle(20, 30, 550, 770));
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText(); content.setFont(PDType1Font.HELVETICA, 20);
                    content.newLineAtOffset(60, 700); content.showText("Original content " + rotation); content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void preservesOriginalAndAddsChineseWatermarkToEveryRotatedPage() throws Exception {
        byte[] source = sample();
        byte[] result = PdfWatermarkUtil.addTextWatermark(new ByteArrayInputStream(source), "添加水印-2026-09-20");
        Path dir = Paths.get("target", "verification"); Files.createDirectories(dir);
        Files.write(dir.resolve("original.pdf"), source); Files.write(dir.resolve("watermarked.pdf"), result);
        try (PDDocument original = PDDocument.load(source); PDDocument output = PDDocument.load(result)) {
            assertEquals(4, output.getNumberOfPages());
            assertFalse(new PDFTextStripper().getText(original).contains("添加水印"));
            PDFRenderer renderer = new PDFRenderer(output);
            for (int i = 0; i < 4; i++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(i + 1); stripper.setEndPage(i + 1);
                String text = stripper.getText(output);
                Files.write(dir.resolve("text-" + i + ".txt"), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                ImageIO.write(renderer.renderImageWithDPI(i, 90), "png", dir.resolve("page-" + i + ".png").toFile());
                assertTrue(text.replaceAll("\\s+", "").contains("添加水印-2026-09-20"), text);
                // 倾斜文字、旋转页的提取结果会插入换行，按连续字符验证文本保留。
                assertTrue(text.replaceAll("\\s+", "").contains("Originalcontent" + i * 90), text);
                assertEquals(original.getPage(i).getRotation(), output.getPage(i).getRotation());
                assertEquals(original.getPage(i).getCropBox().toString(), output.getPage(i).getCropBox().toString());
            }
        }
    }

    @Test
    void rejectsInvalidPdf() {
        assertThrows(IOException.class, () -> PdfWatermarkUtil.addTextWatermark(
                new ByteArrayInputStream(new byte[]{1, 2, 3}), "添加水印-2026-09-20"));
    }
}
