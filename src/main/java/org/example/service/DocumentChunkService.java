package org.example.service;

import org.example.dto.DocumentChunk;
import org.example.service.document.DocumentProcessorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * 文档分片服务（门面）
 * 委托 DocumentProcessorRegistry 按文件类型路由到提取层 + 分片层
 */
@Service
public class DocumentChunkService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentChunkService.class);

    @Autowired
    private DocumentProcessorRegistry processorRegistry;

    /**
     * 按文件路径自动选择处理器并完成分片
     */
    public List<DocumentChunk> chunkDocument(Path filePath) throws Exception {
        logger.info("开始文档分片: {}", filePath);
        return processorRegistry.process(filePath);
    }
}
