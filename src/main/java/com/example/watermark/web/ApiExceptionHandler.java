package com.example.watermark.web;

import io.minio.errors.ErrorResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import java.util.Collections;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 将文件校验失败转换为可供页面展示的提示。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return error(400, e.getMessage());
    }

    /** 明确提示上传大小限制。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge() { return error(413, "文件不能超过 20 MB"); }

    /** 避免缺少参数时被通用异常处理器误报为存储服务故障。 */
    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ResponseEntity<Map<String, String>> missing() { return error(400, "缺少文件或对象名参数"); }

    /** 区分对象不存在与连接、权限等存储异常；详细原因保留在服务日志。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> failure(Exception e) {
        LOG.error("文件处理失败", e);
        if (e instanceof ErrorResponseException) {
            String code = ((ErrorResponseException) e).errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchBucket".equals(code)) {
                return error(404, "文件或存储桶不存在，请先上传 PDF 或检查对象名");
            }
        }
        return error(502, "文件处理失败，请检查本地 MinIO 连接、权限及服务日志");
    }

    private ResponseEntity<Map<String, String>> error(int status, String message) {
        return ResponseEntity.status(status).body(Collections.singletonMap("message", message));
    }
}
