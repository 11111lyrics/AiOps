package org.example.config;

import org.example.service.VectorIndexService;
import org.example.service.parse.DocumentParseService;
import org.example.service.parse.KnowledgeCollectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * 启动时扫描 aiops-docs，与 Milvus 对齐：新增入库、删除去向量、文件变化则重建。
 */
@Component
public class AiopsDocsInitializer implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(AiopsDocsInitializer.class);

    private final VectorIndexService vectorIndexService;
    private final DocumentParseService documentParseService;
    private final KnowledgeCollectionService knowledgeCollectionService;

    @Value("${aiops.docs.auto-import:true}")
    private boolean autoImport;

    @Value("${aiops.docs.path:./aiops-docs}")
    private String docsPath;

    public AiopsDocsInitializer(VectorIndexService vectorIndexService,
                                DocumentParseService documentParseService,
                                KnowledgeCollectionService knowledgeCollectionService) {
        this.vectorIndexService = vectorIndexService;
        this.documentParseService = documentParseService;
        this.knowledgeCollectionService = knowledgeCollectionService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!autoImport) {
            logger.info("aiops-docs 自动同步已关闭（aiops.docs.auto-import=false），跳过启动扫描");
            return;
        }

        File docsDir = new File(docsPath);
        if (!docsDir.exists() || !docsDir.isDirectory()) {
            logger.warn("aiops-docs 目录不存在，跳过自动同步: {}", docsDir.getAbsolutePath());
            return;
        }

        logger.info("========================================");
        logger.info("开始同步 aiops-docs 文档库到 Milvus...");
        logger.info("文档目录: {}", docsDir.getAbsolutePath());
        logger.info("解析引擎: {}  collection: {}",
                documentParseService.properties().currentEngine(),
                knowledgeCollectionService.currentCollection());
        logger.info("========================================");

        try {
            VectorIndexService.IndexingResult result = vectorIndexService.syncDirectory(docsPath);
            logResult(result);
        } catch (Exception e) {
            logger.error("aiops-docs 自动同步异常，不影响应用正常启动: {}", e.getMessage(), e);
        } catch (LinkageError e) {
            logger.error("aiops-docs 自动同步异常，不影响应用正常启动: {}", e.getMessage(), e);
        }
    }

    private void logResult(VectorIndexService.IndexingResult result) {
        if (result.isSuccess()) {
            logger.info("========================================");
            logger.info("aiops-docs 同步完成");
        } else {
            logger.warn("========================================");
            logger.warn("aiops-docs 同步部分失败");
            if (result.getErrorMessage() != null) {
                logger.warn("   错误信息: {}", result.getErrorMessage());
            }
        }
        logger.info("   目录: {}", result.getDirectoryPath());
        logger.info("   collection: {}", result.getCollectionName());
        logger.info("   parser: {}", result.getParser());
        logger.info("   磁盘文件: {}", result.getTotalFiles());
        logger.info("   新增: {}", result.getSuccessCount());
        logger.info("   更新: {}", result.getUpdateCount());
        logger.info("   删除: {}", result.getDeleteCount());
        logger.info("   跳过(未变化): {}", result.getSkipCount());
        logger.info("   失败: {}", result.getFailCount());
        logger.info("   耗时: {} ms", result.getDurationMs());
        logger.info("========================================");
    }
}
