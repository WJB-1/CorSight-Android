package com.example.voicenavigation.voice;

import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.List;

/**
 * 语音指令意图解析器。
 *
 * <p>将语音识别得到的文本解析为结构化的 {@link VoiceCommand}。
 * 采用<strong>本地关键词匹配</strong>策略，无需联网即可实时响应，
 * 适合盲人用户对响应速度的敏感需求。</p>
 *
 * <p>架构上预留了接入云端 LLM Function Calling 的扩展点：
 * 可将 {@link #interpret(String)} 替换为远程 LLM API 调用。</p>
 */
public class VoiceCommandInterpreter {

    private static final String TAG = "VoiceCommandInterpreter";

    // ==================== 意图关键词库 ====================

    /** 停止导航 */
    private static final List<String> STOP_NAV_KEYWORDS = Arrays.asList(
            "停止导航", "结束导航", "关闭导航", "取消导航", "不要导航了",
            "不导航了", "退出导航", "停下导航"
    );

    /** 开始避障 */
    private static final List<String> START_OBSTACLE_KEYWORDS = Arrays.asList(
            "开始避障", "打开避障", "启动避障", "避障模式", "检测障碍物",
            "障碍物检测", "进入避障", "开启避障", "避障"
    );

    /** 停止避障 */
    private static final List<String> STOP_OBSTACLE_KEYWORDS = Arrays.asList(
            "停止避障", "结束避障", "关闭避障", "退出避障", "取消避障",
            "不要避障了", "不避障了", "停下避障"
    );

    /** 我在哪里 */
    private static final List<String> WHERE_AM_I_KEYWORDS = Arrays.asList(
            "我在哪里", "我在哪儿", "当前位置", "我的位置", "这是哪里",
            "这是哪儿", "我在什么地方", "现在在哪"
    );

    /** 重复播报 */
    private static final List<String> REPEAT_KEYWORDS = Arrays.asList(
            "重复", "再说一遍", "重新播报", "再讲一遍", "再听一遍",
            "重复一遍", "刚才说什么", "再说一次"
    );

    /** 预览路线 */
    private static final List<String> PREVIEW_KEYWORDS = Arrays.asList(
            "预览路线", "路线预览", "行前预览", "预览导航", "看路线",
            "查看路线"
    );

    /** 查询状态 */
    private static final List<String> STATUS_KEYWORDS = Arrays.asList(
            "查询状态", "现在什么情况", "当前状态", "状态怎么样",
            "怎么样了", "什么情况", "在干什么"
    );

    /** 导航前缀：文本以这些词开头时，视为导航意图 */
    private static final List<String> NAVIGATE_PREFIXES = Arrays.asList(
            "导航到", "导航去", "带我去", "我要去", "我想去",
            "请带我去", "带我去一下", "去一下"
    );

    /** 导航后缀：文本以这些词结尾时，视为导航意图 */
    private static final List<String> NAVIGATE_SUFFIXES = Arrays.asList(
            "怎么走", "怎么去", "在哪里", "在哪儿", "的位置",
            "怎么去啊", "怎么走啊"
    );

