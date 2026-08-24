package org.example.config;

import org.example.service.VectorIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时扫描 aiops-docs，把目录中的新增、删除、更新同步到 Milvus。
 */
@Component
public class AiopsDocsSyncScheduler {

    private static final Logger logger = LoggerFactory.getLogger(AiopsDocsSyncScheduler.class);

    private final VectorIndexService vectorIndexService;

    @Value("${aiops.docs.auto-import:true}")
    private boolean autoImport;

    public AiopsDocsSyncScheduler(VectorIndexService vectorIndexService) {
        this.vectorIndexService = vectorIndexService;
    }

    @Scheduled(
            fixedDelayString = "${aiops.docs.sync-interval-ms:1800000}",
            initialDelayString = "${aiops.docs.sync-interval-ms:1800000}")
    public void sync() {
        if (!autoImport) {
            return;
        }
        try {
            VectorIndexService.IndexingResult result = vectorIndexService.syncDocsLibrary();
            if (result.getSuccessCount() + result.getUpdateCount() + result.getDeleteCount() + result.getFailCount() > 0) {
                logger.info("定时同步文档库: 新增={}, 更新={}, 删除={}, 跳过={}, 失败={}",
                        result.getSuccessCount(), result.getUpdateCount(), result.getDeleteCount(),
                        result.getSkipCount(), result.getFailCount());
            }
        } catch (Exception e) {
            logger.warn("定时同步文档库失败: {}", e.getMessage());
        }
    }
}
