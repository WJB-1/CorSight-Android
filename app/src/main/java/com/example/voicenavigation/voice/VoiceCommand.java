package com.example.voicenavigation.voice;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 语音指令数据模型。
 * 将用户的语音输入解析为结构化的指令对象，用于 Function Calling 执行。
 */
public class VoiceCommand {

    /**
     * 指令类型枚举。每种类别对应一个可调用的"函数"。
     */
    public enum Type {
        /** 导航到指定目的地：参数为 destination */
        NAVIGATE_TO("navigate_to", "导航到指定地点"),
        /** 启动避障模式 */
        START_OBSTACLE_AVOIDANCE("start_obstacle_avoidance", "启动障碍物检测避障"),
        /** 停止当前导航 */
        STOP_NAVIGATION("stop_navigation", "停止当前导航"),
        /** 停止避障模式 */
        STOP_OBSTACLE_AVOIDANCE("stop_obstacle_avoidance", "停止障碍物检测避障"),
        /** 播报当前位置 */
        WHERE_AM_I("where_am_i", "播报当前所在位置"),
        /** 重复上一次播报内容 */
        REPEAT_LAST("repeat_last", "重复上一次语音播报"),
        /** 发送路线预览请求 */
        PREVIEW_ROUTE("preview_route", "预览当前路线"),
        /** 查询当前状态（导航中？避障中？） */
        QUERY_STATUS("query_status", "查询当前运行状态"),
        /** 普通搜索（非指令，作为文本搜索） */
        TEXT_SEARCH("text_search", "普通文本搜索"),
        /** 无法识别的指令 */
        UNKNOWN("unknown", "未知指令");

        private final String functionName;
        private final String description;

        Type(String functionName, String description) {
            this.functionName = functionName;
            this.description = description;
        }

        public String getFunctionName() {
            return functionName;
        }

        public String getDescription() {
            return description;
        }
    }

    private final Type type;
    private final String destination;
    private final String rawText;

    public VoiceCommand(@NonNull Type type, @Nullable String destination, @NonNull String rawText) {
        this.type = type;
        this.destination = destination;
        this.rawText = rawText;
    }

    /** @return 指令类型，用于路由到对应的函数执行 */
    @NonNull
    public Type getType() {
        return type;
    }

    /**
     * @return 目的地名称，仅在 type == NAVIGATE_TO 时有效
     */
    @Nullable
    public String getDestination() {
        return destination;
    }

    /** @return 用户的原始语音文本 */
    @NonNull
    public String getRawText() {
        return rawText;
    }

    /**
     * 判断该指令是否需要执行 Function Calling（即是否为可执行指令）。
     * TEXT_SEARCH 和 UNKNOWN 不属于 Function Calling 范畴，应走普通搜索流程。
     */
    public boolean isExecutableCommand() {
        return type != Type.TEXT_SEARCH && type != Type.UNKNOWN;
    }

    @NonNull
    @Override
    public String toString() {
        return "VoiceCommand{" +
                "type=" + type +
                ", destination='" + destination + '\'' +
                ", rawText='" + rawText + '\'' +
                '}';
    }
}
