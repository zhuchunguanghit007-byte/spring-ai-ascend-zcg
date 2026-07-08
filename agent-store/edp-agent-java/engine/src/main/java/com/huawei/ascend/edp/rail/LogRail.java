package com.huawei.ascend.edp.rail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.edp.config.EdpConfig;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.llm.schema.UsageMetadata;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * EDPAgent 观测日志 Rail。
 *
 * <p>文件作用：</p>
 * <ul>
 *     <li>记录模型调用前的消息数量和最后一条消息。</li>
 *     <li>记录模型调用后的响应对象。</li>
 *     <li>记录模型 token 使用量，支撑端到端穿刺证据收集。</li>
 *     <li>记录工具调用完成事件。</li>
 *     <li>afterModelCall 中对 tool_call.arguments 做非法 JSON 自动修复（对齐 Python LogRail._repair_tool_call_arguments）。</li>
 * </ul>
 *
 * <p>对外提供的接口：</p>
 * <ul>
 *     <li>{@link #LogRail(EdpConfig)}：创建日志 Rail。</li>
 *     <li>{@link #beforeModelCall(AgentCallbackContext)}：模型调用前回调。</li>
 *     <li>{@link #afterModelCall(AgentCallbackContext)}：模型调用后回调。</li>
 *     <li>{@link #afterToolCall(AgentCallbackContext)}：工具调用后回调。</li>
 * </ul>
 */
public class LogRail extends AgentRail {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogRail.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * EDP 专有配置，当前预留给后续日志脱敏、采样和开关控制使用。
     */
    private final EdpConfig edpConfig;

    /**
     * 构造日志 Rail。
     *
     * @param edpConfig EDP 专有配置
     */
    public LogRail(EdpConfig edpConfig) {
        this.edpConfig = edpConfig;
        // 日志 Rail 使用较低优先级，尽量在其他业务 Rail 完成处理后记录最终上下文。
        setPriority(10);
    }

    /**
     * 模型调用前回调。
     *
     * @param ctx OpenJiuwen 回调上下文，包含模型入参
     */
    @Override
    public void beforeModelCall(AgentCallbackContext ctx) {
        // 关键判断：只有模型调用上下文才记录模型输入。
        if (ctx.getInputs() instanceof ModelCallInputs inputs) {
            Object lastMessage = inputs.getMessages().isEmpty() ? null : inputs.getMessages().get(inputs.getMessages().size() - 1);
            LOGGER.info("E2E_MODEL_INPUT messageCount={}, lastMessage={}", inputs.getMessages().size(), lastMessage);
        }
    }

    /**
     * 模型调用后回调。
     *
     * <p>除记录响应和 usage 外，还对 {@code tool_call.arguments} 做非法 JSON 自动修复。
     * LLM streaming 偶发会少生成 args 末尾的闭合 {@code }}，导致下一轮 LLM 调用 messages
     * 带着坏 args 触发严格网关 400。此修复在 ReActAgent 持久化 AssistantMessage 之前
     * 执行，确保持久化的是合法 JSON。</p>
     *
     * @param ctx OpenJiuwen 回调上下文，包含模型响应
     */
    @Override
    public void afterModelCall(AgentCallbackContext ctx) {
        // 关键判断：非模型调用上下文只记录完成事件，不读取模型响应。
        if (!(ctx.getInputs() instanceof ModelCallInputs inputs)) {
            LOGGER.info("LogRail: model call completed, model response received");
            return;
        }
        Object response = inputs.getResponse();
        LOGGER.info("E2E_MODEL_OUTPUT response={}", response);

        // 关键判断：只有 AssistantMessage 响应才可能携带 usage 元数据和 tool_calls。
        if (response instanceof AssistantMessage assistantMessage) {
            repairToolCallArguments(assistantMessage);
            UsageMetadata usage = assistantMessage.getUsageMetadata();
            if (usage != null) {
                LOGGER.info("E2E_MODEL_USAGE inputTokens={}, outputTokens={}, totalTokens={}, model={}",
                        usage.getInputTokens(), usage.getOutputTokens(), usage.getTotalTokens(), usage.getModelName());
            } else {
                LOGGER.info("E2E_MODEL_USAGE null");
            }
        }
    }

    /**
     * 工具调用后回调。
     *
     * @param ctx OpenJiuwen 回调上下文，包含工具调用信息
     */
    @Override
    public void afterToolCall(AgentCallbackContext ctx) {
        // 关键判断：只有工具调用上下文才记录工具名。
        if (ctx.getInputs() instanceof ToolCallInputs inputs) {
            LOGGER.info("LogRail: tool call completed, toolName={}", inputs.getToolName());
        }
    }

    /**
     * 对 response.tool_calls 里 arguments 不合法的 JSON 做 in-place 自动修复。
     *
     * <p>对齐 Python {@code LogRail._repair_tool_call_arguments}：遍历未闭合的 {@code {} / {@code [}
     * 栈底，按相反顺序补齐 {@code }} / {@code ]}。对"丢末尾 }"这种最常见的 LLM streaming quirk 100% 生效。
     * 命中时打 WARNING；修复后仍非法（极罕见）时打 ERROR。</p>
     */
    private static void repairToolCallArguments(AssistantMessage response) {
        List<ToolCall> toolCalls = response.getToolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }

        for (ToolCall toolCall : toolCalls) {
            String args = toolCall.getArguments();
            if (args == null || args.isBlank()) {
                continue;
            }

            // 合法直接跳过
            if (isValidJson(args)) {
                continue;
            }

            String toolName = toolCall.getName();
            LOGGER.info("LogRail: before repair | name={} | args_len={} | args={}",
                    toolName, args.length(), abbreviate(args, 200));

            String repaired = repairMalformedJson(args);
            if (repaired == null || repaired.equals(args)) {
                LOGGER.error("LogRail: FAILED to repair malformed tool_call.arguments | name={} | args_len={} | args_tail={}",
                        toolName, args.length(), abbreviateTail(args, 60));
                continue;
            }

            if (!isValidJson(repaired)) {
                LOGGER.error("LogRail: repaired args still invalid | name={} | repaired_tail={}",
                        toolName, abbreviateTail(repaired, 60));
                continue;
            }

            toolCall.setArguments(repaired);
            LOGGER.info("LogRail: after repair | name={} | repaired_len={} | repaired={}",
                    toolName, repaired.length(), abbreviate(repaired, 200));
            LOGGER.warn("LogRail: auto-repaired malformed tool_call.arguments | name={} | diff={} chars added | original_tail={} | repaired_tail={}",
                    toolName, repaired.length() - args.length(), abbreviateTail(args, 40), abbreviateTail(repaired, 40));
        }
    }

    private static boolean isValidJson(String json) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(json);
            return node != null && (node.isObject() || node.isArray());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 修复非法 JSON：遍历字符串，维护未闭合的 {@code {} / {@code [} 栈（字符串字面量内的括号忽略），
     * 按栈逆序补齐闭合符号。对齐 Python {@code AbilityManager._repair_tool_arguments_json}。
     *
     * @return 修复后的 JSON 字符串；输入为空或无未闭合括号时返回 null
     */
    private static String repairMalformedJson(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }

        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        char stringDelimiter = 0;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == stringDelimiter) {
                    inString = false;
                }
                continue;
            }

            if (ch == '"' || ch == '\'') {
                inString = true;
                stringDelimiter = ch;
            } else if (ch == '{' || ch == '[') {
                stack.push(ch);
            } else if (ch == '}' || ch == ']') {
                // 弹出匹配的开括号；不匹配则忽略（避免误修）
                char expectedOpen = (ch == '}') ? '{' : '[';
                if (!stack.isEmpty() && stack.peek() == expectedOpen) {
                    stack.pop();
                }
            }
        }

        if (stack.isEmpty()) {
            return null;
        }

        StringBuilder repaired = new StringBuilder(json);
        while (!stack.isEmpty()) {
            char open = stack.pop();
            repaired.append(open == '{' ? '}' : ']');
        }
        return repaired.toString();
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return "null";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...(truncated)";
    }

    private static String abbreviateTail(String value, int tail) {
        if (value == null) {
            return "null";
        }
        return value.length() <= tail ? value : "..." + value.substring(value.length() - tail);
    }
}
