package com.huawei.ascend.edp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统话术配置管理器。
 *
 * 加载 SysScriptsConfig.yaml 并管理 ask_user、取消确认、异常说明等模板。
 */
public class SysScriptsConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(SysScriptsConfig.class);

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    /** 话术模板字典。 */
    private final Map<String, String> templates = new LinkedHashMap<>();

    /** query_patterns 配置结构（解析后缓存）。 */
    private List<QueryPattern> queryPatterns = null;

    public SysScriptsConfig() {
        templates.put("thinking", "正在处理您的请求...");
        templates.put("out_of_scope", "当前请求暂不在可处理范围内。");
        templates.put("ask_user_confirm", "请确认是否继续执行该操作。");
    }

    /**
     * 加载话术模板配置。
     *
     * @param configPath 配置文件路径
     */
    public void load(String configPath) {
        if (configPath == null || configPath.isBlank()) {
            return;
        }
        Path path = Path.of(configPath).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            LOGGER.info("SysScriptsConfig not found at {}, skipping", path);
            return;
        }
        try {
            Map<String, Object> parsed = YAML_MAPPER.readValue(Files.readString(path), Map.class);
            flatten("", parsed);
            aliasCommonKeys();
            aliasGovernancePrefixes();
            LOGGER.info("SysScriptsConfig loaded from {}, templates={}", path, templates.size());
        } catch (Exception e) {
            LOGGER.warn("Failed to load SysScriptsConfig from {}: {}", path, e.getMessage());
        }
    }

    /**
     * 获取话术模板。
     *
     * @param key 模板 key
     * @return 模板内容，null 表示不存在
     */
    public String getTemplate(String key) {
        return templates.get(key);
    }

    /**
     * 是否存在该话术 key（话术消费面合规判定 / 兜底用）。
     *
     * @param key 模板 key
     * @return true 表示配置内存在该 key
     */
    public boolean has(String key) {
        return templates.containsKey(key);
    }

    /**
     * 取模板，缺失返回默认值（兜底场景用）。
     *
     * @param key 模板 key
     * @param def 缺失时的默认值
     * @return 模板内容或 def
     */
    public String getOrDefault(String key, String def) {
        return templates.getOrDefault(key, def);
    }

    /**
     * 合并场景级话术（场景级覆盖系统级同名 key）。
     *
     * @param skillScripts Skill 话术字典
     */
    public void mergeSkillScripts(Map<String, String> skillScripts) {
        if (skillScripts != null) {
            templates.putAll(skillScripts);
        }
    }

    /**
     * 做安全变量替换。
     *
     * @param template 模板内容
     * @param vars 变量字典
     * @return 替换后的内容
     */
    public String render(String template, Map<String, String> vars) {
        if (template == null) return "";
        String result = template;
        if (vars != null) {
            for (Map.Entry<String, String> entry : vars.entrySet()) {
                result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
                result = result.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return result;
    }

    public Map<String, String> getTemplates() {
        return Map.copyOf(templates);
    }

    /**
     * 获取 query_patterns 配置（解析后缓存）。
     * 配置结构：
     * query_patterns:
     *   - keywords: ["推荐", "理财", "产品"]
     *     scripts:
     *       - "正在搜索理财产品..."
     *
     * @return query_patterns 列表
     */
    public List<QueryPattern> getQueryPatterns() {
        if (queryPatterns != null) {
            return queryPatterns;
        }
        queryPatterns = new ArrayList<>();

        // 查找 query_patterns 的 key（可能是嵌套路径）
        String qpKey = null;
        for (Map.Entry<String, String> e : templates.entrySet()) {
            if (e.getKey().endsWith("query_patterns")) {
                qpKey = e.getKey();
                break;
            }
        }

        if (qpKey == null) {
            LOGGER.info("SysScriptsConfig no query_patterns config found");
            return queryPatterns;
        }

        // 构建完整路径前缀（去掉最后的 query_patterns）
        String prefix = qpKey.substring(0, qpKey.length() - "query_patterns".length());

        // 收集所有相关的 key-value
        // query_patterns[i].keywords[j] 和 query_patterns[i].scripts[j]
        // 在 flatten 后会被存储为：
        // - query_patterns.0.keywords.0 = "推荐"
        // - query_patterns.0.keywords.1 = "理财"
        // - query_patterns.0.scripts.0 = "正在搜索理财产品..."

        // 解析 query_patterns 结构
        try {
            // 先收集所有以 prefix 开头的 key
            Map<String, String> qpEntries = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : templates.entrySet()) {
                if (e.getKey().startsWith(prefix)) {
                    String shortKey = e.getKey().substring(prefix.length());
                    qpEntries.put(shortKey, e.getValue());
                }
            }

            // 构建嵌套结构的 YAML
            StringBuilder yamlBuilder = new StringBuilder();
            yamlBuilder.append("query_patterns:\n");

            // 解析索引分组
            Map<String, List<Map.Entry<String, String>>> groups = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : qpEntries.entrySet()) {
                String key = e.getKey();
                // 格式: 0.keywords.0, 0.scripts.0, 1.keywords.0, ...
                String idxStr = key.split("\\.")[0];
                List<Map.Entry<String, String>> list = groups.computeIfAbsent(idxStr, k -> new ArrayList<>());
                list.add(e);
            }

            // 生成简化的 YAML 结构
            for (List<Map.Entry<String, String>> group : groups.values()) {
                List<String> keywords = new ArrayList<>();
                List<String> scripts = new ArrayList<>();
                for (Map.Entry<String, String> e : group) {
                    String key = e.getKey();
                    String[] parts = key.split("\\.");
                    if (parts.length >= 3) {
                        String field = parts[1]; // keywords 或 scripts
                        if ("keywords".equals(field)) {
                            keywords.add(e.getValue());
                        } else if ("scripts".equals(field)) {
                            scripts.add(e.getValue());
                        }
                    }
                }
                if (!keywords.isEmpty() && !scripts.isEmpty()) {
                    yamlBuilder.append("  - keywords: [");
                    yamlBuilder.append(String.join(", ", keywords));
                    yamlBuilder.append("]\n    scripts:\n");
                    for (String scr : scripts) {
                        yamlBuilder.append("      - \"").append(scr).append("\"\n");
                    }
                }
            }

            String yamlStr = yamlBuilder.toString();
            Map<String, Object> parsed = YAML_MAPPER.readValue(yamlStr, Map.class);
            Object qpObj = parsed.get("query_patterns");
            if (qpObj instanceof List<?> qpList) {
                for (Object item : qpList) {
                    if (item instanceof Map<?, ?> itemMap) {
                        Object kwObj = itemMap.get("keywords");
                        Object scriptsObj = itemMap.get("scripts");
                        if (kwObj instanceof List<?> keywords && scriptsObj instanceof List<?> scripts) {
                            List<String> kws = new ArrayList<>();
                            for (Object kw : keywords) {
                                kws.add(String.valueOf(kw));
                            }
                            List<String> scrs = new ArrayList<>();
                            for (Object scr : scripts) {
                                scrs.add(String.valueOf(scr));
                            }
                            if (!kws.isEmpty() && !scrs.isEmpty()) {
                                queryPatterns.add(new QueryPattern(kws, scrs));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to parse query_patterns: {}", e.getMessage());
        }

        LOGGER.info("SysScriptsConfig parsed query_patterns: {} entries", queryPatterns.size());
        return queryPatterns;
    }

    /**
     * 根据用户 query 匹配关键词，选择对应的话术。
     *
     * @param userQuery 用户 query
     * @return 匹配的话术列表，未命中返回空列表
     */
    public List<String> matchQueryPatterns(String userQuery) {
        List<QueryPattern> patterns = getQueryPatterns();
        if (patterns.isEmpty() || userQuery == null || userQuery.isBlank()) {
            return List.of();
        }
        for (QueryPattern qp : patterns) {
            for (String keyword : qp.keywords) {
                if (userQuery.contains(keyword)) {
                    return qp.scripts;
                }
            }
        }
        return List.of();
    }

    /** query_patterns 单条配置。 */
    public static class QueryPattern {
        public final List<String> keywords;
        public final List<String> scripts;

        public QueryPattern(List<String> keywords, List<String> scripts) {
            this.keywords = keywords;
            this.scripts = scripts;
        }
    }

    private void flatten(String prefix, Map<String, Object> values) {
        if (values == null) return;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> child = new LinkedHashMap<>();
                map.forEach((k, v) -> child.put(String.valueOf(k), v));
                flatten(key, child);
            } else if (value instanceof List<?> list) {
                templates.put(key, joinList(list));
            } else if (value != null) {
                templates.put(key, String.valueOf(value));
            }
        }
    }

    private String joinList(List<?> values) {
        return values.stream()
                .map(String::valueOf)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private void aliasCommonKeys() {
        if (templates.containsKey("thinking.default")) {
            templates.put("thinking", templates.get("thinking.default"));
        }
        if (templates.containsKey("ask_user_confirm.default_confirm")) {
            templates.put("ask_user_confirm", templates.get("ask_user_confirm.default_confirm"));
        }
    }

    /**
     * governance/scriptconfig.yaml 嵌套结构适配：剥离 scriptconfig.general_scripts. 前缀，
     * 使消费者能以平铺 key（tool_start / interrupt_start 等）查找话术。
     */
    private void aliasGovernancePrefixes() {
        Map<String, String> aliases = new LinkedHashMap<>();
        // YAML 顶层有 scriptconfig: 键，flatten 产生 scriptconfig.general_scripts.xxx
        String prefix = "scriptconfig.general_scripts.";
        for (Map.Entry<String, String> e : templates.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                String shortKey = e.getKey().substring(prefix.length());
                aliases.put(shortKey, e.getValue());
            }
        }
        // query_intent_tool_text 前缀剥离（对齐 general_scripts 处理）
        String qiPrefix = "scriptconfig.query_intent_tool_text.";
        for (Map.Entry<String, String> e : templates.entrySet()) {
            if (e.getKey().startsWith(qiPrefix)) {
                String shortKey = e.getKey().substring(qiPrefix.length());
                aliases.put("query_intent_tool_text." + shortKey, e.getValue());
            }
        }
        templates.putAll(aliases);
    }
}
