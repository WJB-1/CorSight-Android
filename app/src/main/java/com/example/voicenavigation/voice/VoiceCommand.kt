package com.example.voicenavigation.voice

/**
 * 语音指令数据模型。
 * 将用户的语音输入解析为结构化的指令对象，用于 Function Calling 执行。
 */
data class VoiceCommand(
    /** 指令类型，用于路由到对应的函数执行 */
    val type: Type,
    /** 目的地名称，仅在 type == NAVIGATE_TO 时有效 */
    val destination: String?,
    /** 用户的原始语音文本 */
    val rawText: String
) {

    /**
     * 判断该指令是否需要执行 Function Calling（即是否为可执行指令）。
     * TEXT_SEARCH 和 UNKNOWN 不属于 Function Calling 范畴，应走普通搜索流程。
     */
    fun isExecutableCommand(): Boolean =
        type != Type.TEXT_SEARCH && type != Type.UNKNOWN

    /**
     * 指令类型枚举。每种类别对应一个可调用的"函数"。
     */
    enum class Type(
        /** @suppress */ @JvmField val functionName: String,
        /** @suppress */ @JvmField val description: String
    ) {
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
        /** 金造村游览 —— 触发参观模式引导 */
        JINZAO_TOUR("jinzao_tour", "参观金造村"),
        /** 金造村路线：起义广场 */
        JINZAO_ROUTE_QIYI("jinzao_route_qiyi", "去起义广场"),
        /** 金造村路线：司令部旧址 */
        JINZAO_ROUTE_SILINGBU("jinzao_route_silingbu", "去司令部旧址"),
        /** 普通搜索（非指令，作为文本搜索） */
        TEXT_SEARCH("text_search", "普通文本搜索"),
        /** 无法识别的指令 */
        UNKNOWN("unknown", "未知指令")
    }

    override fun toString(): String =
        "VoiceCommand{type=$type, destination='$destination', rawText='$rawText'}"
}
