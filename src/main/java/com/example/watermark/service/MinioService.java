package com.example.watermark.service;

import com.example.watermark.util.AsposeLegacyWordWatermarkUtil;
import com.example.watermark.util.LegacyWordWatermarkUtil;
import com.example.watermark.util.PdfWatermarkUtil;
import com.example.watermark.util.WordWatermarkUtil;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;

@Service
public class MinioService {
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final String LEGACY_ENGINE_ASPOSE = "aspose";
    private static final String LEGACY_ENGINE_SPIRE = "spire";
    private final MinioClient client;
    private final String bucket;

    public MinioService(MinioClient client, @Value("${minio.bucket}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    /** 获取中国时区的当天水印；每次下载重新生成，避免保存上传日期。 */
    public String watermarkText() {
        return "添加水印-" + LocalDate.now(ZoneId.of("Asia/Shanghai"));
    }

    /** 验证 PDF/DOCX/DOC/WPS 后上传原件，返回唯一对象名；不会提前写入水印。 */
    public String upload(MultipartFile file) throws Exception {
        String name = file.getOriginalFilename();
        if (file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("请选择非空且不超过 20 MB 的 PDF、DOCX、DOC 或 WPS");
        }
        if (!isSupported(name)) {
            throw new IllegalArgumentException("当前仅支持 PDF、DOCX、DOC 和 WPS");
        }
        // 扩展名不能证明文件可解析，先确认内容有效，避免坏文件进入存储桶。
        if (isDocx(name)) {
            try (InputStream in = file.getInputStream()) {
                WordWatermarkUtil.validate(in);
            } catch (IOException e) {
                throw new IllegalArgumentException("DOCX 无法读取，文件可能损坏或加密", e);
            }
        } else if (isLegacyWord(name)) {
            try (InputStream in = file.getInputStream()) {
                AsposeLegacyWordWatermarkUtil.validate(in, isWps(name));
            }
        } else {
            try (InputStream in = file.getInputStream();
                 PDDocument document = PDDocument.load(in, MemoryUsageSetting.setupTempFileOnly())) {
                if (document.isEncrypted() || document.getNumberOfPages() == 0) {
                    throw new IllegalArgumentException("不支持加密或没有页面的 PDF");
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("PDF 无法读取，文件可能损坏或需要密码", e);
            }
        }
        // 仅在用户上传时建桶，应用启动本身不依赖 MinIO 在线。
        ensureBucket();
        String safeName = name.replace('\\', '/');
        safeName = safeName.substring(safeName.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        String objectName = UUID.randomUUID() + "/" + safeName;
        try (InputStream in = file.getInputStream()) {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(objectName)
                    .contentType(contentType(name)).stream(in, file.getSize(), -1).build());
        }
        return objectName;
    }

    /**
     * 读取 MinIO 原件，按 PDF/DOCX/DOC/WPS 类型追加水印并返回同格式副本。
     * @param objectName 桶内对象名
     * @param watermark 是否生成水印；false 用于原件预览对比
     * @return 保留原格式的文件内容，不回写 MinIO
     */
    public byte[] download(String objectName, boolean watermark) throws Exception {
        return download(objectName, watermark, LEGACY_ENGINE_ASPOSE);
    }

    /**
     * 读取 MinIO 原件，并为 DOC/WPS 选择指定的水印引擎，便于在同一份原件上比较处理结果。
     *
     * @param objectName 桶内对象名
     * @param watermark 是否生成水印；false 时直接返回原件
     * @param legacyEngine DOC/WPS 处理引擎，仅支持 aspose 或 spire；其他格式继续使用既有实现
     * @return 保持原格式的文件内容，不回写 MinIO
     */
    public byte[] download(String objectName, boolean watermark, String legacyEngine) throws Exception {
        if (!isSupported(objectName)) {
            throw new IllegalArgumentException("请填写有效的 PDF 或 DOCX 对象名");
        }
        StatObjectResponse stat = client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectName).build());
        if (stat.size() > MAX_BYTES) {
            throw new IllegalArgumentException("当前 Demo 仅处理 20 MB 以内的文件");
        }
        try (InputStream in = client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectName).build());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if ((long) out.size() + count > MAX_BYTES) {
                    throw new IllegalArgumentException("文件超过 20 MB 限制");
                }
                out.write(buffer, 0, count);
            }
            if (!watermark) {
                return out.toByteArray();
            }
            try (InputStream original = new java.io.ByteArrayInputStream(out.toByteArray())) {
                if (isDocx(objectName)) {
                    return WordWatermarkUtil.addTextWatermark(original, watermarkText());
                }
                if (isLegacyWord(objectName)) {
                    // 对比口径：两个引擎读取同一份 MinIO 原件，生成结果均只返回给本次下载。
                    return addLegacyWordWatermark(original, objectName, legacyEngine);
                }
                return PdfWatermarkUtil.addTextWatermark(original, watermarkText());
            } catch (IOException e) {
                throw new IllegalArgumentException("文件无法处理，请检查是否损坏或加密", e);
            }
        }
    }

    private byte[] addLegacyWordWatermark(InputStream original, String objectName, String legacyEngine) {
        String normalizedEngine = legacyEngine == null
                ? LEGACY_ENGINE_ASPOSE
                : legacyEngine.trim().toLowerCase(Locale.ROOT);
        if (LEGACY_ENGINE_ASPOSE.equals(normalizedEngine)) {
            return AsposeLegacyWordWatermarkUtil.addTextWatermark(
                    original, watermarkText(), isWps(objectName));
        }
        if (LEGACY_ENGINE_SPIRE.equals(normalizedEngine)) {
            return LegacyWordWatermarkUtil.addTextWatermark(
                    original, watermarkText(), isWps(objectName));
        }
        throw new IllegalArgumentException("DOC/WPS 水印引擎仅支持 aspose 或 spire");
    }

    /** 检查 MinIO 是否可访问；不创建桶、不暴露凭据。 */
    public boolean bucketExists() throws Exception {
        return client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
    }

    /** 判断是否为任一 Word 下载类型；扩展名用于路由，上传时另行校验实际内容。 */
    public static boolean isWord(String name) {
        return isDocx(name) || isLegacyWord(name);
    }

    /** 判断是否为标准 OOXML DOCX。 */
    public static boolean isDocx(String name) {
        return hasExtension(name, ".docx");
    }

    /** 判断是否为旧二进制 Word/WPS 扩展名。 */
    public static boolean isLegacyWord(String name) {
        return hasExtension(name, ".doc") || isWps(name);
    }

    /** 判断是否使用 WPS 二进制格式读写。 */
    public static boolean isWps(String name) {
        return hasExtension(name, ".wps");
    }

    /** 返回浏览器下载所需的 MIME 类型。 */
    public static String contentType(String name) {
        if (isDocx(name)) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (hasExtension(name, ".doc")) {
            return "application/msword";
        }
        if (isWps(name)) {
            return "application/wps-office.wps";
        }
        return "application/pdf";
    }

    private static boolean isSupported(String name) {
        return isWord(name) || hasExtension(name, ".pdf");
    }

    private static boolean hasExtension(String name, String extension) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(extension);
    }

    private void ensureBucket() throws Exception {
        if (!bucketExists()) {
            try {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            } catch (ErrorResponseException e) {
                // 并发首次上传可能同时建桶，只忽略同一账号已创建成功的情形。
                if (!"BucketAlreadyOwnedByYou".equals(e.errorResponse().code())) {
                    throw e;
                }
            }
        }
    }
}
