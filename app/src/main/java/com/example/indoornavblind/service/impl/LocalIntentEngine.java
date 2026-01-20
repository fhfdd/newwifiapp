package com.example.indoornavblind.service.impl;

import android.content.Context;
import android.util.Log;

import com.example.indoornavblind.model.PathEntity;
import com.example.indoornavblind.util.PathParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地意图识别引擎 - 完全离线工作
 *
 * 功能：
 * 1. 意图识别：导航、定位、查询、帮助、停止等
 * 2. 实体提取：目的地名称
 * 3. 模糊匹配：支持不完全匹配的目的地名称
 * 4. 同义词支持：多种表达方式
 *
 * 不依赖任何云服务，适合离线场景
 */
public class LocalIntentEngine {
    private static final String TAG = "LocalIntentEngine";

    // 意图类型
    public enum Intent {
        NAVIGATE,           // 导航到某地
        LOCATE,             // 定位当前位置
        SET_LOCATION,       // 手动设置位置（我在XX）
        QUERY_LOCATION,     // 查询当前位置
        QUERY_DESTINATION,  // 查询目的地
        QUERY_NEARBY,       // 查询附近
        QUERY_PROGRESS,     // 查询导航进度
        START_NAVIGATION,   // 开始导航
        STOP_NAVIGATION,    // 停止导航
        REPEAT,             // 重复上一条
        HELP,               // 帮助
        SETTINGS,           // 设置
        SPEED_UP,           // 加快语速
        SPEED_DOWN,         // 减慢语速
        EMERGENCY,          // 紧急求助
        UNKNOWN             // 未知意图
    }

    // 意图识别结果
    public static class IntentResult {
        public Intent intent;
        public String destination;      // 目的地（如果有）
        public float confidence;        // 置信度 0-1
        public String rawText;          // 原始文本
        public String matchedKeyword;   // 匹配到的关键词

        public IntentResult(Intent intent, String destination, float confidence, String rawText) {
            this.intent = intent;
            this.destination = destination;
            this.confidence = confidence;
            this.rawText = rawText;
        }

        @Override
        public String toString() {
            return String.format("Intent: %s, Destination: %s, Confidence: %.2f",
                    intent.name(), destination, confidence);
        }
    }

    // 关键词映射表
    private static final Map<String, Intent> KEYWORD_MAP = new HashMap<>();

    // 导航相关关键词
    private static final String[] NAVIGATE_KEYWORDS = {
            "去", "到", "导航", "前往", "带我去", "我要去", "怎么去", "走到", "帮我去",
            "我想去", "带我到", "领我去", "去往", "navigate", "go to"
    };

    // 定位相关关键词
    private static final String[] LOCATE_KEYWORDS = {
            "定位", "重新定位", "刷新位置", "更新位置", "locate"
    };

    // 手动设置位置关键词（我在XX）
    private static final String[] SET_LOCATION_KEYWORDS = {
            "我在", "我现在在", "我的位置是", "起点是", "从这里", "i am at", "i'm at", "start from"
    };

    // 查询位置关键词
    private static final String[] QUERY_LOCATION_KEYWORDS = {
            "我在哪", "在哪里", "当前位置", "现在在哪", "这是哪", "什么位置",
            "where am i", "current location"
    };

    // 查询附近关键词
    private static final String[] QUERY_NEARBY_KEYWORDS = {
            "附近有什么", "周围有什么", "旁边有什么", "附近", "周围", "nearby"
    };

    // 查询进度关键词
    private static final String[] QUERY_PROGRESS_KEYWORDS = {
            "还有多远", "还要多久", "进度", "剩余", "多少步", "how far"
    };

    // 开始导航关键词
    private static final String[] START_NAV_KEYWORDS = {
            "开始导航", "开始", "出发", "走吧", "start"
    };

    // 停止导航关键词
    private static final String[] STOP_NAV_KEYWORDS = {
            "停止", "停止导航", "结束", "取消", "退出导航", "stop", "cancel"
    };

    // 重复关键词
    private static final String[] REPEAT_KEYWORDS = {
            "再说一遍", "重复", "没听清", "什么", "pardon", "repeat"
    };

    // 帮助关键词
    private static final String[] HELP_KEYWORDS = {
            "帮助", "怎么用", "使用说明", "能做什么", "help"
    };

    // 设置关键词
    private static final String[] SETTINGS_KEYWORDS = {
            "设置", "调整", "settings"
    };

    // 语速调整关键词
    private static final String[] SPEED_UP_KEYWORDS = {
            "快一点", "加快", "语速快", "说快点", "faster"
    };

