package com.example.indoornavblind.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.indoornavblind.model.Position;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地路径存储服务
 *
 * 功能：
 * 1. 存储常用起点和终点
 * 2. 存储历史导航路径
 * 3. 快速访问收藏位置
 * 4. 完全离线本地存储
 * 5. 支持语音快速调用
 */
public class PathStorageService {
    private static final String TAG = "PathStorage";
    private static final String PREFS_NAME = "path_storage";
    private static final String KEY_FAVORITE_LOCATIONS = "favorite_locations";
    private static final String KEY_RECENT_ROUTES = "recent_routes";
    private static final String KEY_COMMON_START_POINTS = "common_start_points";
    private static final String KEY_HOME_LOCATION = "home_location";
    private static final String KEY_WORK_LOCATION = "work_location";

    private Context context;
    private SharedPreferences prefs;
    private Gson gson;

    // 内存缓存
    private Map<String, Position> favoriteLocations;
    private List<RouteRecord> recentRoutes;
    private Map<String, Integer> commonStartPoints;
    private String homeLocation;
    private String workLocation;

    /**
     * 路径记录类
     */
    public static class RouteRecord {
        public String from;
        public String to;
        public long timestamp;
        public int useCount;

        public RouteRecord(String from, String to) {
            this.from = from;
            this.to = to;
            this.timestamp = System.currentTimeMillis();
            this.useCount = 1;
        }

        public void incrementUseCount() {
            this.useCount++;
            this.timestamp = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return String.format("%s → %s (使用%d次)", from, to, useCount);
        }
    }

    public PathStorageService(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.gson = new Gson();

        loadAllData();
    }

    /**
     * 加载所有数据
     */
    private void loadAllData() {
        loadFavoriteLocations();
        loadRecentRoutes();
        loadCommonStartPoints();
        loadSpecialLocations();

        Log.d(TAG, "数据加载完成");
        Log.d(TAG, "收藏位置: " + favoriteLocations.size());
        Log.d(TAG, "历史路径: " + recentRoutes.size());
    }

    /**
     * 加载收藏位置
     */
    private void loadFavoriteLocations() {
        String json = prefs.getString(KEY_FAVORITE_LOCATIONS, "{}");
        Type type = new TypeToken<Map<String, Position>>(){}.getType();
        favoriteLocations = gson.fromJson(json, type);

        if (favoriteLocations == null) {
            favoriteLocations = new HashMap<>();
        }
    }

    /**
     * 加载历史路径
     */
    private void loadRecentRoutes() {
        String json = prefs.getString(KEY_RECENT_ROUTES, "[]");
        Type type = new TypeToken<List<RouteRecord>>(){}.getType();
        recentRoutes = gson.fromJson(json, type);

        if (recentRoutes == null) {
            recentRoutes = new ArrayList<>();
        }
    }

    /**
     * 加载常用起点
     */
    private void loadCommonStartPoints() {
        String json = prefs.getString(KEY_COMMON_START_POINTS, "{}");
        Type type = new TypeToken<Map<String, Integer>>(){}.getType();
        commonStartPoints = gson.fromJson(json, type);

        if (commonStartPoints == null) {
            commonStartPoints = new HashMap<>();
        }
    }

    /**
     * 加载特殊位置（家/公司）
     */
    private void loadSpecialLocations() {
        homeLocation = prefs.getString(KEY_HOME_LOCATION, null);
        workLocation = prefs.getString(KEY_WORK_LOCATION, null);
    }

    /**
     * 添加收藏位置
     */
    public void addFavoriteLocation(String name, Position position) {
        favoriteLocations.put(name, position);
        saveFavoriteLocations();
        Log.d(TAG, "添加收藏位置: " + name);
    }

    /**
     * 移除收藏位置
     */
    public void removeFavoriteLocation(String name) {
        favoriteLocations.remove(name);
        saveFavoriteLocations();
        Log.d(TAG, "移除收藏位置: " + name);
    }

