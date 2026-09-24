package com.example.watermark.service;

import com.example.watermark.util.WordWatermarkUtil;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 使用 LibreOffice 格式桥接，为 Word97 兼容的 DOC/WPS 添加页眉平铺图片水印。 */
@Service
public class LibreOfficeLegacyWordWatermarkService {
    private static final String DOCX_FILTER = "docx:Office Open XML Text";
    private static final String WORD_97_FILTER = "MS Word 97";
    private static final long MAX_OUTPUT_BYTES = 50L * 1024 * 1024;
    private static final long MAX_DOCX_ENTRY_BYTES = 50L * 1024 * 1024;
    private static final long MAX_DOCX_EXPANDED_BYTES = 100L * 1024 * 1024;

    private final String executable;
    private final long timeoutSeconds;

    public LibreOfficeLegacyWordWatermarkService(
            @Value("${libreoffice.executable}") String executable,
            @Value("${libreoffice.timeout-seconds:90}") long timeoutSeconds) {
        this.executable = executable;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 将 DOC/WPS 临时转换为 DOCX，复用现有 POI 水印后再导回原扩展名。
     *
     * @param input DOC/WPS 原件输入流，由调用方关闭
     * @param text 水印文字
     * @param wpsExtension 是否以 WPS 扩展名导出 Word97 兼容流
     * @return 保持 DOC/WPS 扩展名及 OLE2 容器的水印副本
     */
    public byte[] addTextWatermark(InputStream input, String text,
                                   boolean wpsExtension) throws IOException {
        Path workspace = Files.createTempDirectory("watermark-libreoffice-").toAbsolutePath().normalize();
        try {
            Path sourceDirectory = Files.createDirectories(workspace.resolve("source"));
            Path docxDirectory = Files.createDirectories(workspace.resolve("docx"));
            Path outputDirectory = Files.createDirectories(workspace.resolve("output"));
            String sourceExtension = wpsExtension ? ".wps" : ".doc";
            Path source = sourceDirectory.resolve("source" + sourceExtension);
            Files.copy(input, source, StandardCopyOption.REPLACE_EXISTING);

            // WPS 扩展名存在多种历史格式，明确按当前项目支持的 Word97 兼容流导入。
            convert(workspace, "import-profile", source, docxDirectory,
                    DOCX_FILTER, wpsExtension ? WORD_97_FILTER : null);
            Path convertedDocx = requireOutput(docxDirectory.resolve("source.docx"), "转换 DOCX");
            validateConvertedDocx(convertedDocx);

            Path watermarkedDocx = workspace.resolve("watermarked.docx");
            try (InputStream docxInput = Files.newInputStream(convertedDocx)) {
                Files.write(watermarkedDocx,
                        WordWatermarkUtil.addTextWatermarkFromLibreOffice(docxInput, text));
            }

            String outputExtension = wpsExtension ? ".wps" : ".doc";
            convert(workspace, "export-profile", watermarkedDocx, outputDirectory,
                    outputExtension.substring(1) + ":" + WORD_97_FILTER, null);
            Path output = requireOutput(outputDirectory.resolve("watermarked" + outputExtension),
                    "导回 Word97");
            validateOutput(output);
            return Files.readAllBytes(output);
        } finally {
            deleteRecursively(workspace);
        }
    }

    private void convert(Path workspace, String profileName, Path input, Path outputDirectory,
                         String outputFilter, String inputFilter) throws IOException {
        Path profile = Files.createDirectories(workspace.resolve(profileName));
        Path log = workspace.resolve(profileName + ".log");
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("--headless");
        command.add("--nologo");
        command.add("--nodefault");
        command.add("--nofirststartwizard");
        command.add("--norestore");
        command.add("-env:UserInstallation=" + profile.toUri());
        if (inputFilter != null) {
            command.add("--infilter=" + inputFilter);
        }
        command.add("--convert-to");
        command.add(outputFilter);
        command.add("--outdir");
        command.add(outputDirectory.toString());
        command.add(input.toString());

        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
        } catch (IOException e) {
            throw new IllegalArgumentException("无法启动 LibreOffice，请检查 libreoffice.executable 配置", e);
        }
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalArgumentException("LibreOffice 处理超时（" + timeoutSeconds + " 秒）");
            }
            if (process.exitValue() != 0) {
                throw new IllegalArgumentException("LibreOffice 转换失败：" + readLog(log));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("LibreOffice 处理被中断", e);
        }
    }

    private Path requireOutput(Path output, String stage) throws IOException {
        if (!Files.isRegularFile(output) || Files.size(output) == 0) {
            throw new IllegalArgumentException("LibreOffice " + stage + "失败，未生成输出文件");
        }
        if (Files.size(output) > MAX_OUTPUT_BYTES) {
            throw new IllegalArgumentException("LibreOffice 输出超过 50 MB 限制");
        }
        return output;
    }

    private void validateOutput(Path output) throws IOException {
        try (InputStream input = Files.newInputStream(output);
             POIFSFileSystem fileSystem = new POIFSFileSystem(input)) {
            if (!fileSystem.getRoot().hasEntry("WordDocument")) {
                throw new IllegalArgumentException("LibreOffice 输出不是 Word97 兼容的 DOC/WPS 文件");
            }
        }
    }

    private void validateConvertedDocx(Path docx) throws IOException {
        long expandedBytes = 0;
        try (ZipFile archive = new ZipFile(docx.toFile())) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                long size = entry.getSize();
                if (size < 0 || size > MAX_DOCX_ENTRY_BYTES) {
                    throw new IllegalArgumentException("LibreOffice 中间 DOCX 包含过大的文件项");
                }
                expandedBytes += size;
                if (expandedBytes > MAX_DOCX_EXPANDED_BYTES) {
                    throw new IllegalArgumentException("LibreOffice 中间 DOCX 解压后超过 100 MB 限制");
                }
            }
        }
    }

    private String readLog(Path log) {
        try {
            String message = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).trim();
            return message.length() > 2000 ? message.substring(0, 2000) : message;
        } catch (IOException e) {
            return "无法读取转换日志";
        }
    }

    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时文件清理由操作系统兜底，不覆盖本次文档处理结果。
                }
            });
        } catch (IOException ignored) {
            // 临时目录清理失败不影响已经生成的返回内容。
        }
    }
}