    private static final String[] SPEED_DOWN_KEYWORDS = {
            "慢一点", "减慢", "语速慢", "说慢点", "slower"
    };

    // 紧急关键词
    private static final String[] EMERGENCY_KEYWORDS = {
            "救命", "帮帮我", "紧急", "求助", "emergency", "help me"
    };

    // 所有可用目的地列表（从路径数据中提取）
    private List<String> availableDestinations = new ArrayList<>();

    private Context context;

    public LocalIntentEngine(Context context) {
        this.context = context;
        loadDestinations();
    }

    /**
     * 从路径数据中加载所有可用目的地
     */
    private void loadDestinations() {
        List<PathEntity> allPaths = PathParser.getAllPaths();
        for (PathEntity path : allPaths) {
            String start = path.getStartLabel_cn();
            String end = path.getEndLabel_cn();
            if (!availableDestinations.contains(start)) {
                availableDestinations.add(start);
            }
            if (!availableDestinations.contains(end)) {
                availableDestinations.add(end);
            }
        }
        Log.d(TAG, "加载目的地列表: " + availableDestinations.size() + "个");
    }

    /**
     * 识别用户语音输入的意图
     *
     * @param text 用户语音转文字后的文本
     * @return 意图识别结果
     */
    public IntentResult recognize(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new IntentResult(Intent.UNKNOWN, null, 0f, text);
        }

        String normalizedText = text.toLowerCase().trim();
        Log.d(TAG, "识别输入: " + normalizedText);

        // 1. 首先检查是否是紧急求助（优先级最高）
        if (matchKeywords(normalizedText, EMERGENCY_KEYWORDS)) {
            return new IntentResult(Intent.EMERGENCY, null, 1.0f, text);
        }

        // 2. 检查导航意图（带目的地）
        IntentResult navResult = parseNavigationIntent(normalizedText, text);
        if (navResult != null) {
            return navResult;
        }

        // 3. 检查其他意图
        if (matchKeywords(normalizedText, STOP_NAV_KEYWORDS)) {
            return new IntentResult(Intent.STOP_NAVIGATION, null, 0.9f, text);
        }

        if (matchKeywords(normalizedText, START_NAV_KEYWORDS)) {
            return new IntentResult(Intent.START_NAVIGATION, null, 0.9f, text);
        }

        if (matchKeywords(normalizedText, QUERY_LOCATION_KEYWORDS)) {
            return new IntentResult(Intent.QUERY_LOCATION, null, 0.9f, text);
        }

        if (matchKeywords(normalizedText, QUERY_NEARBY_KEYWORDS)) {
            return new IntentResult(Intent.QUERY_NEARBY, null, 0.9f, text);
        }

        if (matchKeywords(normalizedText, QUERY_PROGRESS_KEYWORDS)) {
            return new IntentResult(Intent.QUERY_PROGRESS, null, 0.9f, text);
        }

        if (matchKeywords(normalizedText, LOCATE_KEYWORDS)) {
            return new IntentResult(Intent.LOCATE, null, 0.9f, text);
        }

        // 检查手动设置位置意图（我在XX）
        IntentResult setLocResult = parseSetLocationIntent(normalizedText, text);
        if (setLocResult != null) {
            return setLocResult;
        }

        if (matchKeywords(normalizedText, REPEAT_KEYWORDS)) {
            return new IntentResult(Intent.REPEAT, null, 0.9f, text);
        }

        if (matchKeywords(normalizedText, HELP_KEYWORDS)) {
            return new IntentResult(Intent.HELP, null, 0.9f, text);
        }

        if (matchKeywords(normalizedText, SETTINGS_KEYWORDS)) {
            return new IntentResult(Intent.SETTINGS, null, 0.9f, text);
        }

        if (matchKeywords(normalizedText, SPEED_UP_KEYWORDS)) {
            return new IntentResult(Intent.SPEED_UP, null, 0.9f, text);
        }

        if (matchKeywords(normalizedText, SPEED_DOWN_KEYWORDS)) {
            return new IntentResult(Intent.SPEED_DOWN, null, 0.9f, text);
        }

        // 4. 如果用户直接说目的地名称（没有导航关键词）
        String directDestination = findDestination(normalizedText);
        if (directDestination != null) {
            return new IntentResult(Intent.NAVIGATE, directDestination, 0.7f, text);
        }

