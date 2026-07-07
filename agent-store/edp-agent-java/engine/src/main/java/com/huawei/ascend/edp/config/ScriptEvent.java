package com.huawei.ascend.edp.config;

/**
 * 脚本事件枚举，用于标记特定话术场景（对应需求文档 §A 的五个特定事件）。
 */
public enum ScriptEvent {
    REQUEST_START("request_start"),
    PLANNING_START("planning_start"),
    TASK_CANCELLED("task_cancelled"),
    CANCEL_CONFIRM("cancel_confirm"),
    OUT_OF_SCOPE("out_of_scope");

    private final String key;

    ScriptEvent(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
