package com.huawei.ascend.edp.rail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.ascend.edp.channel.ToolDataChannel;
import com.huawei.ascend.edp.channel.ToolDataKey;
import com.huawei.ascend.edp.channel.ToolDataKeyFactory;
import com.huawei.ascend.edp.config.EdpaSpringBootConfig;
import com.huawei.ascend.edp.config.EdpConfig;
import com.huawei.ascend.edp.config.ScriptConstants;
import com.huawei.ascend.edp.config.SysScriptsConfig;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.llm.schema.ToolMessage;
import com.openjiuwen.core.session.interaction.InteractiveInput;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptException;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptionState;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Versatile Agent 委托调用 Rail。
 *
 * <p>文件作用：</p>
 * <ul>
 *     <li>在 call_versatile 工具调用前拦截并直连 Versatile REST 或 adapter A2A。</li>
 *     <li>解析 adapter SSE 响应，分离 USER 透传节点与 LLM 终态内容。</li>
 *     <li>adapter 请求用户输入时抛出 {@link ToolInterruptException}，由 runtime 续传。</li>
 *     <li>续传恢复时从 {@link ToolInterruptionState#RESUME_USER_INPUT_KEY} 回填工具结果。</li>
 * </ul>
 *
 * <p>对外提供的接口：</p>
 * <ul>
 *     <li>{@link #beforeToolCall(AgentCallbackContext)}：工具调用前回调入口。</li>
 *     <li>{@link #invokeWithInputs(Map, String)}：供 handler 层 Versatile 菜单续传直接调用。</li>
 *     <li>{@link VersatilePassthroughBuffer}：与 {@link com.huawei.ascend.edp.handler.EdpaRuntimeHandler}
 *         共享的会话级 USER 节点缓冲。</li>
 * </ul>
 */
public class VersatileInterruptRail extends AgentRail {

    private static final Logger LOGGER = LoggerFactory.getLogger(VersatileInterruptRail.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * pre-delegate guard 计数器在 ToolDataChannel 中的 key 前缀。
     * 对齐 Python versatile_interrupt_rail.py 第 434 行 {@code state_key = f"_pre_delegate_guard:{command}:{rule_id}"}，
     * 用 {@code command:ruleId} 作为唯一标识，避免不同 skill 的同名规则互相干扰。
     */
    static final String GUARD_STATE_KEY_PREFIX = "_pre_delegate_guard:";

    /**
     * history_info 字段名。与 {@link McpInterruptRail#HISTORY_INFO_KEY} 同名，
     * 保证 call_versatile 写入的 history_info 能被下一次 call_mcp 的 buildSkillInput 读到，
     * 形成跨工具的会话级持久化闭环（对齐 Python {@code session.state["history_info"]}）。
     */
    static final String HISTORY_INFO_KEY = "history_info";

    /**
     * EDP 专有配置，当前预留给 VA 委托策略和目标 Agent 配置使用。
     */
    private final EdpConfig edpConfig;

    private final EdpaSpringBootConfig.VersatileConfig versatileConfig;
    private final ToolDataChannel toolDataChannel;
    /** 与 EdpaRuntimeHandler 共享，存放 adapter 返回的完整 Versatile message JSON。 */
    private final VersatilePassthroughBuffer passthroughBuffer;
    private final HttpClient httpClient;

    /**
     * skills 目录，用于 pre-delegate guard 静态解析脚本中的 {@code PRE_DELEGATE_GUARD} 配置。
     * 为 null 时 guard 直接跳过（不影响主流程）。
     */
    private final Path skillsDir;

    /**
     * 话术配置，用于 pre-delegate guard 超限时解析 {@code response_template_key} 兜底话术。
     * 为 null 时回落到规则的 {@code fallback_message}。
     */
    private final SysScriptsConfig scripts;

    /**
     * 构造 VA 委托 Rail。
     *
     * @param edpConfig EDP 专有配置
     */
    public VersatileInterruptRail(EdpConfig edpConfig) {
        this(edpConfig, null, new ToolDataChannel());
    }

    public VersatileInterruptRail(EdpConfig edpConfig, EdpaSpringBootConfig.VersatileConfig versatileConfig) {
        this(edpConfig, versatileConfig, new ToolDataChannel());
    }

    public VersatileInterruptRail(EdpConfig edpConfig, EdpaSpringBootConfig.VersatileConfig versatileConfig,
            ToolDataChannel toolDataChannel) {
        this(edpConfig, versatileConfig, toolDataChannel, new VersatilePassthroughBuffer());
    }

    public VersatileInterruptRail(EdpConfig edpConfig, EdpaSpringBootConfig.VersatileConfig versatileConfig,
            ToolDataChannel toolDataChannel, VersatilePassthroughBuffer passthroughBuffer) {
        this(edpConfig, versatileConfig, toolDataChannel, passthroughBuffer, null, null);
    }

    /**
     * 全参构造：供 {@link com.huawei.ascend.edp.enhancer.EdpaAgentEnhancer} 注入 skillsDir 与话术配置。
     *
     * <p>注：{@link com.huawei.ascend.edp.handler.EdpaRuntimeHandler} 续传路径仍用四参构造
     * （skillsDir/scripts 为 null），guard 与持久化都不会在该路径触发——续传是已中断后的恢复，
     * 不应重新跑 guard 计数，也不应重复持久化 history_info。</p>
     *
     * @param skillsDir skills 目录，用于 pre-delegate guard 静态解析
     * @param scripts 话术配置，用于 guard 超限兜底话术
     */
    public VersatileInterruptRail(EdpConfig edpConfig, EdpaSpringBootConfig.VersatileConfig versatileConfig,
            ToolDataChannel toolDataChannel, VersatilePassthroughBuffer passthroughBuffer,
            Path skillsDir, SysScriptsConfig scripts) {
        this.edpConfig = edpConfig;
        this.versatileConfig = versatileConfig;
        this.toolDataChannel = toolDataChannel != null ? toolDataChannel : new ToolDataChannel();
        this.passthroughBuffer = passthroughBuffer != null ? passthroughBuffer : new VersatilePassthroughBuffer();
        this.skillsDir = skillsDir != null ? skillsDir.toAbsolutePath().normalize() : null;
        this.scripts = scripts;
        Duration timeout = versatileConfig != null ? parseTimeout(versatileConfig.getTimeout()) : Duration.ofSeconds(30);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        // VA 与 MCP、ask_user 同属工具调用增强类 Rail，使用同一优先级。
        setPriority(85);
    }

    /**
     * 工具调用前回调。
     *
     * <p>执行顺序（对齐 Python {@code resolve_interrupt}）：</p>
     * <ol>
     *     <li>续传路径：用户已提交菜单确认等输入，直接回填工具结果。</li>
     *     <li>pre-delegate guard：从脚本静态读取 {@code PRE_DELEGATE_GUARD}，超限则终止。</li>
     *     <li>委托 adapter 执行业务流程。</li>
     * </ol>
     *
     * @param ctx OpenJiuwen 回调上下文，包含工具调用信息
     */
    @Override
    public void beforeToolCall(AgentCallbackContext ctx) {
        // 关键判断：只有工具调用上下文才需要识别 VA 委托工具。
        if (!(ctx.getInputs() instanceof ToolCallInputs inputs)) {
            return;
        }
        String toolName = inputs.getToolName();

        // 关键判断：只拦截 call_versatile，其他工具直接放行。
        if ("call_versatile".equals(toolName)) {
            String toolCallId = toolCallId(inputs);
            // 续传路径：用户已提交菜单确认等输入，直接回填工具结果，不再重复调 adapter。
            Object resumeInput = resolveResumeInput(ctx, toolCallId);
            if (resumeInput != null) {
                LOGGER.info("VersatileInterruptRail: resuming call_versatile with adapter result, toolCallId={}",
                        toolCallId);
                ctx.getExtra().put(ScriptConstants.KEY_SKIP_TOOL, Boolean.TRUE);
                Object toolResult = normalizeResumeToolResult(resumeInput);
                inputs.setToolResult(toolResult);
                inputs.setToolMsg(ToolMessage.builder()
                        .content(toJson(toolResult))
                        .toolCallId(toolCallId)
                        .build());
                return;
            }
            LOGGER.info("VersatileInterruptRail: intercepting call_versatile, direct call to versatile service");

            // pre-delegate guard：在真正委托 adapter 之前检查 skill 声明的前置规则。
            // 超限时直接终止当前 ReAct 流程，避免 adapter 继续执行真实业务（如转账）。
            // 对齐 Python versatile_interrupt_rail.py 第 128-130 行。
            GuardDecision guardDecision = applyPreDelegateGuard(ctx, normalizeArgs(inputs));
            if (guardDecision != null && guardDecision.blocked()) {
                blockCallVersatileByGuard(ctx, inputs, toolCallId, guardDecision);
                return;
            }

            ctx.getExtra().put(ScriptConstants.KEY_SKIP_TOOL, Boolean.TRUE);

            Map<String, Object> toolResult = callVersatile(inputs, ctx);
            if (isInputRequired(toolResult)) {
                // adapter 进入 INPUT_REQUIRED：先刷透传节点，再中断等待用户确认。
                throw inputRequiredInterrupt(ctx, inputs, toolResult);
            }
            inputs.setToolResult(toolResult);
            inputs.setToolMsg(ToolMessage.builder()
                    .content(toJson(toolResult))
                    .toolCallId(inputs.getToolCall() != null ? inputs.getToolCall().getId() : "call_versatile")
                    .build());
        }
    }

    /**
     * pre-delegate guard 超限时的统一收尾：写话术、强制结束、回填失败 toolResult。
     *
     * <p>必须同时设置 toolResult 与 toolMsg，否则 OpenJiuwen 会因 tool_call 无对应 tool_response
     * 而在 forceFinish 后的 LLM 调用中 HTTP 400（沿用 {@link CancelRail} 的成熟模式）。
     * 对齐 Python {@code _apply_pre_delegate_guard} 第 452-468 行：</p>
     * <ul>
     *     <li>response_template 写入 extra（北向话术通道，由 EdpaEventRail 出口发射）。</li>
     *     <li>{@code requestForceFinish} 终止 ReAct 循环。</li>
     *     <li>{@code reject} 跳过本次工具调用 → 此处用 KEY_SKIP_TOOL=true 等价。</li>
     * </ul>
     */
    private void blockCallVersatileByGuard(AgentCallbackContext ctx, ToolCallInputs inputs,
            String toolCallId, GuardDecision decision) {
        ctx.getExtra().put(ScriptConstants.KEY_RESPONSE_TEMPLATE, decision.message());
        ctx.requestForceFinish(Map.of(
                "result_type", "interrupt",
                "state", List.of(),
                "interrupt_ids", List.of()));
        Map<String, Object> blockedResult = new LinkedHashMap<>();
        blockedResult.put("status", "failed");
        blockedResult.put("message", decision.message());
        inputs.setToolResult(blockedResult);
        inputs.setToolMsg(ToolMessage.builder()
                .content(toJson(blockedResult))
                .toolCallId(toolCallId)
                .build());
        ctx.getExtra().put(ScriptConstants.KEY_SKIP_TOOL, Boolean.TRUE);
        LOGGER.warn("VersatileInterruptRail: pre-delegate guard blocked, rule={}, count={}, limit={}, message={}",
                decision.ruleId(), decision.count(), decision.maxCalls(), decision.message());
    }

    private Map<String, Object> callVersatile(ToolCallInputs inputs, AgentCallbackContext ctx) {
        if (versatileConfig == null || versatileConfig.getUrl() == null || versatileConfig.getUrl().isBlank()) {
            return failedResult("versatile config is missing");
        }
        try {
            Map<String, Object> args = normalizeArgs(inputs);
            String conversationId = ctx.getSession() != null && ctx.getSession().getSessionId() != null
                    ? ctx.getSession().getSessionId() : "call-versatile-spike";
            Map<String, Object> versatileInputs = buildInputs(args, ctx);
            return invokeWithInputs(versatileInputs, conversationId);
        } catch (Exception e) {
            LOGGER.warn("VersatileInterruptRail: direct call failed: {}", e.getMessage());
            return failedResult(e.getMessage());
        }
    }

    private String toolCallId(ToolCallInputs inputs) {
        return inputs.getToolCall() != null && inputs.getToolCall().getId() != null
                && !inputs.getToolCall().getId().isBlank()
                ? inputs.getToolCall().getId() : "call_versatile";
    }

    private Object resolveResumeInput(AgentCallbackContext ctx, String toolCallId) {
        // runtime 在中断恢复时把用户输入写入 extra；优先按 toolCallId 精确匹配。
        Object rawInput = ctx.getExtra().get(ToolInterruptionState.RESUME_USER_INPUT_KEY);
        if (rawInput instanceof InteractiveInput interactiveInput) {
            Map<String, Object> userInputs = interactiveInput.getUserInputs();
            if (toolCallId != null && !toolCallId.isBlank() && userInputs.containsKey(toolCallId)) {
                return userInputs.get(toolCallId);
            }
            return interactiveInput.getRawInputs();
        }
        if (rawInput instanceof Map<?, ?> map && toolCallId != null && !toolCallId.isBlank()
                && map.containsKey(toolCallId)) {
            return map.get(toolCallId);
        }
        return rawInput;
    }

    private Object normalizeResumeToolResult(Object resumeInput) {
        if (resumeInput instanceof String text && !text.isBlank()) {
            try {
                return OBJECT_MAPPER.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() { });
            } catch (Exception e) {
                return Map.of("source", "versatile", "status", "completed", "content", text);
            }
        }
        return resumeInput;
    }

    /**
     * 统一 Versatile 调用入口：优先 adapter A2A，否则 REST 直连。
     * 调用完成后把 passthrough_nodes 写入共享缓冲供上层流式刷出。
     */
    public Map<String, Object> invokeWithInputs(Map<String, Object> versatileInputs, String conversationId) {
        if (versatileConfig == null) {
            return failedResult("versatile config is missing");
        }
        boolean hasAdapterA2a = versatileConfig.getAdapterA2aUrl() != null
                && !versatileConfig.getAdapterA2aUrl().isBlank();
        boolean hasDirectUrl = versatileConfig.getUrl() != null && !versatileConfig.getUrl().isBlank();
        if (!hasAdapterA2a && !hasDirectUrl) {
            return failedResult("versatile config is missing");
        }
        try {
            Map<String, Object> result = hasAdapterA2a
                    ? callVersatileAdapterA2a(versatileInputs, conversationId)
                    : callVersatileDirect(versatileInputs, conversationId);
            storePassthroughNodes(conversationId, result);
            return result;
        } catch (Exception e) {
            LOGGER.warn("VersatileInterruptRail: direct call failed: {}", e.getMessage());
            return failedResult(e.getMessage());
        }
    }

    private Map<String, Object> callVersatileDirect(Map<String, Object> versatileInputs, String conversationId)
            throws Exception {
        String url = resolveUrl(conversationId);
        Map<String, Object> body = Map.of("inputs", versatileInputs, "stream", true);
        String bodyJson = OBJECT_MAPPER.writeValueAsString(body);
        LOGGER.info("VersatileInterruptRail: request body {}", bodyJson);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(parseTimeout(versatileConfig.getTimeout()))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8));
        Map<String, String> headers = versatileConfig.getHeaders() != null
                ? versatileConfig.getHeaders() : Map.of();
        headers.forEach(builder::header);
        if (!hasHeader(headers, "content-type")) {
            builder.header("Content-Type", "application/json");
        }

        LOGGER.info("VersatileInterruptRail: POST {}", url);
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        LOGGER.info("VersatileInterruptRail: response status={} body={}", response.statusCode(), abbreviate(response.body()));
        if (response.statusCode() >= 400) {
            return failedResult("HTTP " + response.statusCode() + ": " + response.body());
        }
        String content = normalizeContent(response.body());
        LOGGER.info("VersatileInterruptRail: normalized content {}", content);
        return Map.of("source", "versatile", "status", "completed", "content", content);
    }

    private Map<String, Object> normalizeArgs(ToolCallInputs inputs) {
        Map<String, Object> args = normalizeArgsObject(inputs.getToolArgs());
        if (args.isEmpty() && inputs.getToolCall() != null) {
            args = normalizeArgsObject(inputs.getToolCall().getArguments());
        }
        return args;
    }

    /** 通过 adapter-versatile-agent-java 的 A2A SendStreamingMessage 发起 SSE 调用。 */
    private Map<String, Object> callVersatileAdapterA2a(Map<String, Object> versatileInputs, String conversationId)
            throws Exception {
        String adapterUrl = versatileConfig.getAdapterA2aUrl();
        String messageText = OBJECT_MAPPER.writeValueAsString(Map.of("inputs", versatileInputs));
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "ROLE_USER");
        message.put("messageId", "msg-" + UUID.randomUUID());
        message.put("contextId", conversationId);
        message.put("parts", List.of(Map.of("text", messageText)));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("userId", "edp-agent");
        metadata.put("agentId", "edp-agent");
        metadata.put("versatile", Map.of("inputs", versatileInputs));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("metadata", metadata);
        params.put("message", message);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("jsonrpc", "2.0");
        requestBody.put("method", "SendStreamingMessage");
        requestBody.put("id", "call-versatile-" + UUID.randomUUID());
        requestBody.put("params", params);

        String bodyJson = OBJECT_MAPPER.writeValueAsString(requestBody);
        LOGGER.info("VersatileInterruptRail: POST adapter A2A {}", adapterUrl);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(adapterUrl))
                .timeout(parseTimeout(versatileConfig.getTimeout()))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        LOGGER.info("VersatileInterruptRail: adapter A2A status={} body={}",
                response.statusCode(), abbreviate(response.body()));
        if (response.statusCode() >= 400) {
            return failedResult("adapter A2A HTTP " + response.statusCode() + ": " + response.body());
        }
        return normalizeA2aAdapterResponse(response.body());
    }

    /**
     * 解析 adapter A2A SSE 响应体。
     * artifactUpdate 中的 text 为 USER 透传节点；statusUpdate 中的 text 为 LLM 终态内容。
     */
    private Map<String, Object> normalizeA2aAdapterResponse(String body) throws Exception {
        List<String> passthroughNodes = new ArrayList<>();
        String completedContent = "";
        String state = "";
        for (String line : body != null ? body.split("\\R") : new String[0]) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String payload = trimmed.substring(5).trim();
            if (payload.isBlank() || "[DONE]".equals(payload)) {
                continue;
            }
            JsonNode root = OBJECT_MAPPER.readTree(payload);
            JsonNode result = root.path("result");
            state = extractA2aState(result, state);
            String artifactText = extractA2aArtifactText(result);
            if (!artifactText.isBlank()) {
                passthroughNodes.add(artifactText);
            }
            String terminalText = extractA2aStatusText(result);
            if (!terminalText.isBlank()) {
                completedContent = terminalText;
            }
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("source", "versatile");
        normalized.put("status", state.equalsIgnoreCase("TASK_STATE_INPUT_REQUIRED") ? "input_required" : "completed");
        normalized.put("content", completedContent);
        normalized.put("passthrough_nodes", passthroughNodes);
        return normalized;
    }

    private void storePassthroughNodes(AgentCallbackContext ctx, Map<String, Object> toolResult) {
        storePassthroughNodes(conversationId(ctx), toolResult);
    }

    private void storePassthroughNodes(String conversationId, Map<String, Object> toolResult) {
        if (toolResult == null) {
            return;
        }
        Object nodes = toolResult.get("passthrough_nodes");
        if (!(nodes instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        List<String> normalized = new ArrayList<>();
        for (Object node : list) {
            if (node != null && !String.valueOf(node).isBlank()) {
                normalized.add(String.valueOf(node));
            }
        }
        passthroughBuffer.addAll(conversationId, normalized);
        LOGGER.info("VersatileInterruptRail: queued passthrough nodes conversationId={} count={}",
                conversationId, normalized.size());
    }

    private boolean isInputRequired(Map<String, Object> toolResult) {
        return toolResult != null && "input_required".equals(String.valueOf(toolResult.get("status")));
    }

    private ToolInterruptException inputRequiredInterrupt(AgentCallbackContext ctx, ToolCallInputs inputs,
            Map<String, Object> toolResult) {
        String toolCallId = inputs.getToolCall() != null && inputs.getToolCall().getId() != null
                ? inputs.getToolCall().getId() : "call_versatile";
        // 记录 interruptId，供续传完成时包装 InteractiveInput 恢复 call_versatile。
        passthroughBuffer.rememberInterruptId(conversationId(ctx), toolCallId);
        LOGGER.info("VersatileInterruptRail: adapter requested user input, toolCallId={}", toolCallId);
        InterruptRequest request = InterruptRequest.builder()
                .interruptId(toolCallId)
                .message("")
                .context(Map.of("tool", "call_versatile", "result", toolResult))
                .payloadSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string"),
                                "menu_type", Map.of("type", "string"),
                                "menu_confirm", Map.of("type", "boolean"))))
                .build();
        return new ToolInterruptException(request, inputs.getToolCall());
    }

    private String conversationId(AgentCallbackContext ctx) {
        return ctx.getSession() != null && ctx.getSession().getSessionId() != null
                ? ctx.getSession().getSessionId() : "call-versatile-spike";
    }

    private String extractA2aState(JsonNode result, String fallback) {
        String state = result.path("statusUpdate").path("status").path("state").asText("");
        if (state.isBlank()) {
            state = result.path("status").path("state").asText("");
        }
        return state.isBlank() ? fallback : state;
    }

    private String extractA2aArtifactText(JsonNode result) {
        JsonNode parts = result.path("artifactUpdate").path("artifact").path("parts");
        return extractPartsText(parts);
    }

    private String extractA2aStatusText(JsonNode result) {
        JsonNode parts = result.path("statusUpdate").path("status").path("message").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            parts = result.path("status").path("message").path("parts");
        }
        return extractPartsText(parts);
    }

    private String extractPartsText(JsonNode parts) {
        if (!parts.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode part : parts) {
            String text = part.path("text").asText("");
            if (text.isBlank()) {
                text = part.path("content").asText("");
            }
            if (!text.isBlank()) {
                builder.append(text);
            }
        }
        return builder.toString();
    }

    private Map<String, Object> normalizeArgsObject(Object toolArgs) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (toolArgs instanceof Map<?, ?> map) {
            map.forEach((key, value) -> args.put(String.valueOf(key), value));
            return args;
        }
        if (toolArgs instanceof String text && !text.isBlank()) {
            try {
                JsonNode node = OBJECT_MAPPER.readTree(text);
                if (node != null && node.isObject()) {
                    node.fields().forEachRemaining(entry -> args.put(entry.getKey(), OBJECT_MAPPER.convertValue(entry.getValue(), Object.class)));
                }
            } catch (Exception e) {
                LOGGER.warn("VersatileInterruptRail: failed to parse tool arguments: {}", text);
            }
        }
        return args;
    }

    private Map<String, Object> buildInputs(Map<String, Object> args, AgentCallbackContext ctx) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        ToolDataKey channelKey = ToolDataKeyFactory.fromContext(ctx, edpConfig);
        String query = String.valueOf(args.getOrDefault("query_description", ""));
        if (query.isBlank()) {
            query = readCachedQuery(channelKey);
        }
        inputs.put("query", query);
        inputs.putAll(args);
        inputs.put("query_description", query);

        String inputKey = String.valueOf(args.getOrDefault("input_key", ""));
        if (!inputKey.isBlank()) {
            Object inputData = toolDataChannel.getObject(channelKey, inputKey);
            if (inputData != null) {
                inputs.put("input_data", inputData);
                inputs.put("business_data", inputData);
                LOGGER.info("VersatileInterruptRail: ToolDataChannel hit key={}, input_key={}", channelKey, inputKey);
            } else {
                LOGGER.warn("VersatileInterruptRail: ToolDataChannel miss key={}, input_key={}", channelKey, inputKey);
                inputs.put("input_data", Map.of());
                inputs.put("business_data", Map.of());
            }
        }
        return inputs;
    }

    private String readCachedQuery(ToolDataKey channelKey) {
        Object cached = toolDataChannel.getObject(channelKey, McpInterruptRail.VERSATILE_QUERY_KEY);
        if (cached instanceof String text) {
            return text;
        }
        if (cached instanceof Map<?, ?> map) {
            Object value = map.get("query_description");
            if (value == null) {
                value = map.get("query");
            }
            return value != null ? String.valueOf(value) : "";
        }
        return "";
    }

    private String resolveUrl(String conversationId) {
        String resolved = versatileConfig.getUrl().replace("{conversation_id}", safePathSegment(conversationId));
        if (versatileConfig.getUrlVariables() != null) {
            for (Map.Entry<String, String> entry : versatileConfig.getUrlVariables().entrySet()) {
                resolved = resolved.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        StringBuilder url = new StringBuilder(resolved);
        if (versatileConfig.getQueryParams() != null && !versatileConfig.getQueryParams().isEmpty()) {
            boolean first = !resolved.contains("?");
            for (Map.Entry<String, String> entry : versatileConfig.getQueryParams().entrySet()) {
                url.append(first ? '?' : '&').append(entry.getKey()).append('=').append(entry.getValue());
                first = false;
            }
        }
        return url.toString();
    }

    private String safePathSegment(String value) {
        return value.replace("/", "_").replace("\\", "_").replace("..", "_");
    }

    private boolean hasHeader(Map<String, String> headers, String name) {
        return headers.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(name));
    }

    private Duration parseTimeout(String timeout) {
        if (timeout == null || timeout.isBlank()) {
            return Duration.ofSeconds(30);
        }
        String value = timeout.trim().toLowerCase();
        if (value.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
        }
        if (value.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
        }
        if (value.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1)));
        }
        return Duration.parse(timeout);
    }

    private String normalizeContent(String body) throws Exception {
        String raw = body != null ? body.trim() : "";
        if (raw.isBlank()) {
            return "{}";
        }
        JsonNode sseResult = extractFromSse(raw);
        if (sseResult != null) {
            return OBJECT_MAPPER.writeValueAsString(sseResult);
        }
        String candidate = raw;
        JsonNode node = OBJECT_MAPPER.readTree(candidate);
        if (node.isObject() || node.isArray()) {
            JsonNode extracted = extractStandardJsonContent(node);
            return OBJECT_MAPPER.writeValueAsString(extracted);
        }
        throw new IllegalArgumentException("versatile content is not standard JSON object or array");
    }

    private JsonNode extractFromSse(String raw) throws Exception {
        JsonNode lastJson = null;
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String payload = trimmed.substring(5).trim();
            if (payload.isBlank() || "[DONE]".equals(payload)) {
                continue;
            }
            JsonNode node = OBJECT_MAPPER.readTree(payload);
            lastJson = node;
            if (node.has("text")) {
                JsonNode textResult = parseTextJson(node.get("text"));
                if (textResult != null) {
                    return textResult;
                }
            }
            if (node.has("data")) {
                JsonNode extracted = extractStandardJsonContent(node);
                if (extracted.isObject() || extracted.isArray()) {
                    return extracted;
                }
            }
        }
        return lastJson;
    }

    private JsonNode extractStandardJsonContent(JsonNode node) throws Exception {
        if (node.has("content")) {
            return parseJsonNodeOrReturn(node.get("content"));
        }
        if (node.has("answer")) {
            return parseJsonNodeOrReturn(node.get("answer"));
        }
        if (node.has("data")) {
            JsonNode data = node.get("data");
            if (data.has("content")) {
                return parseJsonNodeOrReturn(data.get("content"));
            }
            if (data.has("answer")) {
                return parseJsonNodeOrReturn(data.get("answer"));
            }
            if (data.has("outputs")) {
                return parseJsonNodeOrReturn(data.get("outputs"));
            }
        }
        return node;
    }

    private JsonNode parseTextJson(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(node.asText());
            return parsed.isObject() || parsed.isArray() ? parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode parseJsonNodeOrReturn(JsonNode node) throws Exception {
        if (node == null || node.isNull()) {
            return OBJECT_MAPPER.createObjectNode();
        }
        if (node.isObject() || node.isArray()) {
            return node;
        }
        if (node.isTextual()) {
            JsonNode parsed = OBJECT_MAPPER.readTree(node.asText());
            if (parsed.isObject() || parsed.isArray()) {
                return parsed;
            }
        }
        ObjectNode wrapper = OBJECT_MAPPER.createObjectNode();
        wrapper.set("value", node);
        return wrapper;
    }

    private String abbreviate(String value) {
        if (value == null || value.length() <= 2000) {
            return value;
        }
        return value.substring(0, 2000) + "...(truncated)";
    }

    /** 安全转 String：null 返回空串。 */
    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 安全转 int：null 或非数字返回默认值。 */
    private int asInt(Object value, int defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // 落入默认值
            }
        }
        return defaultValue;
    }

    /** 把任意 Map 归一为 String 键的 Map。 */
    private Map<String, Object> toStringKeyMap(Object source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source instanceof Map<?, ?> map) {
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
        }
        return result;
    }

    private Map<String, Object> failedResult(String error) {
        return Map.of("source", "versatile", "status", "failed", "error", error != null ? error : "unknown");
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /**
     * 工具调用后回调。
     *
     * <p>职责一：history_info 持久化（对齐 Python versatile_interrupt_rail.py 第 295-321 行）。</p>
     * <p>当前 adapter 归一化结果（{@code normalizeA2aAdapterResponse} 产出）顶层不含 history_info，
     * 此处为未来归一化脚本落地后的预留接入点：脚本输出含 history_info 时自动持久化到 ToolDataChannel，
     * 供下一次 call_mcp 的 buildSkillInput 读取，形成跨工具的会话级持久化闭环。
     * 持久化后从 toolResult/toolMsg 出口剔除该字段，避免 LLM 看到内部字段（对齐 Python 第 321 行）。</p>
     *
     * <p>职责二：记录 call_versatile 完成事件。</p>
     *
     * @param ctx OpenJiuwen 回调上下文，包含工具调用和工具结果信息
     */
    @Override
    public void afterToolCall(AgentCallbackContext ctx) {
        // 关键判断：只有工具调用上下文才需要识别 VA 委托工具。
        if (!(ctx.getInputs() instanceof ToolCallInputs inputs)) {
            return;
        }
        String toolName = inputs.getToolName();

        // 关键判断：只处理 call_versatile。
        if (!"call_versatile".equals(toolName)) {
            return;
        }

        // ── history_info 持久化 + 出口剔除 ──
        persistHistoryInfoIfPresent(ctx, inputs);

        LOGGER.info("VersatileInterruptRail: call_versatile completed, cascade result received");
    }

    /**
     * 检查 toolResult 顶层是否含 history_info，有则持久化到 ToolDataChannel 并从出口剔除。
     *
     * <p>对齐 Python 第 297-306 行持久化、第 321 行剔除。无 history_info 时仅打 persistence check 日志，
     * 与 Python 行为一致。持久化 key 与 {@link McpInterruptRail#HISTORY_INFO_KEY} 同名，
     * 共享同一四元组隔离，下一次 call_mcp 能读到。</p>
     */
    private void persistHistoryInfoIfPresent(AgentCallbackContext ctx, ToolCallInputs inputs) {
        Object rawResult = inputs.getToolResult();
        if (!(rawResult instanceof Map<?, ?> rawMap)) {
            LOGGER.info("VersatileInterruptRail: persistence check history_info=not in result");
            return;
        }
        Map<String, Object> result = toStringKeyMap(rawMap);
        if (!result.containsKey(HISTORY_INFO_KEY)) {
            LOGGER.info("VersatileInterruptRail: persistence check history_info=not in result");
            return;
        }
        Object historyInfo = result.get(HISTORY_INFO_KEY);
        ToolDataKey channelKey = ToolDataKeyFactory.fromContext(ctx, edpConfig);
        // 包装为 {"value": ...} 结构，与 McpInterruptRail 读取时的解包逻辑对齐。
        toolDataChannel.store(channelKey, HISTORY_INFO_KEY,
                Map.of("value", historyInfo != null ? historyInfo : List.of()));
        LOGGER.info("VersatileInterruptRail: persistence check history_info=persisted:{}",
                abbreviate(String.valueOf(historyInfo)));
        // 出口剔除：从 toolResult 与 toolMsg 中移除，避免 LLM 看到内部字段。
        result.remove(HISTORY_INFO_KEY);
        inputs.setToolResult(result);
        ToolCall toolCall = inputs.getToolCall();
        if (toolCall != null && toolCall.getId() != null) {
            inputs.setToolMsg(ToolMessage.builder()
                    .content(toJson(result))
                    .toolCallId(toolCall.getId())
                    .build());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // pre-delegate guard
    // 对齐 Python versatile_interrupt_rail.py 第 421-525 行：
    // 在真正委托 adapter 之前，从 skill 脚本静态读取 PRE_DELEGATE_GUARD 配置，
    // 按 query_intent 维度计数，超限则终止当前 ReAct 流程，避免真实业务（如转账）被执行。
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 应用 pre-delegate guard。
     *
     * @param ctx  回调上下文
     * @param args call_versatile 工具参数（含 query_intent / query_response_analysis_scripts 等）
     * @return 超限判定；null 表示无 guard 或未超限，主流程继续
     */
    private GuardDecision applyPreDelegateGuard(AgentCallbackContext ctx, Map<String, Object> args) {
        String command = asString(args.get("query_response_analysis_scripts"));
        Map<String, Object> guard = loadPreDelegateGuard(command);
        if (guard == null || guard.isEmpty()) {
            return null;
        }
        Object rulesObj = guard.get("rules");
        if (!(rulesObj instanceof List<?> rules) || rules.isEmpty()) {
            return null;
        }
        ToolDataKey channelKey = ToolDataKeyFactory.fromContext(ctx, edpConfig);
        for (Object ruleObj : rules) {
            if (!(ruleObj instanceof Map<?, ?> rule)) {
                continue;
            }
            Map<String, Object> ruleMap = toStringKeyMap(rule);
            // match 表示这条规则只对哪些 tool_args 生效，例如 {"query_intent": "快速转账"}。
            // 全部键值匹配才命中（等价 Python any(...) 取反的循环逻辑）。
            Map<String, Object> match = toStringKeyMap(ruleMap.get("match"));
            if (!match.isEmpty() && !matchesArgs(args, match)) {
                continue;
            }

            String ruleId = asString(ruleMap.getOrDefault("id", "default"));
            // state_key 用 command:ruleId 唯一标识，避免不同 skill 的同名规则互相干扰。
            String stateKey = GUARD_STATE_KEY_PREFIX + command + ":" + ruleId;
            int count = incrementGuardCount(channelKey, stateKey);
            int maxCalls = asInt(ruleMap.get("max_calls"), 0);
            LOGGER.info("VersatileInterruptRail: pre-delegate guard matched rule={}, count={}, limit={}, match={}",
                    ruleId, count, maxCalls, match);
            if (maxCalls > 0 && count > maxCalls) {
                String message = resolveGuardMessage(ruleMap);
                return new GuardDecision(true, ruleId, count, maxCalls, message);
            }
        }
        return null;
    }

    /**
     * 静态读取脚本中的 {@code PRE_DELEGATE_GUARD = {...}} 配置。
     *
     * <p>对齐 Python {@code _load_pre_delegate_guard}（第 477-525 行）：不 import、不执行脚本，
     * 只静态解析字面量。Java 无 Python {@code ast} 模块，这里用括号配对截取字典字面量 + Jackson 解析，
     * 安全性与 {@code ast.literal_eval} 等价（不执行任意代码）。</p>
     *
     * <p>跳过条件（与 Python 一致）：命令中无 .py 脚本、skillsDir 为 null、脚本越界、脚本不存在、
     * 脚本中无 PRE_DELEGATE_GUARD 赋值。</p>
     *
     * @param command query_response_analysis_scripts 命令字符串
     * @return guard 配置 Map；空 Map 表示跳过
     */
    private Map<String, Object> loadPreDelegateGuard(String command) {
        String script = extractScriptName(command);
        if (script == null || script.isBlank()) {
            LOGGER.info("VersatileInterruptRail: pre-delegate guard skipped, no script in command={}", command);
            return Map.of();
        }
        if (skillsDir == null) {
            LOGGER.info("VersatileInterruptRail: pre-delegate guard skipped, skillsDir is null");
            return Map.of();
        }
        Path scriptPath = skillsDir.resolve(script).toAbsolutePath().normalize();
        // 路径越界保护：解析后的路径必须在 skillsDir 之下。
        if (!scriptPath.startsWith(skillsDir)) {
            LOGGER.warn("VersatileInterruptRail: pre-delegate guard skipped, script outside skills dir, path={}",
                    scriptPath);
            return Map.of();
        }
        if (!Files.exists(scriptPath) || !Files.isRegularFile(scriptPath)) {
            LOGGER.info("VersatileInterruptRail: pre-delegate guard skipped, script not found, path={}", scriptPath);
            return Map.of();
        }
        try {
            String source = Files.readString(scriptPath, StandardCharsets.UTF_8);
            String literal = extractAssignLiteral(source, "PRE_DELEGATE_GUARD");
            if (literal == null) {
                LOGGER.info("VersatileInterruptRail: pre-delegate guard skipped, PRE_DELEGATE_GUARD not found, path={}",
                        scriptPath);
                return Map.of();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(literal, Map.class);
            int ruleCount = parsed.get("rules") instanceof List<?> l ? l.size() : 0;
            LOGGER.info("VersatileInterruptRail: pre-delegate guard loaded, path={}, rules={}", scriptPath, ruleCount);
            return parsed;
        } catch (Exception e) {
            LOGGER.warn("VersatileInterruptRail: pre-delegate guard parse failed, path={}, err={}",
                    scriptPath, e.getMessage());
            return Map.of();
        }
    }

    /**
     * 从命令字符串中提取脚本文件名（第一个以 .py 结尾的 token）。
     * 对齐 Python 第 480 行 {@code next((part for part in command.split() if part.endswith(".py")), "")}。
     */
    private String extractScriptName(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        for (String token : command.split("\\s+")) {
            if (token.endsWith(".py")) {
                return token;
            }
        }
        return "";
    }

    /**
     * 从 Python 源码中截取 {@code name = {...}} 赋值的字典字面量。
     *
     * <p>实现：定位 {@code name = } 后第一个 {@code {}，按括号配对截取到匹配的 {@code }}。
     * 字符串字面量内的括号不计入配对（感知单/双引号与转义），避免误截。
     * 替代 Python {@code ast.literal_eval}——Java 无 AST 模块，但 PRE_DELEGATE_GUARD 是
     * 标准 JSON-compatible dict 字面量，括号配对足够且不执行任意代码。</p>
     *
     * @return 字典字面量字符串（含外层花括号）；未找到返回 null
     */
    private String extractAssignLiteral(String source, String name) {
        int assignIdx = source.indexOf(name + " =");
        if (assignIdx < 0) {
            assignIdx = source.indexOf(name + "=");
        }
        if (assignIdx < 0) {
            return null;
        }
        int braceStart = source.indexOf('{', assignIdx + name.length());
        if (braceStart < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        char quoteChar = 0;
        for (int i = braceStart; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (inString) {
                if (ch == '\\') {
                    i++; // 跳过转义字符
                    continue;
                }
                if (ch == quoteChar) {
                    inString = false;
                }
                continue;
            }
            if (ch == '\'' || ch == '"') {
                inString = true;
                quoteChar = ch;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(braceStart, i + 1);
                }
            }
        }
        return null;
    }

    /**
     * 判断 tool_args 是否全部命中 match 中的键值。
     * 对齐 Python 第 430 行 {@code any(tool_args.get(key) != value for key, value in match.items())} 的反向逻辑
     * （Python 用 any+!= 表示"任一不匹配则跳过"，此处用 all+equals 表示"全部匹配才命中"）。
     */
    private boolean matchesArgs(Map<String, Object> args, Map<String, Object> match) {
        for (Map.Entry<String, Object> entry : match.entrySet()) {
            Object actual = args.get(entry.getKey());
            Object expected = entry.getValue();
            if (actual == null ? expected != null : !actual.equals(expected)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 从 ToolDataChannel 读取并递增 guard 计数器。
     *
     * <p>对齐 Python {@code ctx.session.get_state(state_key)} + {@code update_state}。
     * Java 端 {@code ctx.getExtra()} 是 per-request、跨轮丢失，故用 ToolDataChannel（会话级四元组隔离）
     * 作为计数器存储，与 history_info / versatile_query 共享同一 channel。</p>
     */
    private int incrementGuardCount(ToolDataKey channelKey, String stateKey) {
        Object current = toolDataChannel.getObject(channelKey, stateKey);
        int count = (current instanceof Number n ? n.intValue() : 0) + 1;
        toolDataChannel.store(channelKey, stateKey, count);
        return count;
    }

    /**
     * 解析 guard 超限话术：优先取 {@code response_template_key} 对应的话术模板，
     * 缺失或为空则回落到规则的 {@code fallback_message}。
     *
     * <p>对齐 Python 第 444-449 行：
     * {@code text = scripts.get_response_template(template_key) or rule.get("fallback_message", "")}。
     * Java 端 {@code SysScriptsConfig.getTemplate(key)} 返回 null 表示缺失，等价 Python 的 falsy。</p>
     */
    private String resolveGuardMessage(Map<String, Object> ruleMap) {
        String templateKey = asString(ruleMap.get("response_template_key"));
        String text = "";
        if (scripts != null && templateKey != null && !templateKey.isBlank()) {
            String resolved = scripts.getTemplate(templateKey);
            if (resolved != null && !resolved.isBlank()) {
                text = resolved;
            }
        }
        if (text.isEmpty()) {
            text = asString(ruleMap.get("fallback_message"));
        }
        return text;
    }

    /** guard 判定结果。blocked=true 表示超限需终止；其余字段仅用于日志。 */
    private record GuardDecision(boolean blocked, String ruleId, int count, int maxCalls, String message) {
    }

    /**
     * 会话级 Versatile USER 透传缓冲。
     *
     * <p>Rail 在 adapter 响应解析阶段写入完整 message JSON；
     * {@link com.huawei.ascend.edp.handler.EdpaRuntimeHandler} 的流式迭代器
     * 在 DeepAgent 帧之间按 FIFO 刷出，避免 node_type/menu_type 等字段被降维丢失。</p>
     */
    public static final class VersatilePassthroughBuffer {

        private final Map<String, Deque<String>> nodesByConversation = new HashMap<>();
        /** 中断时的 toolCallId，续传完成后用于构造 InteractiveInput。 */
        private final Map<String, String> interruptIdsByConversation = new HashMap<>();

        public void addAll(String conversationId, Collection<String> nodes) {
            if (conversationId == null || conversationId.isBlank() || nodes == null || nodes.isEmpty()) {
                return;
            }
            synchronized (nodesByConversation) {
                Deque<String> queue = nodesByConversation.computeIfAbsent(conversationId, ignored -> new ArrayDeque<>());
                for (String node : nodes) {
                    if (node != null && !node.isBlank()) {
                        queue.addLast(node);
                    }
                }
            }
        }

        public String poll(String conversationId) {
            if (conversationId == null || conversationId.isBlank()) {
                return null;
            }
            synchronized (nodesByConversation) {
                Deque<String> queue = nodesByConversation.get(conversationId);
                if (queue == null) {
                    return null;
                }
                String node = queue.pollFirst();
                if (queue.isEmpty()) {
                    nodesByConversation.remove(conversationId);
                }
                return node;
            }
        }

        public boolean hasPending(String conversationId) {
            if (conversationId == null || conversationId.isBlank()) {
                return false;
            }
            synchronized (nodesByConversation) {
                Deque<String> queue = nodesByConversation.get(conversationId);
                return queue != null && !queue.isEmpty();
            }
        }

        public void clear(String conversationId) {
            if (conversationId != null && !conversationId.isBlank()) {
                synchronized (nodesByConversation) {
                    nodesByConversation.remove(conversationId);
                }
            }
        }

        public void rememberInterruptId(String conversationId, String interruptId) {
            if (conversationId == null || conversationId.isBlank()
                    || interruptId == null || interruptId.isBlank()) {
                return;
            }
            synchronized (interruptIdsByConversation) {
                interruptIdsByConversation.put(conversationId, interruptId);
            }
        }

        public String pollInterruptId(String conversationId) {
            if (conversationId == null || conversationId.isBlank()) {
                return null;
            }
            synchronized (interruptIdsByConversation) {
                return interruptIdsByConversation.remove(conversationId);
            }
        }
    }
}
