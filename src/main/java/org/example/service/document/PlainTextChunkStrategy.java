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
 * 纯文本分片策略：段落 → 字符（不识别 Markdown 标题）
 */
@Component
public class PlainTextChunkStrategy implements ChunkStrategy {

    private static final Logger logger = LoggerFactory.getLogger(PlainTextChunkStrategy.class);

    @Autowired
    private TextChunkHelper textChunkHelper;

    @Override
    public List<DocumentChunk> chunk(List<TextBlock> blocks, String filePath) {
        List<DocumentChunk> chunks = new ArrayList<>();
        if (blocks == null || blocks.isEmpty()) {
            return chunks;
        }

        int globalChunkIndex = 0;
        for (TextBlock block : blocks) {
            if (block.getContent() == null || block.getContent().trim().isEmpty()) {
                continue;
            }
            List<DocumentChunk> blockChunks = textChunkHelper.chunkText(
                    block.getContent(), block.getTitle(), block.getStartIndex(),
                    globalChunkIndex, block.getMetadata());
            chunks.addAll(blockChunks);
            globalChunkIndex += blockChunks.size();
        }

        logger.info("纯文本分片完成: {} -> {} 个分片", filePath, chunks.size());
        return chunks;
    }
}
