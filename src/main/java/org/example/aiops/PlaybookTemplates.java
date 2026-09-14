package org.example.aiops;

import org.example.aiops.config.AiOpsOrchestrationProperties.Playbook;
import org.example.aiops.config.AiOpsOrchestrationProperties.PlaybookParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * playbook 命令句式固定、参数用 {@code {name}} 占位。
 * 与 docker inspect 的 {@code {{.State.Status}}} 不冲突（后者以 {@code .} 开头，不会被当成参数名）。
 */
public final class PlaybookTemplates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)}");
    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9_.:\\-`]+");

    private PlaybookTemplates() {
    }

    public static Set<String> placeholders(Playbook playbook) {
        Set<String> names = new LinkedHashSet<>();
        if (playbook == null) {
            return names;
        }
        collect(names, playbook.getCommands());
        collect(names, playbook.getPrecondition(), playbook.getVerifyCommand(),
                playbook.getVerifyExpect(), playbook.getRollback(), playbook.getTarget());
        return names;
    }

    public static String fill(String template, Map<String, String> params) {
        if (template == null || template.isEmpty()) {
            return template == null ? "" : template;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = params == null ? null : params.get(key);
            if (value == null) {
                throw new IllegalArgumentException("缺少参数 {" + key + "}");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static List<String> fillAll(List<String> templates, Map<String, String> params) {
        List<String> filled = new ArrayList<>();
        if (templates == null) {
            return filled;
        }
        for (String template : templates) {
            filled.add(fill(template, params));
        }
        return filled;
    }

    /**
     * 用默认值补齐后校验：占位符必须有值；有 choices 则必须落在枚举内；无枚举则只允许安全字符。
     */
    public static Map<String, String> resolveParams(Playbook playbook, Map<String, String> raw)
            throws IllegalArgumentException {
        Map<String, String> resolved = new LinkedHashMap<>();
        Map<String, PlaybookParam> specs = playbook.getParams() == null ? Map.of() : playbook.getParams();
        if (raw != null) {
            raw.forEach((k, v) -> {
                if (k != null && v != null && !v.isBlank()) {
                    resolved.put(k, v.trim());
                }
            });
        }
        for (Map.Entry<String, PlaybookParam> entry : specs.entrySet()) {
            if (resolved.containsKey(entry.getKey())) {
                continue;
            }
            PlaybookParam spec = entry.getValue();
            if (spec != null && spec.getDefaultValue() != null && !spec.getDefaultValue().isBlank()) {
                resolved.put(entry.getKey(), spec.getDefaultValue().trim());
            }
        }
        for (String name : placeholders(playbook)) {
            String value = resolved.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("参数 {" + name + "} 未填写且无默认值");
            }
            PlaybookParam spec = specs.get(name);
            if (spec != null && spec.getChoices() != null && !spec.getChoices().isEmpty()) {
                if (!spec.getChoices().contains(value)) {
                    throw new IllegalArgumentException("参数 {" + name + "}=" + value
                            + " 不在预设 choices " + spec.getChoices());
                }
            } else if (!SAFE_VALUE.matcher(value).matches()) {
                throw new IllegalArgumentException("参数 {" + name + "} 含非法字符");
            }
        }
        return resolved;
    }

    private static void collect(Set<String> names, List<String> texts) {
        if (texts == null) {
            return;
        }
        for (String text : texts) {
            collect(names, text);
        }
    }

    private static void collect(Set<String> names, String... texts) {
        if (texts == null) {
            return;
        }
        for (String text : texts) {
            if (text == null || text.isEmpty()) {
                continue;
            }
            Matcher matcher = PLACEHOLDER.matcher(text);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
    }
}
