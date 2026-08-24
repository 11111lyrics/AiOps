package org.example.config;

import org.example.constant.MilvusConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;
import java.util.Set;

/**
 * 知识库文档解析开关。关闭时走 PDFBox/Tika 基线；开启后入库前调用本地 PaddleOCR-VL 或 MinerU。
 */
@Configuration
@ConfigurationProperties(prefix = "document.parse")
public class DocumentParseProperties {

    public static final String ENGINE_BASELINE = "baseline";
    public static final String ENGINE_PADDLE = "paddle";
    public static final String ENGINE_MINERU = "mineru";

    private static final Set<String> REMOTE_ENGINES = Set.of(ENGINE_PADDLE, ENGINE_MINERU);

    /** 是否在入库前调用外部解析服务 */
    private boolean enabled = false;

    /** baseline / paddle / mineru；enabled=false 时一律按 baseline */
    private String engine = ENGINE_BASELINE;

    /** 解析服务不可用时是否回退 PDFBox/Tika，并在下次同步重试 */
    private boolean fallbackToBaseline = true;

    /**
     * true：paddle/mineru 写入独立 collection（biz_paddle / biz_mineru），基线仍用 biz，便于对比且互不覆盖。
     * false：都写入 biz，换引擎会按 _parser 强制重建。
     */
    private boolean isolateCollection = true;

    private long timeoutMs = 600_000L;

    private Paddle paddle = new Paddle();
    private Mineru mineru = new Mineru();

    public String currentEngine() {
        if (!enabled) {
            return ENGINE_BASELINE;
        }
        String normalized = normalizeEngine(engine);
        return REMOTE_ENGINES.contains(normalized) ? normalized : ENGINE_BASELINE;
    }

    public boolean isRemoteEnabled() {
        return enabled && REMOTE_ENGINES.contains(currentEngine());
    }

    public String knowledgeCollection() {
        if (!isolateCollection) {
            return MilvusConstants.MILVUS_COLLECTION_NAME;
        }
        return MilvusConstants.knowledgeCollectionFor(currentEngine());
    }

    public static String normalizeEngine(String engine) {
        if (engine == null || engine.isBlank()) {
            return ENGINE_BASELINE;
        }
        return engine.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public boolean isFallbackToBaseline() {
        return fallbackToBaseline;
    }

    public void setFallbackToBaseline(boolean fallbackToBaseline) {
        this.fallbackToBaseline = fallbackToBaseline;
    }

    public boolean isIsolateCollection() {
        return isolateCollection;
    }

    public void setIsolateCollection(boolean isolateCollection) {
        this.isolateCollection = isolateCollection;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public Paddle getPaddle() {
        return paddle;
    }

    public void setPaddle(Paddle paddle) {
        this.paddle = paddle != null ? paddle : new Paddle();
    }

    public Mineru getMineru() {
        return mineru;
    }

    public void setMineru(Mineru mineru) {
        this.mineru = mineru != null ? mineru : new Mineru();
    }

    public static class Paddle {
        private String baseUrl = "http://127.0.0.1:8080";
        private String path = "/layout-parsing";
        private String healthPath = "/health";
        private boolean useChartRecognition = true;
        private boolean useOcrForImageBlock = true;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getHealthPath() {
            return healthPath;
        }

        public void setHealthPath(String healthPath) {
            this.healthPath = healthPath;
        }

        public boolean isUseChartRecognition() {
            return useChartRecognition;
        }

        public void setUseChartRecognition(boolean useChartRecognition) {
            this.useChartRecognition = useChartRecognition;
        }

        public boolean isUseOcrForImageBlock() {
            return useOcrForImageBlock;
        }

        public void setUseOcrForImageBlock(boolean useOcrForImageBlock) {
            this.useOcrForImageBlock = useOcrForImageBlock;
        }
    }

    public static class Mineru {
        private String baseUrl = "http://127.0.0.1:8000";
        private String path = "/file_parse";
        private String healthPath = "/health";
        /** 本机 mineru-api 合法值：pipeline | vlm-engine | hybrid-engine */
        private String backend = "pipeline";
        private String parseMethod = "auto";
        private String lang = "ch";
        private boolean formulaEnable = true;
        private boolean tableEnable = true;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getHealthPath() {
            return healthPath;
        }

        public void setHealthPath(String healthPath) {
            this.healthPath = healthPath;
        }

        public String getBackend() {
            return backend;
        }

        public void setBackend(String backend) {
            this.backend = backend;
        }

        public String getParseMethod() {
            return parseMethod;
        }

        public void setParseMethod(String parseMethod) {
            this.parseMethod = parseMethod;
        }

        public String getLang() {
            return lang;
        }

        public void setLang(String lang) {
            this.lang = lang;
        }

        public boolean isFormulaEnable() {
            return formulaEnable;
        }

        public void setFormulaEnable(boolean formulaEnable) {
            this.formulaEnable = formulaEnable;
        }

        public boolean isTableEnable() {
            return tableEnable;
        }

        public void setTableEnable(boolean tableEnable) {
            this.tableEnable = tableEnable;
        }
    }
}
