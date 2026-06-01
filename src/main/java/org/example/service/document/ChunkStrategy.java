package org.example.service.document;

import org.example.dto.DocumentChunk;
import org.example.dto.TextBlock;

import java.util.List;

/**
 * 分片层策略：将提取层输出的 TextBlock 切分为 DocumentChunk
 */
public interface ChunkStrategy {

    List<DocumentChunk> chunk(List<TextBlock> blocks, String filePath);
}
