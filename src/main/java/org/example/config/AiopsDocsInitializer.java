package org.example.config;

import org.example.service.VectorIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * 启动时自动将 aiops-docs 目录下的文档导入到 Milvus 向量数据库
 * 解决切换 Milvus 实例后需要重新导入文档的问题
 */
@Component
public class AiopsDocsInitializer implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(AiopsDocsInitializer.class);

    private final VectorIndexService vectorIndexService;

    @Value("${aiops.docs.auto-import:true}")
    private boolean autoImport;

    @Value("${aiops.docs.path:./aiops-docs}")
    private String docsPath;

    public AiopsDocsInitializer(VectorIndexService vectorIndexService) {
        this.vectorIndexService = vectorIndexService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!autoImport) {
            logger.info("aiops-docs 自动导入已关闭（aiops.docs.auto-import=false），跳过");
            return;
        }

        File docsDir = new File(docsPath);
        if (!docsDir.exists() || !docsDir.isDirectory()) {
            logger.warn("aiops-docs 目录不存在，跳过自动导入: {}", docsDir.getAbsolutePath());
            return;
        }

        logger.info("========================================");
        logger.info("开始自动导入 aiops-docs 文档到 Milvus...");
        logger.info("文档目录: {}", docsDir.getAbsolutePath());
        logger.info("========================================");

        try {
            VectorIndexService.IndexingResult result = vectorIndexService.indexDirectory(docsPath);

            if (result.isSuccess()) {
                logger.info("========================================");
                logger.info("✅ aiops-docs 自动导入完成");
                logger.info("   总文件数: {}", result.getTotalFiles());
                logger.info("   成功: {}", result.getSuccessCount());
                logger.info("   失败: {}", result.getFailCount());
                logger.info("   耗时: {} ms", result.getDurationMs());
                logger.info("========================================");
            } else {
                logger.warn("========================================");
                logger.warn("⚠️  aiops-docs 自动导入部分失败");
                logger.warn("   总文件数: {}", result.getTotalFiles());
                logger.warn("   成功: {}", result.getSuccessCount());
                logger.warn("   失败: {}", result.getFailCount());
                if (result.getErrorMessage() != null) {
                    logger.warn("   错误信息: {}", result.getErrorMessage());
                }
                logger.warn("========================================");
            }
        } catch (Exception e) {
            logger.error("aiops-docs 自动导入异常，不影响应用正常启动: {}", e.getMessage(), e);
        }
    }
}
