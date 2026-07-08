package org.example.constant;

public class MilvusConstants {
    
    /**
     * Milvus 数据库名称
     */
    public static final String MILVUS_DB_NAME = "default";
    
    /**
     * Milvus 集合名称
     */
    public static final String MILVUS_COLLECTION_NAME = "biz";

    /**
     * 经验沉淀集合名称（存向量 + 不可变结构化经验，可变生命周期元数据在 MySQL）
     */
    public static final String EXPERIENCE_COLLECTION_NAME = "experience";

    /**
     * 情景记忆集合名称（历史会话逐轮归档，供跨会话检索"之前处理过什么"）
     */
    public static final String EPISODIC_COLLECTION_NAME = "episodic";
    
    /**
     * 向量维度（豆包 embedding 模型的维度）
     */
    public static final int VECTOR_DIM = 1024;  // 豆包模型返回1024维向量
    
    /**
     * ID字段最大长度
     */
    public static final int ID_MAX_LENGTH = 256;
    
    /**
     * Content字段最大长度
     */
    public static final int CONTENT_MAX_LENGTH = 8192;
    
    /**
     * 默认分片数
     */
    public static final int DEFAULT_SHARD_NUMBER = 2;
    
    private MilvusConstants() {
        // 工具类，禁止实例化
    }
}
