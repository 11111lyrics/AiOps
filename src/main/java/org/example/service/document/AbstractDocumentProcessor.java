package org.example.service.document;

import org.example.dto.DocumentChunk;
import org.example.dto.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 文档处理器抽象基类：提取与分片解耦，子类只需实现 extract + 选择 ChunkStrategy
 */
public abstract class AbstractDocumentProcessor implements DocumentProcessor {

    private static final Logger logger = LoggerFactory.getLogger(AbstractDocumentProcessor.class);

    protected abstract ChunkStrategy getChunkStrategy();

    @Override
    public List<DocumentChunk> process(Path file) throws Exception {
        List<TextBlock> blocks = extract(file);
        logger.info("{} 提取完成: {}, 共 {} 个文本块", getClass().getSimpleName(), file, blocks.size());
        return getChunkStrategy().chunk(blocks, file.toString());
    }

    protected TextBlock readPlainText(Path file) throws Exception {
        String content = Files.readString(file);
        return new TextBlock(content);
    }
}
