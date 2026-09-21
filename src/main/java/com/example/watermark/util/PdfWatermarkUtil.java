package com.example.watermark.util;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** PDF 文字水印：中文字体、10% 不透明度、45° 平铺水印。 */
public final class PdfWatermarkUtil {
    private PdfWatermarkUtil() { }

    /**
     * 在每页上方追加水印，返回新 PDF；不修改原件，输入流由调用方关闭。
     * @param inputStream 原始 PDF
     * @param watermarkText 本次下载的完整水印内容
     * @return 含水印的 PDF 字节
     */
    public static byte[] addTextWatermark(InputStream inputStream, String watermarkText) throws IOException {
        try (PDDocument document = PDDocument.load(inputStream, MemoryUsageSetting.setupTempFileOnly());
             InputStream fontStream = PdfWatermarkUtil.class.getResourceAsStream("/fonts/WenQuanZhengHei.ttf");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (document.isEncrypted()) {
                throw new IllegalArgumentException("暂不支持加密 PDF，请先解除加密");
            }
            if (fontStream == null) {
                throw new IOException("未找到中文字体 fonts/WenQuanZhengHei.ttf");
            }
            PDFont font = PDType0Font.load(document, fontStream);
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant(0.1f);
            gs.setStrokingAlphaConstant(0.1f);

            for (PDPage page : document.getPages()) {
                try (PDPageContentStream content = new PDPageContentStream(document, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    content.setGraphicsStateParameters(gs);
                    content.setNonStrokingColor(Color.DARK_GRAY);
                    // 兼容裁剪区域偏移及旋转页：先把坐标系转换为阅读器中的可见方向。
                    PDRectangle box = page.getCropBox();
                    int rotation = (page.getRotation() % 360 + 360) % 360;
                    float width = box.getWidth();
                    float height = box.getHeight();
                    content.transform(Matrix.getTranslateInstance(box.getLowerLeftX(), box.getLowerLeftY()));
                    if (rotation == 90) {
                        content.transform(new Matrix(0, 1, -1, 0, width, 0));
                    } else if (rotation == 180) {
                        content.transform(new Matrix(-1, 0, 0, -1, width, height));
                    } else if (rotation == 270) {
                        content.transform(new Matrix(0, -1, 1, 0, 0, height));
                    }
                    if (rotation == 90 || rotation == 270) {
                        width = box.getHeight();
                        height = box.getWidth();
                    }
                    for (float y = -height; y < height * 2; y += 300f) {
                        for (float x = -width; x < width * 2; x += 350f) {
                            content.beginText();
                            content.setFont(font, 30f);
                            content.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(45), x, y));
                            content.showText(watermarkText);
                            content.endText();
                        }
                    }
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
