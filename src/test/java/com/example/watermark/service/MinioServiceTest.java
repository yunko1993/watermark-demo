package com.example.watermark.service;

import io.minio.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.io.*;
import java.time.LocalDate;
import java.time.ZoneId;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MinioServiceTest {
    @Test
    void downloadUsesCurrentDateWithoutWritingBackOriginal() throws Exception {
        MinioClient client = mock(MinioClient.class);
        MinioService service = new MinioService(client, "watermark-demo",
                mock(LibreOfficeLegacyWordWatermarkService.class));
        byte[] source;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage()); doc.save(out); source = out.toByteArray();
        }
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn((long) source.length);
        when(client.statObject(any(StatObjectArgs.class))).thenReturn(stat);
        when(client.getObject(any(GetObjectArgs.class))).thenAnswer(invocation -> {
            GetObjectResponse response = mock(GetObjectResponse.class);
            ByteArrayInputStream input = new ByteArrayInputStream(source);
            when(response.read(any(byte[].class))).thenAnswer(read -> input.read(read.getArgument(0)));
            return response;
        });
        assertArrayEquals(source, service.download("sample.PDF", false));
        byte[] watermarked = service.download("sample.PDF", true);
        try (PDDocument doc = PDDocument.load(watermarked)) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.replaceAll("\\s+", "").contains("添加水印-" + LocalDate.now(ZoneId.of("Asia/Shanghai"))), text);
        }
        assertArrayEquals(source, service.download("sample.PDF", false));
        verify(client, never()).putObject(any(PutObjectArgs.class));
    }

    @Test
    void rejectsFakePdfBeforeContactingMinio() {
        MinioClient client = mock(MinioClient.class);
        MinioService service = new MinioService(client, "watermark-demo",
                mock(LibreOfficeLegacyWordWatermarkService.class));
        assertThrows(IllegalArgumentException.class, () -> service.upload(
                new MockMultipartFile("file", "fake.pdf", "application/pdf", new byte[]{1, 2, 3})));
        assertThrows(IllegalArgumentException.class, () -> service.upload(
                new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> service.upload(
                new MockMultipartFile("file", "fake.doc", "application/msword", new byte[]{1, 2, 3})));
        assertThrows(IllegalArgumentException.class, () -> service.upload(
                new MockMultipartFile("file", "fake.wps", "application/wps-office.wps", new byte[]{1, 2, 3})));
        assertTrue(MinioService.isWord("test.doc"));
        assertTrue(MinioService.isWord("test.wps"));
        assertEquals("application/msword", MinioService.contentType("test.doc"));
        assertEquals("application/wps-office.wps", MinioService.contentType("test.wps"));
        verifyNoInteractions(client);
    }
}
