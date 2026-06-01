package org.example.service.document;

import org.example.config.FileUploadConfig;
import org.example.dto.DocumentChunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 按文件扩展名路由到对应 DocumentProcessor
 */
@Service
public class DocumentProcessorRegistry {

    @Autowired
    private List<DocumentProcessor> processors;

    @Autowired
    private FileUploadConfig fileUploadConfig;

    public DocumentProcessor resolve(String filePath) {
        String extension = getExtension(filePath);
        return processors.stream()
                .filter(p -> p.supports(extension))
                .findFirst()
                .orElseThrow(() -> new UnsupportedOperationException(
                        "不支持的文件类型: ." + extension + "，允许的类型: " + fileUploadConfig.getAllowedExtensions()));
    }

    public List<DocumentChunk> process(Path file) throws Exception {
        return resolve(file.toString()).process(file);
    }

    public boolean isSupportedExtension(String extension) {
        if (extension == null || extension.isEmpty()) {
            return false;
        }
        String allowed = fileUploadConfig.getAllowedExtensions();
        if (allowed == null || allowed.isEmpty()) {
            return false;
        }
        return Arrays.asList(allowed.split(",")).contains(extension.toLowerCase(Locale.ROOT));
    }

    public boolean isSupportedFile(String filePath) {
        return isSupportedExtension(getExtension(filePath));
    }

    private String getExtension(String filePath) {
        String name = Path.of(filePath).getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
