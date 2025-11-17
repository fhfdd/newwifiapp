package com.example.indoornavblind.service.impl;

import android.content.Context;
import android.util.Log;

import com.example.indoornavblind.App;
import com.example.indoornavblind.database.entity.PositionEntity;
import com.example.indoornavblind.database.entity.WiFiFingerprintEntity;
import com.example.indoornavblind.model.Position;
import com.example.indoornavblind.model.WiFiData;
import com.example.indoornavblind.service.LocationService;
import com.example.indoornavblind.service.WiFiScannerService;

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
    private static final int KNN_TOP_N = 3; // 可配置的K值（扩展点）

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
            Position position = matchPosition(currentWifi, 1); // 目标楼层可扩展为参数
            if (position != null) {
                Log.d(TAG, "定位成功：" + position.getLabel());
                callback.onSuccess(position);
            } else {
                Log.e(TAG, "定位失败：未匹配到位置");
                callback.onFailure("定位失败，未匹配到位置");
            }
        });
    }

    // KNN匹配核心逻辑（私有方法，对外封闭修改）
    private Position matchPosition(List<WiFiData> currentWifi, int targetFloor) {
        Log.d(TAG, "开始匹配位置，目标楼层：" + targetFloor);

        // 提取当前WiFi的BSSID列表
        List<String> bssids = new ArrayList<>();
        for (WiFiData wifi : currentWifi) {
            bssids.add(wifi.getBssid());
        }
        Log.d(TAG, "待匹配BSSID列表：" + bssids);

        // 查询数据库中匹配的指纹
        List<WiFiFingerprintEntity> fingerprints = App.getInstance()
                .getDb()
                .wifiFingerprintDao()
                .findByBssidsAndFloor(bssids, targetFloor);

        Log.d(TAG, "数据库查询到" + fingerprints.size() + "条匹配的指纹数据");
        if (fingerprints.isEmpty()) {
            Log.e(TAG, "无匹配的指纹数据（BSSID列表：" + bssids + "，目标楼层：" + targetFloor + "）");
            return null;
        }

        // 计算信号差值（核心算法）
        List<FingerprintDistance> distances = new ArrayList<>();
        for (WiFiFingerprintEntity fp : fingerprints) {
            boolean matched = false;
            for (WiFiData wifi : currentWifi) {
                if (wifi.getBssid().equals(fp.getBssid())) {
                    int diff = Math.abs(wifi.getRssi() - fp.getRssi());
                    distances.add(new FingerprintDistance(fp.getLocationId(), diff));
                    Log.d(TAG, "指纹匹配 - LocationId: " + fp.getLocationId() +
                            ", BSSID: " + fp.getBssid() +
                            ", 实际RSSI: " + wifi.getRssi() +
                            ", 指纹RSSI: " + fp.getRssi() +
                            ", 差值: " + diff);
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                Log.w(TAG, "指纹BSSID未在当前扫描结果中：" + fp.getBssid());
            }
        }

        // 检查是否有有效差值数据
        if (distances.isEmpty()) {
            Log.e(TAG, "未计算到有效信号差值，无法进行KNN匹配");
            return null;
        }

        // 排序并取Top N
        Collections.sort(distances, Comparator.comparingInt(FingerprintDistance::getRssiDiff));
        int takeCount = Math.min(distances.size(), KNN_TOP_N);
        List<FingerprintDistance> topN = distances.subList(0, takeCount);
        Log.d(TAG, "KNN排序完成，取前" + takeCount + "条结果");
        for (int i = 0; i < topN.size(); i++) {
            FingerprintDistance d = topN.get(i);
            Log.d(TAG, "Top " + (i+1) + " - LocationId: " + d.locationId + ", 差值: " + d.rssiDiff);
        }

        // 投票选出最优位置
        Map<Integer, Integer> voteMap = new HashMap<>();
        for (FingerprintDistance d : topN) {
            int count = voteMap.getOrDefault(d.locationId, 0) + 1;
            voteMap.put(d.locationId, count);
            Log.d(TAG, "投票 - LocationId: " + d.locationId + ", 票数: " + count);
        }

        // 找出得票最高的位置ID
        Integer bestId = voteMap.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        if (bestId == null) {
            Log.e(TAG, "投票未选出最优位置");
            return null;
        }
        Log.d(TAG, "投票结果 - 最优LocationId: " + bestId + ", 票数: " + voteMap.get(bestId));

        // 查询位置详情
        PositionEntity entity = App.getInstance().getDb().positionDao().findById(bestId);
        if (entity == null) {
            Log.e(TAG, "位置ID不存在于数据库：" + bestId);
            return null;
        }

        return convertToPosition(entity);
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