    /**
     * 获取收藏位置
     */
    public Position getFavoriteLocation(String name) {
        return favoriteLocations.get(name);
    }

    /**
     * 获取所有收藏位置
     */
    public Map<String, Position> getAllFavoriteLocations() {
        return new HashMap<>(favoriteLocations);
    }

    /**
     * 记录路径使用
     */
    public void recordRoute(String from, String to) {
        // 查找是否已存在
        RouteRecord existing = null;
        for (RouteRecord route : recentRoutes) {
            if (route.from.equals(from) && route.to.equals(to)) {
                existing = route;
                break;
            }
        }

        if (existing != null) {
            // 增加使用次数
            existing.incrementUseCount();
        } else {
            // 新建记录
            RouteRecord newRoute = new RouteRecord(from, to);
            recentRoutes.add(0, newRoute);

            // 限制历史记录数量（最多50条）
            if (recentRoutes.size() > 50) {
                recentRoutes.remove(recentRoutes.size() - 1);
            }
        }

        // 更新常用起点统计
        int currentCount = commonStartPoints.containsKey(from) ? commonStartPoints.get(from) : 0;
        commonStartPoints.put(from, currentCount + 1);

        saveRecentRoutes();
        saveCommonStartPoints();

        Log.d(TAG, "记录路径: " + from + " → " + to);
    }

    /**
     * 获取最近使用的路径
     */
    public List<RouteRecord> getRecentRoutes(int limit) {
        int count = Math.min(limit, recentRoutes.size());
        return new ArrayList<>(recentRoutes.subList(0, count));
    }

    /**
     * 获取最常用的路径
     */
    public List<RouteRecord> getMostUsedRoutes(int limit) {
        List<RouteRecord> sorted = new ArrayList<>(recentRoutes);
        // 修改这里：使用 Collections.sort() 代替 List.sort()
        Collections.sort(sorted, new Comparator<RouteRecord>() {
            @Override
            public int compare(RouteRecord r1, RouteRecord r2) {
                return Integer.compare(r2.useCount, r1.useCount);
            }
        });

        int count = Math.min(limit, sorted.size());
        return sorted.subList(0, count);
    }

