package org.example.service.parse;

import io.milvus.client.MilvusServiceClient;
import org.example.client.MilvusClientFactory;
import org.example.config.DocumentParseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 按当前解析器解析知识库 collection，避免基线 / Paddle / MinerU 互相覆盖。
 */
@Service
public class KnowledgeCollectionService {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeCollectionService.class);

    private final DocumentParseProperties properties;
    private final MilvusClientFactory milvusClientFactory;
    private final MilvusServiceClient milvusClient;

    public KnowledgeCollectionService(DocumentParseProperties properties,
                                      MilvusClientFactory milvusClientFactory,
                                      MilvusServiceClient milvusClient) {
        this.properties = properties;
        this.milvusClientFactory = milvusClientFactory;
        this.milvusClient = milvusClient;
    }

    public String currentCollection() {
        return properties.knowledgeCollection();
    }

    public String currentEngine() {
        return properties.currentEngine();
    }

    public void ensureCurrent() {
        String collection = currentCollection();
        milvusClientFactory.ensureCollection(
                milvusClient,
                collection,
                "Knowledge collection for parser " + currentEngine());
        logger.debug("知识库 collection 已就绪: {} (parser={})", collection, currentEngine());
    }
}
