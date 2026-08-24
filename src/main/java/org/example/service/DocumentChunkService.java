package org.example.service;

import org.example.dto.DocumentChunk;
import org.example.dto.TextBlock;
import org.example.service.document.DocumentProcessorRegistry;
import org.example.service.document.MarkdownChunkStrategy;
import org.example.service.parse.DocumentParseService;
import org.example.service.parse.ParseOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档分片服务（门面）
 * 知识库入库：可选先走本地解析器得到 Markdown，再按标题分片；否则按文件类型走 PDFBox/Tika。
 */
@Service
public class DocumentChunkService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentChunkService.class);

    public static final String META_PARSER = "_parser";
    public static final String META_PARSER_REQUESTED = "_parser_requested";
    public static final String META_PARSER_FALLBACK = "_parser_fallback";
    public static final String META_PARSER_UNSUPPORTED = "_parser_unsupported";

    @Autowired
    private DocumentProcessorRegistry processorRegistry;

    @Autowired
    private DocumentParseService documentParseService;

    @Autowired
    private MarkdownChunkStrategy markdownChunkStrategy;

    /**
     * 按文件路径自动选择处理器并完成分片
     */
    public List<DocumentChunk> chunkDocument(Path filePath) throws Exception {
        logger.info("开始文档分片: {}", filePath);
        ParseOutcome outcome = documentParseService.tryParse(filePath);
        List<DocumentChunk> chunks;
        if (outcome.hasMarkdown()) {
            TextBlock block = new TextBlock(outcome.getMarkdown());
            chunks = markdownChunkStrategy.chunk(List.of(block), filePath.toString());
            logger.info("使用 {} 解析结果分片: {} -> {} 个分片",
                    outcome.getParserId(), filePath.getFileName(), chunks.size());
        } else {
            chunks = processorRegistry.process(filePath);
            if (outcome.getStatus() == ParseOutcome.Status.FALLBACK) {
                logger.warn("解析回退基线提取: {} ({})", filePath.getFileName(), outcome.getMessage());
            }
        }
        stampParserMetadata(chunks, outcome);
        return chunks;
    }

    private void stampParserMetadata(List<DocumentChunk> chunks, ParseOutcome outcome) {
        if (chunks == null) {
            return;
        }
        for (DocumentChunk chunk : chunks) {
            Map<String, Object> extra = chunk.getExtraMetadata();
            if (extra == null) {
                extra = new HashMap<>();
                chunk.setExtraMetadata(extra);
            }
            extra.put(META_PARSER, outcome.getParserId());
            extra.put(META_PARSER_REQUESTED, outcome.getRequestedEngine());
            extra.put(META_PARSER_FALLBACK, outcome.shouldRetryOnNextSync());
            extra.put(META_PARSER_UNSUPPORTED, outcome.getStatus() == ParseOutcome.Status.UNSUPPORTED);
        }
    }
}
