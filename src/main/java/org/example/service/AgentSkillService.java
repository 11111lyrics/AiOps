package org.example.service;

import org.example.config.ClsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 项目内 Agent Skill：从 classpath:skills/&lt;name&gt;/SKILL.md 加载操作手册，
 * 供模型通过工具按需读取，避免把长流程写进每份 system prompt。
 */
@Service
public class AgentSkillService {

    private static final Logger logger = LoggerFactory.getLogger(AgentSkillService.class);
    private static final String SKILL_PATTERN = "classpath:skills/*/SKILL.md";

    private final ClsProperties clsProperties;
    private final Map<String, SkillDocument> skills = new LinkedHashMap<>();

    public AgentSkillService(ClsProperties clsProperties) {
        this.clsProperties = clsProperties;
    }

    @PostConstruct
    public void init() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(SKILL_PATTERN);
            for (Resource resource : resources) {
                String raw = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                SkillDocument doc = parse(raw);
                if (doc == null || doc.name.isBlank()) {
                    logger.warn("跳过无法解析的 skill: {}", resource.getDescription());
                    continue;
                }
                skills.put(normalize(doc.name), doc);
                logger.info("已加载 Agent Skill: {} ({})", doc.name, resource.getDescription());
            }
        } catch (Exception e) {
            logger.warn("加载 Agent Skill 失败（不影响主流程）: {}", e.getMessage());
        }
        logger.info("Agent Skill 初始化完成, count={}", skills.size());
    }

    public List<SkillDocument> list() {
        return new ArrayList<>(skills.values());
    }

    /**
     * 按名称加载 skill 全文。cls-log-query 会附加当前环境的 CLS 主题对照表。
     */
    public String load(String name) {
        if (name == null || name.isBlank()) {
            return "skill 名称不能为空。可用: " + availableNames();
        }
        SkillDocument doc = skills.get(normalize(name));
        if (doc == null) {
            return "未找到 skill: " + name + "。可用: " + availableNames();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# Skill: ").append(doc.name).append("\n\n");
        sb.append(doc.body);
        if ("cls-log-query".equals(doc.name)) {
            String topics = clsProperties.buildTopicsPromptBlock();
            if (topics != null && !topics.isBlank()) {
                sb.append("\n\n").append(topics);
            }
        }
        return sb.toString();
    }

    public String availableNames() {
        if (skills.isEmpty()) {
            return "（无）";
        }
        return String.join(", ", skills.keySet());
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private SkillDocument parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.strip();
        SkillDocument doc = new SkillDocument();
        if (text.startsWith("---")) {
            int end = text.indexOf("\n---", 3);
            if (end > 0) {
                String front = text.substring(3, end).trim();
                doc.body = text.substring(end + 4).strip();
                for (String line : front.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("name:")) {
                        doc.name = unquote(trimmed.substring(5).trim());
                    } else if (trimmed.startsWith("description:")) {
                        String rest = trimmed.substring(12).trim();
                        if (">-".equals(rest) || "|".equals(rest) || ">".equals(rest)) {
                            continue;
                        }
                        doc.description = unquote(rest);
                    } else if (doc.description != null && !trimmed.contains(":") && !trimmed.startsWith("#")) {
                        doc.description = (doc.description + " " + trimmed).trim();
                    }
                }
                return doc.name == null ? null : doc;
            }
        }
        return null;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    public static class SkillDocument {
        private String name = "";
        private String description = "";
        private String body = "";

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public String getBody() {
            return body;
        }
    }
}