        // 5. 未识别
        return new IntentResult(Intent.UNKNOWN, null, 0f, text);
    }

    /**
     * 解析导航意图
     */
    private IntentResult parseNavigationIntent(String normalizedText, String originalText) {
        // 检查是否包含导航关键词
        String matchedKeyword = null;
        for (String keyword : NAVIGATE_KEYWORDS) {
            if (normalizedText.contains(keyword)) {
                matchedKeyword = keyword;
                break;
            }
        }

        if (matchedKeyword == null) {
            return null;
        }

        // 提取目的地
        String destination = extractDestination(normalizedText, matchedKeyword);

        if (destination != null) {
            IntentResult result = new IntentResult(Intent.NAVIGATE, destination, 0.95f, originalText);
            result.matchedKeyword = matchedKeyword;
            return result;
        }

        // 有导航意图但没有识别到目的地
        IntentResult result = new IntentResult(Intent.NAVIGATE, null, 0.5f, originalText);
        result.matchedKeyword = matchedKeyword;
        return result;
    }

    /**
     * 解析手动设置位置意图（我在XX）
     */
    /**
     * 解析设置位置意图（"我在XX"）
     */
    private IntentResult parseSetLocationIntent(String normalizedText, String originalText) {
        String matchedKeyword = null;
        for (String keyword : SET_LOCATION_KEYWORDS) {
            if (normalizedText.contains(keyword)) {
                matchedKeyword = keyword;
                break;
            }
        }

        if (matchedKeyword == null) {
            return null;
        }

        // 提取位置
        int keywordIndex = normalizedText.indexOf(matchedKeyword);
        String afterKeyword = normalizedText.substring(keywordIndex + matchedKeyword.length()).trim();
        String location = findDestination(afterKeyword);

        if (location == null) {
            location = findDestination(normalizedText);
        }

        if (location != null) {
            IntentResult result = new IntentResult(Intent.SET_LOCATION, location, 0.95f, originalText);
            result.matchedKeyword = matchedKeyword;
            return result;
        }

        return null;
    }

    /**
     * 从文本中提取目的地
     */
    private String extractDestination(String text, String keyword) {
        // 策略1: 在关键词后面查找目的地
        int keywordIndex = text.indexOf(keyword);
        if (keywordIndex >= 0) {
            String afterKeyword = text.substring(keywordIndex + keyword.length()).trim();
            String dest = findDestination(afterKeyword);
            if (dest != null) {
                return dest;
            }
        }

        // 策略2: 在整个文本中查找目的地
        return findDestination(text);
    }

    /**
     * 在文本中查找目的地（支持模糊匹配）
     */
    private String findDestination(String text) {
        // 精确匹配
        for (String dest : availableDestinations) {
            if (text.contains(dest.toLowerCase())) {
                return dest;
            }
        }

        // 模糊匹配（处理语音识别可能的错误）
        for (String dest : availableDestinations) {
            // 简化匹配：去除常见后缀
            String simpleDest = dest.replace("a", "").replace("b", "");
            if (text.contains(simpleDest.toLowerCase())) {
                return dest;
            }

            // 部分匹配（目的地名称是文本的一部分）
            if (fuzzyMatch(text, dest.toLowerCase())) {
                return dest;
            }
        }

        return null;
    }

    /**
     * 模糊匹配（编辑距离）
     */
    private boolean fuzzyMatch(String text, String target) {
        // 简单的包含关系匹配
        if (text.length() < 2 || target.length() < 2) {
            return false;
        }

        // 检查是否有2个以上连续字符匹配
        for (int i = 0; i <= target.length() - 2; i++) {
            String sub = target.substring(i, i + 2);
            if (text.contains(sub)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 检查文本是否匹配关键词列表
     */
    private boolean matchKeywords(String text, String[] keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取所有可用目的地列表
     */
    public List<String> getAvailableDestinations() {
        return new ArrayList<>(availableDestinations);
    }

    /**
     * 获取帮助信息
     */
    public String getHelpText() {
        StringBuilder sb = new StringBuilder();
        sb.append("您可以说以下指令：\n");
        sb.append("• 导航到[目的地]，例如：去浴室、带我去门口\n");
        sb.append("• 我在哪里 - 查询当前位置\n");
        sb.append("• 附近有什么 - 查询周围设施\n");
        sb.append("• 还有多远 - 查询导航进度\n");
        sb.append("• 停止导航 - 结束当前导航\n");
        sb.append("• 重复 - 再说一遍上一条\n");
        sb.append("• 快一点/慢一点 - 调整语速\n");
        sb.append("\n可用目的地：");
        for (int i = 0; i < Math.min(5, availableDestinations.size()); i++) {
            sb.append(availableDestinations.get(i));
            if (i < Math.min(5, availableDestinations.size()) - 1) {
                sb.append("、");
            }
        }
        if (availableDestinations.size() > 5) {
            sb.append("等");
        }
        return sb.toString();
    }
}