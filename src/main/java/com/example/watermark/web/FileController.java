package com.example.watermark.web;

import com.example.watermark.service.MinioService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class FileController {
    private final MinioService service;

    public FileController(MinioService service) { this.service = service; }

    /** 返回当日水印与 MinIO 连通状态；桶不存在时等待首次上传自动创建。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("watermark", service.watermarkText());
        try {
            result.put("bucketExists", service.bucketExists());
            result.put("minioConnected", true);
            result.put("message", "MinIO 已连接，上传时自动创建 Demo 存储桶");
        } catch (Exception e) {
            result.put("minioConnected", false);
            result.put("message", "MinIO 未连接，请检查服务地址、账号和访问权限");
        }
        return result;
    }

    /** 上传 PDF/DOCX/DOC/WPS 原件并返回对象名，供页面后续预览和下载使用。 */
    @PostMapping("/files")
    public Map<String, String> upload(@RequestParam("file") MultipartFile file) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("objectName", service.upload(file));
        result.put("watermark", service.watermarkText());
        return result;
    }

    /** 下载水印副本或原件；仅 PDF 支持浏览器预览，Word 文件始终以附件返回。 */
    @GetMapping("/files/download")
    public ResponseEntity<byte[]> download(@RequestParam String objectName,
                                          @RequestParam(defaultValue = "false") boolean preview,
                                          @RequestParam(defaultValue = "false") boolean original) throws Exception {
        byte[] body = service.download(objectName, !original);
        String name = objectName.substring(objectName.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}\\\\]", "_");
        if (!original) { name = "水印-" + name; }
        ContentDisposition disposition = (preview && !MinioService.isWord(objectName) ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(name, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(MinioService.contentType(objectName)))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .cacheControl(CacheControl.noStore()).contentLength(body.length).body(body);
    }
}
