package com.example.watermark.util;

import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import java.io.*;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class WordWatermarkUtilTest {
    private static final Pattern POSITION = Pattern.compile(
            "margin-left:([0-9-]+)pt;margin-top:([0-9-]+)pt");

    @Test
    void preservesContentAndCoversExplicitAndInheritedHeaders() throws Exception {
        byte[] original;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("正文内容：Word 水印验证");
            doc.createTable(1, 1).getRow(0).getCell(0).setText("表格保持不变");
            CTSectPr first = doc.createParagraph().getCTP().addNewPPr().addNewSectPr();
            first.addNewTitlePg();
            XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(doc, first);
            policy.createHeader(STHdrFtr.DEFAULT).createParagraph().createRun().setText("原有单位页眉");
            policy.createHeader(STHdrFtr.FIRST).createParagraph().createRun().setText("首页专用页眉");
            policy.createHeader(STHdrFtr.EVEN).createParagraph().createRun().setText("偶数页页眉");
            policy.createFooter(STHdrFtr.DEFAULT).createParagraph().createRun().setText("原有页脚");
            doc.createParagraph().createRun().setText("第二节继承上一节页眉");
            doc.createParagraph().getCTP().addNewPPr().addNewSectPr();
            doc.createParagraph().createRun().setText("第三节独立页眉");
            CTSectPr last = doc.getDocument().getBody().addNewSectPr();
            new XWPFHeaderFooterPolicy(doc, last).createHeader(STHdrFtr.DEFAULT)
                    .createParagraph().createRun().setText("第三节单位页眉");
            doc.write(out); original = out.toByteArray();
        }
        byte[] marked = WordWatermarkUtil.addTextWatermark(new ByteArrayInputStream(original), "添加水印-2026-09-20");
        Path dir = Paths.get("target", "verification"); Files.createDirectories(dir);
        Files.write(dir.resolve("word-original.docx"), original);
        Files.write(dir.resolve("word-watermarked.docx"), marked);
        try (XWPFDocument source = new XWPFDocument(new ByteArrayInputStream(original));
             XWPFDocument result = new XWPFDocument(new ByteArrayInputStream(marked))) {
            assertEquals(source.getParagraphs().size(), result.getParagraphs().size());
            assertEquals(source.getTables().get(0).getText(), result.getTables().get(0).getText());
            assertEquals(source.getFooterList().get(0).getText(), result.getFooterList().get(0).getText());
            assertEquals(4, result.getHeaderList().size());
            Set<String> allShapeIds = new HashSet<>();
            for (int i = 0; i < result.getHeaderList().size(); i++) {
                XWPFHeader header = result.getHeaderList().get(i);
                assertTrue(header.getText().contains(source.getHeaderList().get(i).getText().trim()));
                // 直接检查页眉子树，避免仅匹配序列化字符串而漏掉嵌套 w:p 的非法结构。
                org.w3c.dom.NodeList shapes = ((org.w3c.dom.Element) header._getHdrFtr().getDomNode())
                        .getElementsByTagNameNS("urn:schemas-microsoft-com:vml", "shape");
                assertEquals(12, shapes.getLength());
                Set<String> positions = new HashSet<>();
                for (int shapeIndex = 0; shapeIndex < shapes.getLength(); shapeIndex++) {
                    org.w3c.dom.Element shape = (org.w3c.dom.Element) shapes.item(shapeIndex);
                    assertTrue(allShapeIds.add(shape.getAttribute("id")));
                    String style = shape.getAttribute("style");
                    assertFalse(style.contains("horizontal:center"));
                    assertFalse(style.contains("vertical:center"));
                    assertTrue(style.contains("mso-position-horizontal:absolute"));
                    assertTrue(style.contains("mso-position-horizontal-relative:page"));
                    assertTrue(style.contains("mso-position-vertical:absolute"));
                    assertTrue(style.contains("mso-position-vertical-relative:page"));
                    Matcher position = POSITION.matcher(style);
                    assertTrue(position.find(), style);
                    positions.add(position.group(1) + "," + position.group(2));
                }
                assertEquals(12, positions.size());
                XWPFParagraph added = header.getParagraphs().get(header.getParagraphs().size() - 1);
                assertEquals(1, added.getCTP().sizeOfRArray());
                assertTrue(header._getHdrFtr().xmlText().contains("添加水印-2026-09-20"));
            }
            assertEquals(48, allShapeIds.size());
        }
    }

    @Test
    void createsMissingHeadersAndEscapesText() throws Exception {
        byte[] original;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("无页眉文档"); doc.write(out); original = out.toByteArray();
        }
        byte[] marked = WordWatermarkUtil.addTextWatermark(new ByteArrayInputStream(original), "研发 & 测试 <2026>");
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(marked))) {
            assertEquals(3, doc.getHeaderList().size());
            assertTrue(doc.getHeaderList().get(0)._getHdrFtr().xmlText().contains("&amp;"));
            String headerXml = doc.getHeaderList().get(0)._getHdrFtr().xmlText();
            assertTrue(headerXml.contains("margin-left:20pt"));
            assertTrue(headerXml.contains("margin-top:600pt"));
            assertTrue(headerXml.contains("mso-position-horizontal:absolute"));
            assertFalse(headerXml.contains("mso-position-horizontal:center"));
        }
        assertThrows(Exception.class, () -> WordWatermarkUtil.validate(new ByteArrayInputStream(new byte[]{1, 2, 3})));
    }
}
