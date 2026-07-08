package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 经验生命周期服务
 * - 评分反馈：成功复用上调置信度，失败下调（人工接口 + AIOps 自动回写两条通道）。
 * - 定时衰减：长期不用的经验置信度衰减，过低则归档删除；临时经验按 TTL 清理。
 */
@Service
public class ExperienceLifecycleService {

    private static final Logger logger = LoggerFactory.getLogger(ExperienceLifecycleService.class);

    /** 置信度归档下限：低于此值则从长期库移除 */
    private static final double ARCHIVE_FLOOR = 0.2;
    private static final double UP_STEP = 0.05;
    private static final double DOWN_STEP = 0.10;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MilvusServiceClient milvusClient;

    @Value("${experience.decay.idle-days:30}")
    private int idleDays;

    @Value("${experience.decay.factor:0.95}")
    private double decayFactor;

    /**
     * 评分反馈：成功复用 → 置信度上调、success_count+1；失败 → 置信度下调。
     *
     * @param expId   经验 ID
     * @param success 本次复用是否成功
     * @return 是否命中并更新了该经验
     */
    public boolean feedback(String expId, boolean success) {
        if (expId == null || expId.isBlank()) {
            return false;
        }
        int rows;
        if (success) {
            rows = jdbcTemplate.update(
                    "UPDATE experience_meta SET confidence = LEAST(1.0, confidence + ?), " +
                            "success_count = success_count + 1, last_used = NOW() WHERE exp_id = ?",
                    UP_STEP, expId);
        } else {
            rows = jdbcTemplate.update(
                    "UPDATE experience_meta SET confidence = GREATEST(0.0, confidence - ?), " +
                            "last_used = NOW() WHERE exp_id = ?",
                    DOWN_STEP, expId);
        }
        if (rows > 0) {
            logger.info("经验评分回写 - expId={}, success={}", expId, success);
            return true;
        }
        logger.warn("经验评分回写未命中 - expId={}", expId);
        return false;
    }

    /**
     * 批量评分回写（供 AIOps 闭环结束时对本次采纳的经验自动回写）。
     */
    public void feedbackBatch(List<String> expIds, boolean success) {
        if (expIds == null) {
            return;
        }
        for (String expId : expIds) {
            feedback(expId, success);
        }
    }

    /**
     * 定时任务：衰减长期不用的经验 + 清理过期临时经验。
     */
    @Scheduled(cron = "${experience.decay.cron:0 0 3 * * *}")
    public void decayAndCleanup() {
        try {
            // 1. 衰减：超过 idleDays 未使用的经验置信度乘以衰减系数
            int decayed = jdbcTemplate.update(
                    "UPDATE experience_meta SET confidence = confidence * ? " +
                            "WHERE last_used IS NOT NULL AND last_used < (NOW() - INTERVAL ? DAY)",
                    decayFactor, idleDays);

            // 也衰减从未被使用、且创建已久的经验
            jdbcTemplate.update(
                    "UPDATE experience_meta SET confidence = confidence * ? " +
                            "WHERE last_used IS NULL AND created_at < (NOW() - INTERVAL ? DAY)",
                    decayFactor, idleDays);

            // 2. 归档：置信度低于下限的长期经验，从 MySQL + Milvus 移除
            List<String> toArchive = jdbcTemplate.queryForList(
                    "SELECT exp_id FROM experience_meta WHERE confidence < ?", String.class, ARCHIVE_FLOOR);
            for (String expId : toArchive) {
                deleteFromMilvus(expId);
                jdbcTemplate.update("DELETE FROM experience_meta WHERE exp_id = ?", expId);
            }

            // 3. 清理过期临时经验
            int purged = jdbcTemplate.update("DELETE FROM experience_temp WHERE expire_at < NOW()");

            logger.info("经验生命周期维护完成 - 衰减:{}, 归档:{}, 临时清理:{}", decayed, toArchive.size(), purged);
        } catch (Exception e) {
            logger.warn("经验生命周期维护失败: {}", e.getMessage());
        }
    }

    /**
     * 从 Milvus 删除指定经验。
     */
    private void deleteFromMilvus(String expId) {
        try {
            milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(MilvusConstants.EXPERIENCE_COLLECTION_NAME)
                    .build());
            String expr = String.format("id == \"%s\"", expId);
            R<MutationResult> resp = milvusClient.delete(DeleteParam.newBuilder()
                    .withCollectionName(MilvusConstants.EXPERIENCE_COLLECTION_NAME)
                    .withExpr(expr)
                    .build());
            if (resp.getStatus() != 0) {
                logger.warn("从 Milvus 删除经验失败 expId={}: {}", expId, resp.getMessage());
            }
        } catch (Exception e) {
            logger.warn("从 Milvus 删除经验异常 expId={}: {}", expId, e.getMessage());
        }
    }
}
