package org.example.service.document;

import org.example.dto.DocumentChunk;
import org.example.dto.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * PDF 分片策略：每个 TextBlock 对应一页，页内超长时再段落 → 字符切分
 */
@Component
public class PdfChunkStrategy implements ChunkStrategy {

    private static final Logger logger = LoggerFactory.getLogger(PdfChunkStrategy.class);

    @Autowired
    private TextChunkHelper textChunkHelper;

    @Override
    public List<DocumentChunk> chunk(List<TextBlock> blocks, String filePath) {
        List<DocumentChunk> chunks = new ArrayList<>();
        if (blocks == null || blocks.isEmpty()) {
            logger.warn("PDF 无有效页面内容: {}", filePath);
            return chunks;
        }

        int globalChunkIndex = 0;
        for (TextBlock pageBlock : blocks) {
            if (pageBlock.getContent() == null || pageBlock.getContent().trim().isEmpty()) {
                continue;
            }

            Object pageNum = pageBlock.getMetadata().get("pageNumber");
            String title = pageBlock.getTitle();
            if (title == null && pageNum != null) {
                title = "第 " + pageNum + " 页";
            }

            List<DocumentChunk> pageChunks = textChunkHelper.chunkText(
                    pageBlock.getContent(), title, pageBlock.getStartIndex(),
                    globalChunkIndex, pageBlock.getMetadata());
            chunks.addAll(pageChunks);
            globalChunkIndex += pageChunks.size();
        }

        logger.info("PDF 分片完成: {} -> {} 个分片", filePath, chunks.size());
        return chunks;
    }
}