    /**
     * 获取最常用的起点
     */
    public List<String> getMostCommonStartPoints(int limit) {
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(commonStartPoints.entrySet());
        // 修改这里：使用 Collections.sort() 代替 List.sort()
        Collections.sort(sorted, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> e1, Map.Entry<String, Integer> e2) {
                return Integer.compare(e2.getValue(), e1.getValue());
            }
        });

        List<String> result = new ArrayList<>();
        int count = Math.min(limit, sorted.size());
        for (int i = 0; i < count; i++) {
            result.add(sorted.get(i).getKey());
        }

        return result;
    }

    /**
     * 设置家位置
     */
    public void setHomeLocation(String location) {
        this.homeLocation = location;
        prefs.edit().putString(KEY_HOME_LOCATION, location).apply();
        Log.d(TAG, "设置家位置: " + location);
    }

    /**
     * 获取家位置
     */
    public String getHomeLocation() {
        return homeLocation;
    }

    /**
     * 设置公司位置
     */
    public void setWorkLocation(String location) {
        this.workLocation = location;
        prefs.edit().putString(KEY_WORK_LOCATION, location).apply();
        Log.d(TAG, "设置公司位置: " + location);
    }

    /**
     * 获取公司位置
     */
    public String getWorkLocation() {
        return workLocation;
    }

    /**
     * 根据语音指令快速获取位置
     * 例如："回家"、"去公司"、"去我常去的厕所"
     */
    public String resolveVoiceCommand(String command) {
        command = command.toLowerCase().trim();

        // 特殊位置
        if (command.contains("家") || command.contains("home")) {
            return homeLocation;
        }

        if (command.contains("公司") || command.contains("work") || command.contains("办公室")) {
            return workLocation;
        }

        // 收藏位置
        for (String favName : favoriteLocations.keySet()) {
            if (command.contains(favName)) {
                return favName;
            }
        }

        // 常用起点（如"我常去的厕所"）
        if (command.contains("常去") || command.contains("usual")) {
            String location = extractLocationKeyword(command);
            if (location != null && commonStartPoints.containsKey(location)) {
                return location;
            }
        }

        return null;
    }

    /**
     * 提取位置关键词
     */
    private String extractLocationKeyword(String command) {
        String[] keywords = {"厕所", "洗手间", "浴室", "楼梯", "电梯", "门口", "出口", "入口"};

        for (String keyword : keywords) {
            if (command.contains(keyword)) {
                return keyword;
            }
        }

        return null;
    }

    /**
     * 智能推荐目的地
     * 基于当前位置和历史记录
     */
    public List<String> recommendDestinations(String currentLocation, int limit) {
        List<String> recommendations = new ArrayList<>();
        Map<String, Integer> scores = new HashMap<>();

        // 统计从当前位置出发的历史目的地
        for (RouteRecord route : recentRoutes) {
            if (route.from.equals(currentLocation)) {
                int currentScore = scores.containsKey(route.to) ? scores.get(route.to) : 0;
                scores.put(route.to, currentScore + route.useCount);
            }
        }

        // 排序 - 修改这里：使用 Collections.sort() 代替 List.sort()
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(scores.entrySet());
        Collections.sort(sorted, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> e1, Map.Entry<String, Integer> e2) {
                return Integer.compare(e2.getValue(), e1.getValue());
            }
        });

        // 取前N个
        int count = Math.min(limit, sorted.size());
        for (int i = 0; i < count; i++) {
            recommendations.add(sorted.get(i).getKey());
        }

        return recommendations;
    }

    /**
     * 获取统计信息
     */
    public String getStatistics() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 路径存储统计 ===\n");
        sb.append("收藏位置: ").append(favoriteLocations.size()).append("个\n");
        sb.append("历史路径: ").append(recentRoutes.size()).append("条\n");
        sb.append("常用起点: ").append(commonStartPoints.size()).append("个\n");

        if (homeLocation != null) {
            sb.append("家: ").append(homeLocation).append("\n");
        }

        if (workLocation != null) {
            sb.append("公司: ").append(workLocation).append("\n");
        }

        // 最常用路径
        List<RouteRecord> topRoutes = getMostUsedRoutes(3);
        if (!topRoutes.isEmpty()) {
            sb.append("\n最常用路径:\n");
            for (int i = 0; i < topRoutes.size(); i++) {
                sb.append(String.format("%d. %s\n", i + 1, topRoutes.get(i)));
            }
        }

        return sb.toString();
    }

    /**
     * 清除历史记录
     */
    public void clearHistory() {
        recentRoutes.clear();
        commonStartPoints.clear();
        saveRecentRoutes();
        saveCommonStartPoints();
        Log.d(TAG, "历史记录已清除");
    }

    /**
     * 清除所有数据
     */
    public void clearAll() {
        favoriteLocations.clear();
        recentRoutes.clear();
        commonStartPoints.clear();
        homeLocation = null;
        workLocation = null;

        prefs.edit().clear().apply();
        Log.d(TAG, "所有数据已清除");
    }

    // ========== 保存方法 ==========

    private void saveFavoriteLocations() {
        String json = gson.toJson(favoriteLocations);
        prefs.edit().putString(KEY_FAVORITE_LOCATIONS, json).apply();
    }

    private void saveRecentRoutes() {
        String json = gson.toJson(recentRoutes);
        prefs.edit().putString(KEY_RECENT_ROUTES, json).apply();
    }

    private void saveCommonStartPoints() {
        String json = gson.toJson(commonStartPoints);
        prefs.edit().putString(KEY_COMMON_START_POINTS, json).apply();
    }
}