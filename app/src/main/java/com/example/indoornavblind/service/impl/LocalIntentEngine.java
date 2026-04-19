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
        CONTINUE_NAVIGATION,// 继续导航
        REPEAT,             // 重复上一条
        HELP,               // 帮助
        ENTER_SETTINGS,     // 进入设置
        EXIT_SETTINGS,      // 退出设置
        SPEED_UP,           // 加快语速
        SPEED_DOWN,         // 减慢语速
        VOICE_ASSISTANT,    // 打开语音助手
        VOICE_TEST,         // 语音测试/录入
        FLOOR_UP,           // 上楼梯
        FLOOR_DOWN,         // 下楼梯
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
            "去", "到", "导航", "前往", "带我去", "我要去", "怎么去", "走到",
            "navigate", "go to", "take me to", "how to get to", "directions to", "guide me to"
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

    // 进入设置关键词
    private static final String[] ENTER_SETTINGS_KEYWORDS = {
            "设置", "settings", "open settings", "enter settings", "打开设置", "进入设置",
            "settings mode", "设置模式", "调整"
    };

    // 退出设置关键词（优先级更高，需要单独检查）
    private static final String[] EXIT_SETTINGS_KEYWORDS = {
            "退出设置", "exit settings", "关闭设置", "quit settings", "leave settings"
    };

    // 语速调整关键词
    private static final String[] SPEED_UP_KEYWORDS = {
            "快一点", "加快", "语速快", "说快点", "faster", "speech faster",
            "语速加", "语速增加", "快啲", "speech rate up", "速度加快"
    };

    private static final String[] SPEED_DOWN_KEYWORDS = {
            "慢一点", "减慢", "语速慢", "说慢点", "slower", "speech slower",
            "语速减", "语速减少", "慢啲", "speech rate down", "速度减慢"
    };

    // 紧急关键词
    private static final String[] EMERGENCY_KEYWORDS = {
            "救命", "帮帮我", "紧急", "求助", "emergency", "help me", "help!",
            "紧急求助", "紧急帮助", "emergency help", "帮帮我", "帮下手", "幫我"
    };

    // 语音助手关键词
    private static final String[] VOICE_ASSISTANT_KEYWORDS = {
            "语音助手", "语音助理", "voice assistant", "open assistant",
            "打开助手", "进入助手", "启动助手", "voice input", "开始录音",
            "我想提问", "有问题要问"
    };

    // 语音测试关键词
    private static final String[] VOICE_TEST_KEYWORDS = {
            "语音录入", "语音测试", "voice test", "voice input",
            "开始录音", "开始识别", "识别测试", "录音测试"
    };

    // 继续导航关键词
    private static final String[] CONTINUE_NAV_KEYWORDS = {
            "继续导航", "继续", "continue navigation", "continue", "前进"
    };

    // 楼层导航关键词
    private static final String[] FLOOR_UP_KEYWORDS = {
            "上楼梯", "上去", "上楼", "上去楼梯", "up stairs", "go up",
            "搭电梯上去", "乘电梯上去", "电梯上楼"
    };

    private static final String[] FLOOR_DOWN_KEYWORDS = {
            "下楼梯", "下去", "下楼", "下去楼梯", "down stairs", "go down",
            "搭电梯下去", "乘电梯下去", "电梯下楼"
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
            // 加载中文
            String startCn = path.getStartLabel_cn();
            String endCn = path.getEndLabel_cn();
            if (startCn != null && !availableDestinations.contains(startCn)) {
                availableDestinations.add(startCn);
            }
            if (endCn != null && !availableDestinations.contains(endCn)) {
                availableDestinations.add(endCn);
            }
            // 加载英文
            String startEn = path.getStartLabel_en();
            String endEn = path.getEndLabel_en();
            if (startEn != null && !availableDestinations.contains(startEn)) {
                availableDestinations.add(startEn);
            }
            if (endEn != null && !availableDestinations.contains(endEn)) {
                availableDestinations.add(endEn);
            }
        }
        Log.d(TAG, "加载目的地列表: " + availableDestinations.size() + "个（含中英文）");
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

        // 去除空格等空白，避免 Vosk 返回 "我 在 厕所" 时无法匹配 "我在"
        String normalizedText = text.toLowerCase().trim().replaceAll("\\s+", "");
        Log.d(TAG, "识别输入: " + normalizedText);

        // 1. 首先检查是否是紧急求助（优先级最高）
        if (matchKeywords(normalizedText, EMERGENCY_KEYWORDS)) {
            return new IntentResult(Intent.EMERGENCY, null, 1.0f, text);
        }

        // 2. 语音监听到“我在xxx”时优先识别为设置位置，未定位时直接定位，不触发“请先定位”
        IntentResult setLocResult = parseSetLocationIntent(normalizedText, text);
        if (setLocResult != null) {
            return setLocResult;
        }

        // 3. 检查导航意图（带目的地）
        IntentResult navResult = parseNavigationIntent(normalizedText, text);
        if (navResult != null) {
            return navResult;
        }

        // 4. 检查其他意图
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

        if (matchKeywords(normalizedText, REPEAT_KEYWORDS)) {
            return new IntentResult(Intent.REPEAT, null, 0.9f, text);
        }

        if (matchKeywords(normalizedText, HELP_KEYWORDS)) {
            return new IntentResult(Intent.HELP, null, 0.9f, text);
        }

        // 优先检查退出设置（因为"退出设置"也包含"设置"关键词）
        if (matchKeywords(normalizedText, EXIT_SETTINGS_KEYWORDS)) {
            return new IntentResult(Intent.EXIT_SETTINGS, null, 0.95f, text);
        }

        // 再检查进入设置
        if (matchKeywords(normalizedText, ENTER_SETTINGS_KEYWORDS)) {
            return new IntentResult(Intent.ENTER_SETTINGS, null, 0.9f, text);
        }

        if (matchKeywords(normalizedText, SPEED_UP_KEYWORDS)) {
            return new IntentResult(Intent.SPEED_UP, null, 0.9f, text);
        }

        if (matchKeywords(normalizedText, SPEED_DOWN_KEYWORDS)) {
            return new IntentResult(Intent.SPEED_DOWN, null, 0.9f, text);
        }

        if (matchKeywords(normalizedText, VOICE_ASSISTANT_KEYWORDS)) {
            return new IntentResult(Intent.VOICE_ASSISTANT, null, 0.9f, text);
        }

        if (matchKeywords(normalizedText, VOICE_TEST_KEYWORDS)) {
            return new IntentResult(Intent.VOICE_TEST, null, 0.9f, text);
        }

        if (matchKeywords(normalizedText, CONTINUE_NAV_KEYWORDS)) {
            return new IntentResult(Intent.CONTINUE_NAVIGATION, null, 0.9f, text);
        }

        if (matchKeywords(normalizedText, FLOOR_UP_KEYWORDS)) {
            return new IntentResult(Intent.FLOOR_UP, null, 0.9f, text);
        }

        if (matchKeywords(normalizedText, FLOOR_DOWN_KEYWORDS)) {
            return new IntentResult(Intent.FLOOR_DOWN, null, 0.9f, text);
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

    /** 查询类短语：这些跟在"我在"后面时视为“我在哪”类查询，不当作设置位置 */
    private static final String[] SET_LOCATION_QUERY_WORDS = {
            "哪", "哪里", "什么地方", "什么位置", "哪儿", "where"
    };

    /**
     * 解析设置位置意图（"我在XX"）— 语音监听到"我在xx"后用于执行定位/设置位置逻辑
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

        // 提取关键词后的内容
        int keywordIndex = normalizedText.indexOf(matchedKeyword);
        String afterKeyword = normalizedText.substring(keywordIndex + matchedKeyword.length()).trim();
        // 排除“我在哪/哪里”等查询意图，交给 QUERY_LOCATION 处理
        for (String q : SET_LOCATION_QUERY_WORDS) {
            if (afterKeyword.startsWith(q) || afterKeyword.contains(q)) {
                return null;
            }
        }
        if (afterKeyword.isEmpty()) {
            return null;
        }

        // 优先从已知目的地中匹配
        String location = findDestination(afterKeyword);
        if (location == null) {
            location = findDestination(normalizedText);
        }
        // 未在列表中时仍返回“我在xx”的xx部分，由 MainActivity 尝试 findPositionByName 或提示
        if (location == null && afterKeyword.length() >= 1) {
            String raw = afterKeyword.split("[\\s，,。.、]+")[0].trim();
            if (raw.length() <= 20) {
                location = raw;
            }
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
        // 预处理：中文数字转阿拉伯数字
        text = text.replace("零", "0").replace("一", "1").replace("二", "2")
                .replace("三", "3").replace("四", "4").replace("五", "5")
                .replace("六", "6").replace("七", "7").replace("八", "8").replace("九", "9");

        String lowerText = text.toLowerCase();

        // 1. 精确匹配目的地列表
        for (String dest : availableDestinations) {
            if (lowerText.contains(dest.toLowerCase())) {
                return dest;
            }
        }

        // 2. 别名匹配：检查用户输入是否匹配某个别名
        for (Map.Entry<String, String[]> entry : LOCATION_ALIASES.entrySet()) {
            String canonical = entry.getKey();
            for (String alias : entry.getValue()) {
                if (lowerText.contains(alias.toLowerCase())) {
                    // 找到别名，返回标准名或可用列表中的匹配项
                    if (availableDestinations.contains(canonical)) {
                        return canonical;
                    }
                    // 在可用目的地中查找包含此标准名的
                    for (String dest : availableDestinations) {
                        if (dest.toLowerCase().contains(canonical.toLowerCase()) ||
                                canonical.toLowerCase().contains(dest.toLowerCase())) {
                            return dest;
                        }
                    }
                }
            }
        }

        // 3. 去除字母后缀匹配（C202a -> C202）
        for (String dest : availableDestinations) {
            String simpleDest = dest.replaceAll("[a-zA-Z]$", "").toLowerCase();
            if (lowerText.contains(simpleDest) && simpleDest.length() >= 3) {
                return dest;
            }
        }

        return null;
    }

    private static final Map<String, String[]> LOCATION_ALIASES = new HashMap<>();
    static {
        // 厕所相关
        LOCATION_ALIASES.put("男厕", new String[]{"男厕","男厕所", "male toilet", "male washroom", "男洗手间"});
        LOCATION_ALIASES.put("女厕", new String[]{"女厕", "女厕所", "female toilet", "female washroom", "女洗手间", "f washroom"});
        LOCATION_ALIASES.put("厕所", new String[]{"洗手间", "toilet", "washroom", "卫生间", "wc"});
        // 电梯相关
        LOCATION_ALIASES.put("电梯", new String[]{"lift", "elevator", "升降机"});
        // 拐弯点相关
        LOCATION_ALIASES.put("拐弯点", new String[]{"岔路口", "拐点", "turning point", "junction"});
    }

    /**
     * 检查目的地是否在多个楼层存在
     */
    public List<Integer> getFloorsForDestination(String destination) {
        List<Integer> floors = new ArrayList<>();
        for (PathEntity path : PathParser.getAllPaths()) {
            int floor = 0;
            try { floor = Integer.parseInt(String.valueOf(path.getFloor())); } catch (Exception ignored) {}
            if ((path.getStartLabel_cn().equalsIgnoreCase(destination) ||
                    path.getEndLabel_cn().equalsIgnoreCase(destination)) && !floors.contains(floor)) {
                floors.add(floor);
            }
        }
        return floors;
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
     * 修复：先去除所有空白字符再匹配，处理"退出 设置"这种带空格的情况
     */
    private boolean matchKeywords(String text, String[] keywords) {
        // 去除所有空白字符（空格、中文空格、换行、制表符等）
        String normalized = text.replaceAll("\\s+", "");
        for (String keyword : keywords) {
            if (normalized.contains(keyword)) {
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
        sb.append("• 打开设置/进入设置 - 进入设置模式\n");
        sb.append("• 紧急求助 - 发送紧急帮助\n");
        sb.append("• 语音助手 - 打开语音助手\n");
        sb.append("• 语音录入 - 测试语音识别\n");
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
