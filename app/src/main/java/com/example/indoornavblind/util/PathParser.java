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
    private static List<PathEntity> allPaths = new ArrayList<>();
    private static boolean isInitialized = false;

    public static void init(Context context) {
        if (isInitialized) return;
        
        try {
            InputStream is = context.getAssets().open("path_db.json");
            InputStreamReader reader = new InputStreamReader(is);
            Gson gson = new Gson();
            Type type = new TypeToken<List<PathEntity>>(){}.getType();
            allPaths = gson.fromJson(reader, type);
            isInitialized = true;
            Log.d(TAG, "路径数据加载成功，共" + allPaths.size() + "条");
            reader.close();
        } catch (Exception e) {
            Log.e(TAG, "加载路径数据失败", e);
            allPaths = new ArrayList<>();
        }
    }

    public static List<PathEntity> getAllPaths() {
        return allPaths;
    }

    public static List<PathEntity> getFullPath(String start, String end) {
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
                if (path.getStartLabel_cn().equals(current) && 
                    !visited.contains(path.getEndLabel_cn())) {
                    queue.offer(path.getEndLabel_cn());
                    visited.add(path.getEndLabel_cn());
                    previous.put(path.getEndLabel_cn(), current);
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
            if (path.getStartLabel_cn().equals(from) && 
                path.getEndLabel_cn().equals(to)) {
                return path;
            }
        }
        return null;
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
}
