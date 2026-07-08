package com.huawei.ascend.edp.rail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.edp.config.EdpaEventType;
import com.huawei.ascend.edp.config.ScriptConstants;
import com.huawei.ascend.edp.config.ScriptResolver;
import com.huawei.ascend.edp.config.SysScriptsConfig;
import com.huawei.ascend.edp.config.ToolConstants;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.harness.rails.DeepAgentRail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 话术出口 Rail（B 面，priority=50）。
 *
 * <p>负责 {@link EdpaEventRail} 不发的话术：首轮开场、业务话术解析、出口发射、合规把关、Prompt 注入。
 * 最终出口，仅高于 LogRail(10)。话术规则集中在共享 {@link ScriptResolver}，本 Rail 保持薄。</p>
 *
 * <p>话术载体 = {@code ctx.getExtra()} 内的 {@code _edp_response_template} 键（与现有
 * {@code _skip_tool}/{@code _plan_first_block}/{@code _edp_checkpoint_release} 同通道，本工程无
 * session.state API）。在同一 HTTP 请求内写 + 读 + 发射，不跨轮持久化。</p>
 *
 * <p>六职责：</p>
 * <ol>
 *     <li>{@link #beforeInvoke}：首轮开场（request_start 不作为独立事件，对齐 Python 理念）。</li>
 *     <li>{@link #beforeToolCall}：ask_user 解析 {@code response_template_*} / cancel_task 写 reason → {@code _edp_response_template}。</li>
 *     <li>{@link #afterToolCall}：call_versatile/call_mcp 结果话术兜底。</li>
 *     <li>{@link #afterInvoke}：出口发射 {@code _edp_response_template}（对齐 Python 流末出口）。</li>
 *     <li>{@link #complianceGate}：配置外话术替换为 {@code out_of_scope}。</li>
 *     <li>{@link #init}：注入 cancel/business Prompt section（调 {@link ScriptResolver}）。</li>
 * </ol>
 */
public class ScriptsRail extends DeepAgentRail {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScriptsRail.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Prompt section 名 + 优先级（对齐 EdpaTodoRail 的 addPromptBuilderSection 模式）。 */
    private static final String CANCEL_RULES_SECTION = "edpa_scripts_cancel_rules";
    private static final String BUSINESS_RULES_SECTION = "edpa_scripts_business_rules";
    private static final int CANCEL_RULES_PRIORITY = 30;
    private static final int BUSINESS_RULES_PRIORITY = 40;

    /** 话术配置，可为 null（退化为不填话术，等价现状）。 */
    private final SysScriptsConfig scripts;

    /**
     * 构造话术 Rail。
     *
     * @param scripts 话术配置
     */
    public ScriptsRail(SysScriptsConfig scripts) {
        this.scripts = scripts;
    }

    /**
     * 构造话术 Rail（兼容含 edpConfig 的调用）。
     *
     * @param scripts 话术配置
     * @param edpConfig EDP 配置（UC-C04 scope 已通过 PlanrulePromptBuilder 注入 prompt，此处不使用）
     */
    public ScriptsRail(SysScriptsConfig scripts, com.huawei.ascend.edp.config.EdpConfig edpConfig) {
        this.scripts = scripts;
    }

    @Override
    public int priority() {
        return 50;
    }

    // ═══════════════════════════════════════════════════
    // ① 首轮开场（对齐 Python 理念：request_start 不作为独立事件）
    // ═══════════════════════════════════════════════════

    @Override
    public void beforeInvoke(AgentCallbackContext ctx) {
        // request_start 不作为独立事件发射（对齐 Python 理念）。
        // 首轮开场感知由 conversation_start 承载；出口话术通过 conversation_end content 输出。
        // planning 阶段提示由 think_chunk 固定帧话术承载。
        // UC-C04: 超范围检测由 LLM 通过 prompt 中的 scope.allowed/denied 自行判断（对齐 Python prompt.py），
        // complianceGate 兜底确保配置外话术被替换为 out_of_scope。
    }

    // ═══════════════════════════════════════════════════
    // ② 业务话术解析（cancel_task only；ask_user 由 EdpaEventRail.onToolException 解析）
    // ═══════════════════════════════════════════════════

    @Override
    public void beforeToolCall(AgentCallbackContext ctx) {
        if (scripts == null || !(ctx.getInputs() instanceof ToolCallInputs inputs)) {
            return;
        }
        String tool = inputs.getToolName();
        Map<String, Object> args = normalizeArgs(inputs.getToolArgs());

        // ask_user 话术解析已迁移至 EdpaEventRail.onToolException
        // （AskUserTemplateRail p=85 抛 ToolInterruptException 后此 Rail p=50 不可达）
        if (ToolConstants.CANCEL_TASK.equals(tool) && readResponseTemplate(ctx) == null) {
            resolveCancelScript(ctx, args);
        }
    }

    /**
     * cancel_task：写 {@code _edp_response_template}（forceFinish 由 CancelRail 中段执行，payload 非话术）。
     *
     * <p>reason 参数值需与 ScriptConstants 常量名一致（如 {@code "task_cancelled"}），
     * 内部通过 {@link SysScriptsConfig#resolveScriptKey(String)} 映射到实际配置键。</p>
     */
    private void resolveCancelScript(AgentCallbackContext ctx, Map<String, Object> args) {
        String reason = str(args.get("reason"));
        if (isBlank(reason)) {
            reason = "task_cancelled";
        }
        // 使用配置驱动的键映射
        String resolvedKey = scripts.resolveScriptKey("SCRIPT_" + reason.toUpperCase().replace("-", "_"));
        String text = scripts.getScriptOrDefault("SCRIPT_" + reason.toUpperCase().replace("-", "_"), "");
        if (isBlank(text)) {
            // 对齐 Python cancel_rail.py 第 43 行：话术 key 未找到时记录 warn 日志，不阻断取消流程。
            LOGGER.warn("[EDPA-SCRIPT] cancel_task response_template not found for reason={}, skip script injection", reason);
            return;
        }
        ctx.getExtra().put(ScriptConstants.KEY_RESPONSE_TEMPLATE, text);
        ctx.getExtra().put(ScriptConstants.KEY_LAST_SCRIPT, resolvedKey);
        ctx.getExtra().put(ScriptConstants.KEY_CANCEL_REASON, reason);
        LOGGER.info("[EDPA-SCRIPT] cancel_task resolved reason={} (resolved={}) -> response_template", reason, resolvedKey);
    }

    // ═══════════════════════════════════════════════════
    // ③ 业务结果话术兜底（call_versatile / call_mcp 结果）
    // ═══════════════════════════════════════════════════
    @Override
    public void afterToolCall(AgentCallbackContext ctx) {
        // 业务话术键由各 SKILL.yaml scripts 字段定义，通过 SkillScriptsCollector 动态收集。
        // LLM 通过 ask_user(response_template_keys=["..."]) 指定话术键，不再在此硬编码映射。
    }

    // ═══════════════════════════════════════════════════
    // ④ 出口发射 + ⑤ 合规把关（对齐 Python agent.py:551-556，修复 D2）
    // ═══════════════════════════════════════════════════

    @Override
    public void afterInvoke(AgentCallbackContext ctx) {
        // 出口 interrupt_start 已由 EdpaEventRail.afterInvoke（priority=80）在 conversation_end 之前发射。
        // 本 Rail（priority=50）不再重复发射，仅执行合规把关兜底（若 _edp_response_template 仍存在）。
        // 实际上 EdpaEventRail 已内置合规把关并清除 KEY_RESPONSE_TEMPLATE，此处通常直接 return。
        String text = readResponseTemplate(ctx);
        if (isBlank(text)) {
            return;
        }
        complianceGate(ctx);
        ctx.getExtra().remove(ScriptConstants.KEY_RESPONSE_TEMPLATE); // 清本请求残留
        LOGGER.info("[EDPA-SCRIPT] afterInvoke compliance gate done, response_template cleared");
    }

    /**
     * 合规把关：配置外话术（{@code _edp_last_script_key} 不在配置内）→ 替换为 {@code out_of_scope}。
     *
     * <p>注意：{@code _edp_last_script_key} 存储的是解析后的实际键名（非常量名），
     * 所以此处使用 {@link SysScriptsConfig#has(String)} 而非 {@link SysScriptsConfig#hasScript(String)}。</p>
     */
    private void complianceGate(AgentCallbackContext ctx) {
        Object k = ctx.getExtra().get(ScriptConstants.KEY_LAST_SCRIPT);
        String resolvedKey = k == null ? null : String.valueOf(k);
        if (scripts != null && resolvedKey != null && !scripts.has(resolvedKey)) {
            // _edp_last_script_key 存的是实际键名，直接查 has()
            ctx.getExtra().put(ScriptConstants.KEY_RESPONSE_TEMPLATE,
                    scripts.getScriptOrDefault("SCRIPT_OUT_OF_SCOPE", ""));
            LOGGER.info("[EDPA-SCRIPT] compliance gate replaced out-of-config key={} -> out_of_scope", resolvedKey);
        }
    }

    // ═══════════════════════════════════════════════════
    // ⑥ Prompt 注入（init 回调，调 ScriptResolver 静态方法）
    // ═══════════════════════════════════════════════════

    @Override
    public void init(Object agent) {
        if (scripts == null || !(agent instanceof ReActAgent reActAgent)) {
            return;
        }
        reActAgent.addPromptBuilderSection(CANCEL_RULES_SECTION,
                ScriptResolver.cancelRulesPrompt(scripts), CANCEL_RULES_PRIORITY);
        reActAgent.addPromptBuilderSection(BUSINESS_RULES_SECTION,
                ScriptResolver.businessRulesPrompt(scripts), BUSINESS_RULES_PRIORITY);
        LOGGER.info("[EDPA-SCRIPT] injected prompt sections: {}, {}", CANCEL_RULES_SECTION, BUSINESS_RULES_SECTION);
    }

    @Override
    public void uninit(Object agent) {
        if (!(agent instanceof ReActAgent reActAgent)) {
            return;
        }
        try {
            reActAgent.getPromptBuilder().removeSection(CANCEL_RULES_SECTION);
        } catch (Exception e) {
            LOGGER.debug("[EDPA-SCRIPT] uninit removeSection '{}' ignored: {}", CANCEL_RULES_SECTION, e.getMessage());
        }
        try {
            reActAgent.getPromptBuilder().removeSection(BUSINESS_RULES_SECTION);
        } catch (Exception e) {
            LOGGER.debug("[EDPA-SCRIPT] uninit removeSection '{}' ignored: {}", BUSINESS_RULES_SECTION, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════
    // 私有辅助
    // ═══════════════════════════════════════════════════

    /**
     * 首轮判定（待联调）：当前实现为「无 interrupt_end 恢复信号即视为首轮」。
     *
     * <p>Python 用 checkpoint/cascade；EventFlow 首轮信号来源待确认。本处保守判定：当
     * {@code ctx.getExtra()} 无 resume 标记时视为首轮。生产联调后可改为基于 session 历史。</p>
     */
    private boolean isFirstTurn(AgentCallbackContext ctx) {
        try {
            Object resume = ctx.getExtra().get(com.openjiuwen.core.singleagent.interrupt.ToolInterruptionState.RESUME_USER_INPUT_KEY);
            return resume == null;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 发射话术事件（北向 SSE，复用 EdpaEventRail.emit 同款格式）。
     */
    private void emitScript(AgentCallbackContext ctx, String eventType, String text) {
        if (isBlank(text)) {
            return;
        }
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("event", eventType);
            event.put("timestamp", System.currentTimeMillis());
            event.put("conversation_id", sessionId(ctx));
            event.put("content", text);
            ctx.getSession().writeStream(new OutputSchema("custom", 0, event));
        } catch (Exception e) {
            LOGGER.warn("[EDPA-SCRIPT] emitScript failed: {}", e.getMessage());
        }
    }

    private String readResponseTemplate(AgentCallbackContext ctx) {
        Object v = ctx.getExtra().get(ScriptConstants.KEY_RESPONSE_TEMPLATE);
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeArgs(Object rawArgs) {
        if (rawArgs instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        if (rawArgs instanceof String s && !s.isBlank()) {
            try {
                Map<String, Object> parsed = OBJECT_MAPPER.readValue(s, new TypeReference<LinkedHashMap<String, Object>>() {
                });
                return parsed != null ? parsed : new LinkedHashMap<>();
            } catch (Exception e) {
                LOGGER.debug("[EDPA-SCRIPT] failed to parse toolArgs as JSON: {}", e.getMessage());
            }
        }
        return new LinkedHashMap<>();
    }

    private static String sessionId(AgentCallbackContext ctx) {
        try {
            return ctx.getSession().getSessionId();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
