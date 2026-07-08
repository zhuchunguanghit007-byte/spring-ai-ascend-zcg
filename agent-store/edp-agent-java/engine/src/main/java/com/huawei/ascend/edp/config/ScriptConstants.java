package com.huawei.ascend.edp.config;

/**
 * 话术层常量集中地（extra key / 话术配置 key / status 值）。
 *
 * <p>所有 Rail / ScriptResolver 引用话术相关字符串时必须使用此常量类，
 * 禁止在代码中使用字符串字面量。参照 {@link ToolConstants} 模式。</p>
 *
 * <h2>配置驱动常量说明</h2>
 * <p>以下常量不再硬编码键名，而是作为 API 契约供调用方使用。
 * 实际键名通过场景配置 {@code scriptconfig.yaml} 中的 {@code script_keys} 映射：</p>
 * <pre>
 * # 配置示例
 * scriptconfig:
 *   script_keys:
 *     fund_planning_success: "loan_success"  # 覆盖键名
 *   loan_success: "贷款申请已提交..."         # 实际话术内容
 * </pre>
 * <p>调用方使用 {@link SysScriptsConfig#getScriptOrDefault(String, String)} 时，
 * 内部通过 {@link SysScriptsConfig#resolveScriptKey(String)} 将常量名映射到配置中的实际键名。</p>
 *
 * @see SysScriptsConfig#resolveScriptKey(String)
 * @see SysScriptsConfig#getScriptOrDefault(String, String)
 * @see SysScriptsConfig#hasScript(String)
 */
public final class ScriptConstants {

    private ScriptConstants() {}

    // ── ctx.getExtra() 内的 key（话术载体 + 控制信号）──

    /** 话术载体键：本请求内业务话术文本（供 EdpaEventRail.interrupt_start 与 ScriptsRail 出口读取）。 */
    public static final String KEY_RESPONSE_TEMPLATE = "_edp_response_template";
    /** 上次命中的话术 key（合规把关用）。 */
    public static final String KEY_LAST_SCRIPT = "_edp_last_script_key";
    /** ask_user confirm 时落选品变量（对齐 Python selected_product）。 */
    public static final String KEY_SELECTED_PRODUCT = "_edp_selected_product";
    /** cancel reason（对齐 Python cancel_reason）。 */
    public static final String KEY_CANCEL_REASON = "_edp_cancel_reason";

    // ── 既有 extra key（从 EdpaTodoRail/EdpaEventRail 硬编码收编到此）──

    public static final String KEY_SKIP_TOOL = "_skip_tool";
    public static final String KEY_PLAN_FIRST_BLOCK = "_plan_first_block";
    public static final String KEY_CHECKPOINT_RELEASE = "_edp_checkpoint_release";
    /** planning_start per-request 去重标记（解耦后仅真正进入规划时发一次）。 */
    public static final String KEY_PLANNING_START_SENT = "_edp_planning_start_sent";

    // ── 话术配置 key（生命周期类，与 EdpaEventType.wireName 一致）──
    // 用 EdpaEventType 枚举引用，不在此重复定义，避免双源

    // ── 话术配置 key（业务类，ScriptsConfig.yaml 顶层平铺 key）──

    public static final String SCRIPT_REQUEST_START = "request_start";
    public static final String SCRIPT_PLANNING_START = "planning_start";
    public static final String SCRIPT_TASK_CANCELLED = "task_cancelled";
    public static final String SCRIPT_CANCEL_CONFIRM = "cancel_confirm";
    public static final String SCRIPT_OUT_OF_SCOPE = "out_of_scope";
    // 业务话术键（product_select_confirm 等）由各 SKILL.yaml scripts 字段定义，
    // 通过 SkillScriptsCollector 动态收集到 templates，不再在此硬编码。

    // ── ask_user response_template 参数 key（LLM 工具入参字段名）──

    public static final String PARAM_RESPONSE_TEMPLATE_STATUS = "response_template_status";
    public static final String PARAM_RESPONSE_TEMPLATE_KEYS = "response_template_keys";
    public static final String PARAM_RESPONSE_TEMPLATE_VARS = "response_template_vars";

    // ── status 值（ask_user response_template_status 的取值）──

    public static final String STATUS_CONFIRM = "confirm";
    public static final String STATUS_MISSING_AMOUNT = "missing_amount";
    public static final String STATUS_MISSING_PRODUCT = "missing_product";
    public static final String STATUS_OUT_OF_SCOPE = "out_of_scope";

    // ── 结果 status 值（call_versatile / call_mcp toolResult.status 取值）──

    public static final String RESULT_SUCCESS = "success";
    public static final String RESULT_COMPLETED = "completed";
    public static final String RESULT_FAILED = "failed";
}
