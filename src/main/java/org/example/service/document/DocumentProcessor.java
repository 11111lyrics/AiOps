package org.example.service.document;

import org.example.dto.DocumentChunk;
import org.example.dto.TextBlock;

import java.nio.file.Path;
import java.util.List;

/**
 * 文档处理器：提取层 + 分片层编排
 */
public interface DocumentProcessor {

    boolean supports(String extension);

    List<TextBlock> extract(Path file) throws Exception;

    List<DocumentChunk> process(Path file) throws Exception;
}
