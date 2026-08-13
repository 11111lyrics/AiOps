package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.AgentSkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目内 Agent Skill 工具：列出并按需加载操作手册（如 CLS 查询顺序）。
 */
@Component
public class SkillTools {

    private static final Logger logger = LoggerFactory.getLogger(SkillTools.class);

    public static final String TOOL_LIST_SKILLS = "listSkills";
    public static final String TOOL_LOAD_SKILL = "loadSkill";

    private final AgentSkillService agentSkillService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SkillTools(AgentSkillService agentSkillService) {
        this.agentSkillService = agentSkillService;
    }

    @Tool(description = "List available project skills (procedural playbooks) with name and description. " +
            "Call this if you are unsure which skill to load.")
    public String listSkills() {
        try {
            List<Map<String, String>> items = new ArrayList<>();
            for (AgentSkillService.SkillDocument doc : agentSkillService.list()) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("name", doc.getName());
                item.put("description", doc.getDescription());
                items.add(item);
            }
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            logger.error("[工具错误] listSkills 执行失败", e);
            return "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}";
        }
    }

    @Tool(description = "Load a project skill playbook by name and return its full instructions. " +
            "MUST call this before querying Tencent Cloud CLS / SearchLog: use name 'cls-log-query'. " +
            "Do not guess the MCP tool order from memory.")
    public String loadSkill(
            @ToolParam(description = "Skill name, e.g. cls-log-query") String name) {
        logger.info("加载 Agent Skill: {}", name);
        return agentSkillService.load(name);
    }
}
