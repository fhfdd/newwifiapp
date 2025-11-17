package com.example.indoornavblind.util;

import android.content.Context;
import com.example.indoornavblind.model.PathEntity;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class PathParser {
    private static List<PathEntity> allPaths;
    private static String currentLang = "cn"; // 默认中文

    // 初始化路径数据（从path_db.json读取）
    public static void init(Context context) {
        if (allPaths != null) return;
        try (InputStream is = context.getAssets().open("path_db.json");
             BufferedReader br = new BufferedReader(new InputStreamReader(is))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);

            Type type = new TypeToken<List<PathEntity>>(){}.getType();
            allPaths = GsonUtil.fromJson(sb.toString(), type);
        } catch (IOException e) {
            allPaths = new ArrayList<>();
        }
    }

    // 设置当前语言（cn=中文, en=英语, yue=粤语）
    public static void setCurrentLang(String lang) {
        if (lang.matches("cn|en|yue")) currentLang = lang;
    }

    // 根据起点和终点获取完整路径（需实现实际逻辑）
    public static List<PathEntity> getFullPath(String startLabel, String endLabel) {
        List<PathEntity> result = new ArrayList<>();
        if (allPaths == null) return result;

        // 实际逻辑：遍历allPaths，匹配startLabel和endLabel，拼接完整路径
        // 示例：仅返回空列表，需根据实际path_db.json格式实现
        return result;
    }

    // 根据语言获取方向描述（已有方法）
    public static String getDirectionByLang(PathEntity path) {
        switch (currentLang) {
            case "en": return path.getDirection_en();
            case "yue": return path.getDirection_yue();
            default: return path.getDirection_cn(); // 默认中文
        }
    }

    // 新增：根据语言获取距离描述（解决错误的核心方法）
    public static String getDistanceByLang(PathEntity path) {
        switch (currentLang) {
            case "en": return path.getDistance_en(); // 英语距离（如"5 meters"）
            case "yue": return path.getDistance_yue(); // 粤语距离（如"5米"）
            default: return path.getDistance_cn(); // 中文距离（如"5米"）
        }
    }
}