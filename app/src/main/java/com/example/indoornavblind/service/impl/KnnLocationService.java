package com.example.indoornavblind.service.impl;

import android.content.Context;
import android.util.Log;

import com.example.indoornavblind.model.Position;
import com.example.indoornavblind.model.WiFiData;
import com.example.indoornavblind.service.LocationService;
import com.example.indoornavblind.service.WiFiScannerService;
import com.example.indoornavblind.database.entity.PositionEntity;
import com.example.indoornavblind.database.entity.WiFiFingerprintEntity;
import com.example.indoornavblind.util.DatabaseManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * KNN定位实现：基于WiFi指纹的定位算法，实现LocationService接口
 */
public class KnnLocationService implements LocationService {
    private static final String TAG = "KnnLocationService";
    private final WiFiScannerService wifiScanner;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Context context;
    private static final int KNN_TOP_N = 3; // 可配置的K值

    // 构造注入依赖（依赖抽象而非具体）
    public KnnLocationService(WiFiScannerService wifiScanner) {
        this.wifiScanner = wifiScanner;
        Log.d(TAG, "KnnLocationService初始化，依赖WiFiScanner：" + wifiScanner.getClass().getSimpleName());
    }

    @Override
    public void init(Context context) {
        this.context = context;
        Log.d(TAG, "KnnLocationService初始化上下文");
    }

    @Override
    public void locate(LocationCallback callback) {
        Log.d(TAG, "开始定位流程");

        // 权限检查
        if (!wifiScanner.hasPermission()) {
            Log.e(TAG, "定位失败：缺少位置权限");
            callback.onFailure("请授予位置权限");
            return;
        }

        // 扫描WiFi
        List<WiFiData> currentWifi = wifiScanner.scanWiFi();
        Log.d(TAG, "WiFi扫描完成，获取到" + currentWifi.size() + "个有效信号");

        if (currentWifi.isEmpty()) {
            Log.e(TAG, "定位失败：未检测到WiFi信号");
            callback.onFailure("未检测到WiFi信号");
            return;
        }

        // 打印扫描到的WiFi详情
        for (WiFiData wifi : currentWifi) {
            Log.d(TAG, "扫描到WiFi - BSSID: " + wifi.getBssid() + ", RSSI: " + wifi.getRssi());
        }

        // 子线程执行匹配（不阻塞主线程）
        executor.execute(() -> {
            Log.d(TAG, "开始KNN匹配计算（子线程）");
            // 提取当前WiFi的BSSID和RSSI，构建快速查询映射
            Map<String, Integer> currentWifiMap = new HashMap<>();
            for (WiFiData wifi : currentWifi) {
                currentWifiMap.put(wifi.getBssid(), wifi.getRssi());
            }
            List<String> bssids = new ArrayList<>(currentWifiMap.keySet());
            Log.d(TAG, "待匹配BSSID列表：" + bssids);

            // 异步查询指纹，通过回调继续处理
            DatabaseManager.getWiFiFingerprintsByBssids(bssids, new DatabaseManager.OnOperationCallback<List<WiFiFingerprintEntity>>() {
                @Override
                public void onSuccess(List<WiFiFingerprintEntity> fingerprints) {
                    handleFingerprints(fingerprints, currentWifiMap, callback);
                }

                @Override
                public void onFailure(Exception e) {
                    Log.e(TAG, "指纹查询失败", e);
                    callback.onFailure("定位失败：数据库错误");
                }
            });
        });
    }

    /**
     * 处理指纹匹配逻辑
     */
    private void handleFingerprints(List<WiFiFingerprintEntity> fingerprints,
                                    Map<String, Integer> currentWifiMap,
                                    LocationCallback callback) {
        Log.d(TAG, "数据库查询到" + fingerprints.size() + "条匹配的指纹数据");
        if (fingerprints.isEmpty()) {
            Log.e(TAG, "无匹配的指纹数据");
            callback.onFailure("定位失败：未匹配到指纹");
            return;
        }

        // 1. 按locationId分组，计算每个位置的综合信号差值
        Map<Integer, List<Integer>> locationDiffMap = new HashMap<>();
        for (WiFiFingerprintEntity fp : fingerprints) {
            Integer currentRssi = currentWifiMap.get(fp.getBssid());
            if (currentRssi != null) {
                int diff = Math.abs(currentRssi - fp.getRssi());
                // 手动检查并初始化列表
                List<Integer> diffList = locationDiffMap.get(fp.getLocationId());
                if (diffList == null) {
                    diffList = new ArrayList<>();
                    locationDiffMap.put(fp.getLocationId(), diffList);
                }
                diffList.add(diff);

                Log.d(TAG, "指纹匹配 - LocationId: " + fp.getLocationId() +
                        ", BSSID: " + fp.getBssid() +
                        ", 实际RSSI: " + currentRssi +
                        ", 指纹RSSI: " + fp.getRssi() +
                        ", 差值: " + diff);
            } else {
                Log.w(TAG, "指纹BSSID未在当前扫描结果中：" + fp.getBssid());
            }
        }

        // 检查是否有有效分组数据
        if (locationDiffMap.isEmpty()) {
            Log.e(TAG, "未计算到有效信号差值，无法进行KNN匹配");
            callback.onFailure("定位失败：信号匹配异常");
            return;
        }

        // 2. 计算每个位置的平均差值
        List<LocationDistance> locationDistances = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> entry : locationDiffMap.entrySet()) {
            int locationId = entry.getKey();
            List<Integer> diffs = entry.getValue();

            // 手动计算平均值
            int sum = 0;
            for (int diff : diffs) {
                sum += diff;
            }
            double avgDiff = sum / (double) diffs.size(); // 避免整数除法

            locationDistances.add(new LocationDistance(locationId, avgDiff, diffs.size()));
            Log.d(TAG, "位置ID: " + locationId + ", 匹配到" + diffs.size() + "个WiFi, 平均差值: " + avgDiff);
        }