    /**
     * 解析语音文本，返回结构化的语音指令。
     *
     * @param text 语音识别得到的原始文本
     * @return 解析后的 VoiceCommand，不会返回 null
     */
    @NonNull
    public VoiceCommand interpret(@NonNull String text) {
        String cleaned = cleanText(text);
        Log.d(TAG, "Interpreting: \"" + cleaned + "\"");

        // 1. 精确匹配：停止导航（优先级高，避免与"导航到"混淆）
        if (containsAny(cleaned, STOP_NAV_KEYWORDS)) {
            return new VoiceCommand(VoiceCommand.Type.STOP_NAVIGATION, null, cleaned);
        }

        // 2. 精确匹配：停止避障
        if (containsAny(cleaned, STOP_OBSTACLE_KEYWORDS)) {
            return new VoiceCommand(VoiceCommand.Type.STOP_OBSTACLE_AVOIDANCE, null, cleaned);
        }

        // 3. 精确匹配：开始避障
        if (containsAny(cleaned, START_OBSTACLE_KEYWORDS)) {
            return new VoiceCommand(VoiceCommand.Type.START_OBSTACLE_AVOIDANCE, null, cleaned);
        }

        // 4. 精确匹配：我在哪里
        if (containsAny(cleaned, WHERE_AM_I_KEYWORDS)) {
            return new VoiceCommand(VoiceCommand.Type.WHERE_AM_I, null, cleaned);
        }

        // 5. 精确匹配：重复播报
        if (containsAny(cleaned, REPEAT_KEYWORDS)) {
            return new VoiceCommand(VoiceCommand.Type.REPEAT_LAST, null, cleaned);
        }

        // 6. 精确匹配：预览路线
        if (containsAny(cleaned, PREVIEW_KEYWORDS)) {
            return new VoiceCommand(VoiceCommand.Type.PREVIEW_ROUTE, null, cleaned);
        }

        // 7. 精确匹配：查询状态
        if (containsAny(cleaned, STATUS_KEYWORDS)) {
            return new VoiceCommand(VoiceCommand.Type.QUERY_STATUS, null, cleaned);
        }

        // 8. 导航意图：提取目的地
        String destination = extractDestination(cleaned);
        if (destination != null && !destination.isEmpty()) {
            Log.d(TAG, "Parsed NAVIGATE_TO destination: " + destination);
            return new VoiceCommand(VoiceCommand.Type.NAVIGATE_TO, destination, cleaned);
        }

        // 9.  fallback：作为普通文本搜索
        if (cleaned.length() >= 2) {
            Log.d(TAG, "Falling back to TEXT_SEARCH: " + cleaned);
            return new VoiceCommand(VoiceCommand.Type.TEXT_SEARCH, cleaned, cleaned);
        }

        Log.d(TAG, "Unknown command: " + cleaned);
        return new VoiceCommand(VoiceCommand.Type.UNKNOWN, null, cleaned);
    }

    /**
     * 清洗语音文本：去首尾标点、去语气词。
     */
    @NonNull
    private String cleanText(@NonNull String text) {
        String cleaned = text.trim()
                .replaceAll("^[。，、！；：,.!?;:]+", "")
                .replaceAll("[。，、！；：,.!?;:]+$", "")
                .replaceAll("^[吧吗呢啊]+", "")
                .replaceAll("[吧吗呢啊]+$", "");
        return cleaned.trim();
    }

    /**
     * 判断文本是否包含关键词列表中的任意一个。
     */
    private boolean containsAny(@NonNull String text, @NonNull List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从文本中提取导航目的地。
     * 支持前缀形式（"导航到天安门"）和后缀形式（"天安门怎么走"）。
     *
     * @return 提取到的目的地，如果不是导航意图则返回 null
     */
    private String extractDestination(@NonNull String text) {
        // 前缀匹配："导航到XX"、"带我去XX" 等
        for (String prefix : NAVIGATE_PREFIXES) {
            if (text.startsWith(prefix)) {
                String dest = text.substring(prefix.length()).trim()
                        .replaceAll("^[的吧吗呢啊]+", "")
                        .replaceAll("[的吧吗呢啊]+$", "")
                        .trim();
                if (!dest.isEmpty()) {
                    return dest;
                }
            }
        }

        // 后缀匹配："XX怎么走"、"XX怎么去" 等
        for (String suffix : NAVIGATE_SUFFIXES) {
            if (text.endsWith(suffix)) {
                String dest = text.substring(0, text.length() - suffix.length()).trim()
                        .replaceAll("^[的吧吗呢啊]+", "")
                        .replaceAll("[的吧吗呢啊]+$", "")
                        .trim();
                if (!dest.isEmpty()) {
                    return dest;
                }
            }
        }

        // 特殊处理：单独的 "去XX"
        if (text.startsWith("去") && text.length() > 1) {
            String dest = text.substring(1).trim()
                    .replaceAll("^[的吧吗呢啊]+", "")
                    .replaceAll("[的吧吗呢啊]+$", "")
                    .trim();
            if (!dest.isEmpty() && dest.length() >= 2) {
                return dest;
            }
        }

        // 特殊处理：单独的 "到XX"
        if (text.startsWith("到") && text.length() > 1) {
            String dest = text.substring(1).trim()
                    .replaceAll("^[的吧吗呢啊]+", "")
                    .replaceAll("[的吧吗呢啊]+$", "")
                    .trim();
            if (!dest.isEmpty() && dest.length() >= 2) {
                return dest;
            }
        }

        return null;
    }
}
