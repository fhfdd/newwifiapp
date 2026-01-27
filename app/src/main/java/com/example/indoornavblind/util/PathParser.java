package com.example.indoornavblind.util;

import android.content.Context;
import android.util.Log;
import com.example.indoornavblind.model.PathEntity;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.*;

public class PathParser {
    private static final String TAG = "PathParser";
    private static final String[] FINGERPRINT_FILES = {"path_db.json","path_3a.json","path_2c.json","path_3c.json"};
    private static List<PathEntity> allPaths = new ArrayList<>();
    private static boolean isInitialized = false;
    private static final Object LOCK = new Object();

    public static void init(Context context) {
        synchronized (LOCK) {
            if (isInitialized) return;

            allPaths = new ArrayList<>();
            int totalLoaded = 0;

            try {
                for (String fileName : FINGERPRINT_FILES) {
                    List<PathEntity> filePaths = null;
                    try (InputStream is = context.getAssets().open(fileName);
                         InputStreamReader reader = new InputStreamReader(is)) {

                        Gson gson = new Gson();
                        Type type = new TypeToken<List<PathEntity>>() {}.getType();
                        filePaths = gson.fromJson(reader, type);

                    } catch (Exception e) {
                        Log.e(TAG, "加载文件[" + fileName + "]失败", e);
                    }

                    if (filePaths != null && !filePaths.isEmpty()) {
                        allPaths.addAll(filePaths);
                        totalLoaded += filePaths.size();
                        Log.d(TAG, "文件[" + fileName + "]加载成功，共" + filePaths.size() + "条");
                    }
                }

                isInitialized = true;
                Log.d(TAG, "所有路径数据加载完成，总计" + totalLoaded + "条");

            } catch (Exception e) {
                Log.e(TAG, "初始化路径数据异常", e);
                allPaths = new ArrayList<>();
            }
        }
    }

    public static List<PathEntity> getAllPaths() {
        synchronized (LOCK) {
            return Collections.unmodifiableList(new ArrayList<>(allPaths));
        }
    }

    public static boolean isInitialized() {
        synchronized (LOCK) {
            return isInitialized;
        }
    }

    /**
     * 获取指定楼层的完整路径
     */

    public static List<PathEntity> getFullPath(String start, String end, int floor) {
        if (!isInitialized || allPaths.isEmpty()) {
            return Collections.emptyList();
        }

        Queue<String> queue = new LinkedList<>();
        Map<String, String> previous = new HashMap<>();
        Set<String> visited = new HashSet<>();

        queue.offer(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            String current = queue.poll();

            if (current.equals(end)) {
                return reconstructPath(previous, start, end);
            }

            for (PathEntity path : allPaths) {
                int pathFloor = 0;
                try {
                    pathFloor = Integer.parseInt(String.valueOf(path.getFloor()));
                } catch (Exception ignored) {}
                if (pathFloor != floor) continue;

                // 正向边
                if (path.getStartLabel_cn().equals(current) && !visited.contains(path.getEndLabel_cn())) {
                    queue.offer(path.getEndLabel_cn());
                    visited.add(path.getEndLabel_cn());
                    previous.put(path.getEndLabel_cn(), current);
                }
                // 反向边
                if (path.getEndLabel_cn().equals(current) && !visited.contains(path.getStartLabel_cn())) {
                    queue.offer(path.getStartLabel_cn());
                    visited.add(path.getStartLabel_cn());
                    previous.put(path.getStartLabel_cn(), current);
                }
            }
        }

        return Collections.emptyList();
    }

    private static List<PathEntity> reconstructPath(Map<String, String> previous, String start, String end) {
        List<PathEntity> path = new ArrayList<>();
        String current = end;
        
        while (!current.equals(start)) {
            String prev = previous.get(current);
            if (prev == null) break;
            
            PathEntity edge = findEdge(prev, current);
            if (edge != null) {
                path.add(0, edge);
            }
            current = prev;
        }
        
        return path;
    }

    private static PathEntity findEdge(String from, String to) {
        for (PathEntity path : allPaths) {
            if (path.getStartLabel_cn().equals(from) && path.getEndLabel_cn().equals(to)) {
                return path;
            }
            if (path.getEndLabel_cn().equals(from) && path.getStartLabel_cn().equals(to)) {
                return createReversedPath(path);
            }
        }
        return null;
    }

    private static PathEntity createReversedPath(PathEntity original) {
        PathEntity reversed = new PathEntity();
        reversed.setStartLabel_cn(original.getEndLabel_cn());
        reversed.setEndLabel_cn(original.getStartLabel_cn());
        reversed.setStartLabel_en(original.getEndLabel_en());
        reversed.setEndLabel_en(original.getStartLabel_en());
        reversed.setFloor(original.getFloor());
        reversed.setDistance_cn(original.getDistance_cn());
        reversed.setDistance_en(original.getDistance_en());
        reversed.setDistance_yue(original.getDistance_yue());
        reversed.setDirection_cn(reverseDirection(original.getDirection_cn()));
        reversed.setDirection_en(reverseDirection(original.getDirection_en()));
        reversed.setDirection_yue(reverseDirection(original.getDirection_yue()));
        reversed.setNextPoint_cn(original.getStartLabel_cn());
        reversed.setNextPoint_en(original.getStartLabel_en());
        reversed.setNextPoint_yue(original.getStartLabel_yue());
        return reversed;
    }

    private static String reverseDirection(String dir) {
        if (dir == null) return null;
        return dir.replace("左", "右§").replace("右", "左").replace("§", "")
                .replace("left", "right§").replace("right", "left").replace("§", "")
                .replace("Left", "Right§").replace("Right", "Left").replace("§", "");
    }

    public static String getDirectionByLang(PathEntity path, Locale locale) {
        if (locale.equals(Locale.ENGLISH)) {
            return path.getDirection_en();
        } else if (locale.getLanguage().equals("yue")) {
            return path.getDirection_yue();
        }
        return path.getDirection_cn();
    }

    public static String getDistanceByLang(PathEntity path, Locale locale) {
        if (locale.equals(Locale.ENGLISH)) {
            return path.getDistance_en();
        } else if (locale.getLanguage().equals("yue")) {
            return path.getDistance_yue();
        }
        return path.getDistance_cn();
    }

    public static String getNextPointByLang(PathEntity path, Locale locale) {
        if (locale.equals(Locale.ENGLISH)) {
            return path.getNextPoint_en();
        } else if (locale.getLanguage().equals("yue")) {
            return path.getNextPoint_yue();
        }
        return path.getNextPoint_cn();
    }

    public static List<String> getAllPOINames() {
        Set<String> pois = new HashSet<>();
        for (PathEntity path : allPaths) {
            pois.add(path.getStartLabel_cn());
            pois.add(path.getEndLabel_cn());
        }
        return new ArrayList<>(pois);
    }

    public static List<String> getNearbyPOIs(String currentLabel, int floor, int limit) {
        Set<String> nearby = new LinkedHashSet<>();
        if (currentLabel == null) return new ArrayList<>();

        for (PathEntity path : allPaths) {
            int pathFloor = 0;
            try { pathFloor = Integer.parseInt(String.valueOf(path.getFloor())); } catch (Exception ignored) {}
            if (pathFloor != floor) continue;

            if (path.getStartLabel_cn().equals(currentLabel)) {
                nearby.add(path.getEndLabel_cn());
            } else if (path.getEndLabel_cn().equals(currentLabel)) {
                nearby.add(path.getStartLabel_cn());
            }
            if (nearby.size() >= limit) break;
        }
        return new ArrayList<>(nearby);
    }
}