        // 3. 按平均差值排序，取前K个（K=KNN_TOP_N）
        Collections.sort(locationDistances, new Comparator<LocationDistance>() {
            @Override
            public int compare(LocationDistance d1, LocationDistance d2) {
                return Double.compare(d1.avgDiff, d2.avgDiff);
            }
        });
        int takeCount = Math.min(locationDistances.size(), KNN_TOP_N);
        List<LocationDistance> topN = locationDistances.subList(0, takeCount);
        Log.d(TAG, "KNN排序完成，取前" + takeCount + "条结果");
        for (int i = 0; i < topN.size(); i++) {
            LocationDistance d = topN.get(i);
            Log.d(TAG, "Top " + (i+1) + " - LocationId: " + d.locationId + ", 平均差值: " + d.avgDiff + ", 匹配数量: " + d.matchCount);
        }

        // 4. 带权重的投票
        Map<Integer, Double> weightMap = new HashMap<>();
        for (LocationDistance d : topN) {
            double weight = (1.0 / (d.avgDiff + 0.001)) * d.matchCount;
            // 更新权重
            if (weightMap.containsKey(d.locationId)) {
                weightMap.put(d.locationId, weightMap.get(d.locationId) + weight);
            } else {
                weightMap.put(d.locationId, weight);
            }
            Log.d(TAG, "投票 - LocationId: " + d.locationId + ", 权重: " + weight);
        }

        // 5. 找出权重最高的位置ID（声明为final解决内部类访问问题）
        final Integer bestId;
        double maxWeight = -1;
        Integer tempBestId = null;
        for (Map.Entry<Integer, Double> entry : weightMap.entrySet()) {
            if (entry.getValue() > maxWeight) {
                maxWeight = entry.getValue();
                tempBestId = entry.getKey();
            }
        }
        bestId = tempBestId;

        if (bestId == null) {
            Log.e(TAG, "投票未选出最优位置");
            callback.onFailure("定位失败：无法确定位置");
            return;
        }
        Log.d(TAG, "投票结果 - 最优LocationId: " + bestId + ", 总权重: " + weightMap.get(bestId));

        // 异步查询位置详情
        DatabaseManager.getPositionById(bestId, new DatabaseManager.OnOperationCallback<PositionEntity>() {
            @Override
            public void onSuccess(PositionEntity entity) {
                if (entity == null) {
                    Log.e(TAG, "位置ID不存在于数据库：" + bestId);
                    callback.onFailure("定位失败：位置信息缺失");
                    return;
                }
                Position position = convertToPosition(entity);
                Log.d(TAG, "定位成功：" + position.getLabel());
                callback.onSuccess(position);
            }

            @Override
            public void onFailure(Exception e) {
                Log.e(TAG, "查询位置信息失败", e);
                callback.onFailure("定位失败：位置查询错误");
            }
        });
    }

    // 内部辅助类：存储位置ID、平均差值和匹配数量
    private static class LocationDistance {
        int locationId;
        double avgDiff;
        int matchCount;

        LocationDistance(int locationId, double avgDiff, int matchCount) {
            this.locationId = locationId;
            this.avgDiff = avgDiff;
            this.matchCount = matchCount;
        }
    }

    // 实体转换（封闭修改）
    private Position convertToPosition(PositionEntity entity) {
        if (entity == null) return null;
        Position position = new Position();
        position.setLabel(entity.getLabel());
        position.setFloor(entity.getFloor());
        position.setPixelX(entity.getPixelX());
        position.setPixelY(entity.getPixelY());
        position.setZone(entity.getZone());
        Log.d(TAG, "位置转换完成：" + entity.getLabel() + "（楼层：" + entity.getFloor() + "）");
        return position;
    }

    // 内部辅助类（封闭）
    private static class FingerprintDistance {
        int locationId;
        int rssiDiff;

        FingerprintDistance(int locationId, int rssiDiff) {
            this.locationId = locationId;
            this.rssiDiff = rssiDiff;
        }

        int getRssiDiff() { return rssiDiff; }
    }
}