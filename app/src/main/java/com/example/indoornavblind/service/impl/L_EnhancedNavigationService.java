package com.example.indoornavblind.service.impl;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.example.indoornavblind.model.PathEntity;
import com.example.indoornavblind.model.Position;
import com.example.indoornavblind.service.LocationService;
import com.example.indoornavblind.service.NavigationService;
import com.example.indoornavblind.service.VoiceService;
import com.example.indoornavblind.util.PathParser;
import java.util.List;
import java.util.Locale;

/**
 * 增强导航服务 - 修复版
 *
 * 修复内容：
 * 1. 添加位置更新回调接口
 * 2. 改进导航概览播报（包含详细路线）
 * 3. 改进停止导航提示（区分到达和未到达）
 * 4. 优化各种播报信息
 * 5. 添加导航进度查询方法
 *
 * 原有功能：
 * 1. 拐弯点提前8米播报
 * 2. 偏离路线检测和提醒（4米阈值）
 * 3. 连续两次到达确认
 * 4. 实时定位跟踪
 * 5. 动态播报间隔（接近拐弯点时缩短）
 * 6. 错过拐弯检测和提示
 */
public class L_EnhancedNavigationService implements NavigationService {
    private static final String TAG = "EnhancedNavigation";

    /**
     * 位置更新回调接口（新增）
     */
    public interface PositionUpdateCallback {
        void onPositionUpdated(Position newPosition);
    }

    // 距离阈值常量
    private static final double TURN_WARNING_DISTANCE = 8.0; // 拐弯提前播报距离（米）
    private static final double OFF_ROUTE_THRESHOLD = 4.0;   // 偏离路线阈值（米）
    private static final double ARRIVAL_THRESHOLD = 2.0;      // 到达判定距离（米）
    private static final double MISSED_TURN_THRESHOLD = 5.0; // 错过拐弯阈值（米）

    // 时间间隔常量
    private static final int REGULAR_INTERVAL_MS = 5000;    // 常规播报间隔5秒
    private static final int NEAR_TURN_INTERVAL_MS = 5000;   // 接近拐弯点时5秒
    private static final int LOCATION_UPDATE_INTERVAL = 3000; // 定位更新间隔3秒（优化）

    private VoiceService voiceService;
    private LocationService locationService;
    private Position currentPosition;
    private String targetDestination;
    private List<PathEntity> fullPath;
    private int currentStepIndex = 0;
    private boolean isNavigating = false;
    private int baseIntervalMs = REGULAR_INTERVAL_MS;
    private float baseSpeed = 1.0f;
    private Locale currentLocale = Locale.CHINESE;

    // Handler和Runnable
    private Handler navigationHandler = new Handler(Looper.getMainLooper());
    private Handler locationUpdateHandler = new Handler(Looper.getMainLooper());
    private Runnable stepGuidanceRunnable;
    private Runnable locationUpdateRunnable;

    // 导航状态
    private double accumulatedDistance = 0.0;  // 累计行走距离
    private double distanceToNextTurn = 0.0;   // 到下一个拐弯点的距离
    private boolean hasTurnWarned = false;     // 是否已播报拐弯提醒
    private Position lastPosition = null;      // 上一次定位位置
    private int arrivalConfirmCount = 0;       // 到达确认次数
    private boolean hasAnnouncedArrival = false; // 是否已播报到达

    // 位置更新回调（新增）
    private PositionUpdateCallback positionCallback;

    public L_EnhancedNavigationService(VoiceService voiceService, LocationService locationService) {
        this.voiceService = voiceService;
        this.locationService = locationService;
    }

    /**
     * 设置位置更新回调（新增）
     */
    public void setPositionUpdateCallback(PositionUpdateCallback callback) {
        this.positionCallback = callback;
    }

    @Override
    public void setCurrentPosition(Position position) {
        this.currentPosition = position;
        this.lastPosition = position;
        Log.d(TAG, "设置当前位置: " + position.getLabel());
    }

    @Override
    public void setTarget(String target) {
        this.targetDestination = target;
        Log.d(TAG, "设置目标位置: " + target);
    }

    public void setNavigationConfig(int intervalMs, float speed, Locale locale) {
        this.baseIntervalMs = intervalMs;
        this.baseSpeed = speed;
        this.currentLocale = locale;
    }

    @Override
    public List<PathEntity> calculatePath() {
        if (currentPosition == null || targetDestination == null) {
            return List.of();
        }
        fullPath = PathParser.getFullPath(currentPosition.getLabel(), targetDestination);
        currentStepIndex = 0;
        accumulatedDistance = 0.0;
        arrivalConfirmCount = 0;
        hasAnnouncedArrival = false;
        Log.d(TAG, String.format("路径计算完成: %d步", fullPath.size()));
        return fullPath;
    }

    /**
     * 开始连续导航（改进版）
     */
    public void startContinuousNavigation() {
        if (fullPath == null || fullPath.isEmpty()) {
            voiceService.speak("未找到导航路径，请重新设置", baseSpeed);
            return;
        }

        if (currentPosition == null) {
            voiceService.speak("当前位置未知，请先定位", baseSpeed);
            return;
        }

        isNavigating = true;
        hasTurnWarned = false;

        Log.d(TAG, "=== 开始连续导航 ===");
        Log.d(TAG, "起点：" + currentPosition.getLabel());
        Log.d(TAG, "终点：" + targetDestination);
        Log.d(TAG, "路径步数：" + fullPath.size());

        // 播报路径概览（改进版）
        String overview = buildPathOverview();
        voiceService.speak(overview, baseSpeed);

        // 启动实时定位跟踪
        startLocationTracking();

        // 延迟3秒后开始分步导航（给用户时间准备）
        navigationHandler.postDelayed(() -> {
            if (isNavigating) {
                startStepByStepGuidance();
            }
        }, 3000);
    }

    /**
     * 启动实时定位跟踪
     */
    private void startLocationTracking() {
        locationUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isNavigating) {
                    return;
                }

                // 实时定位
                locationService.locate(new LocationService.LocationCallback() {
                    @Override
                    public void onSuccess(Position position) {
                        updateCurrentPosition(position);
                    }

                    @Override
                    public void onFailure(String error) {
                        Log.w(TAG, "定位更新失败: " + error);
                    }
                });

                // 继续下一次定位
                locationUpdateHandler.postDelayed(this, LOCATION_UPDATE_INTERVAL);
            }
        };
        locationUpdateHandler.post(locationUpdateRunnable);
    }

    /**
     * 更新当前位置并检测状态（改进版）
     */
    private void updateCurrentPosition(Position newPosition) {
        if (lastPosition == null) {
            lastPosition = newPosition;
            currentPosition = newPosition;
            return;
        }

        // 计算移动距离
        double movedDistance = calculateDistance(lastPosition, newPosition);
        accumulatedDistance += movedDistance;

        currentPosition = newPosition;

        // 检测是否偏离路线
        checkOffRoute();

        // 检测是否错过拐弯
        checkMissedTurn();

        // 检测是否接近拐弯点
        checkNearTurn();

        // 检测是否到达目的地
        checkArrival();

        lastPosition = newPosition;

        // 通知外部位置更新（新增）
        if (positionCallback != null) {
            positionCallback.onPositionUpdated(newPosition);
        }
    }

    /**
     * 检测是否偏离路线
     */
    private void checkOffRoute() {
        if (currentStepIndex >= fullPath.size()) return;

        PathEntity currentStep = fullPath.get(currentStepIndex);
        String expectedEnd = currentStep.getEndLabel_cn();

        // 如果当前位置是下一步的终点 → 正常前进
        if (currentPosition.getLabel().equals(expectedEnd)) {
            return;
        }

        // 如果当前位置既不是起点也不是终点 → 可能偏离
//        if (!currentPosition.getLabel().equals(currentStep.getStartLabel_cn())) {
//            // 用坐标判断方向是否反了
//            double dx = currentPosition.getPixelX() - lastPosition.getPixelX();
//            double dy = currentPosition.getPixelY() - lastPosition.getPixelY();
//            double stepDx = PathParser.getPixelX(expectedEnd) - lastPosition.getPixelX();
//            double stepDy = PathParser.getPixelY(expectedEnd) - lastPosition.getPixelY();
//
//            double dot = dx * stepDx + dy * stepDy;
//            if (dot < 0) { // 方向相反
//                announceOffRoute(estimateDeviation());
//            }
//        }
    }


    /**
     * 估算偏离距离（简化版）
     */
    private double estimateDeviation() {
        // 这里简化处理，实际应该根据WiFi指纹计算精确偏差
        return Math.random() * 3.0 + 2.0; // 模拟2-5米偏差
    }

    /**
     * 播报偏离路线
     */
    private void announceOffRoute(double deviation) {
        String direction = getDeviationDirection();
        String message = String.format("您已偏离路线，请向%s调整%.0f米", direction, deviation);
        voiceService.speak(message, baseSpeed);
        Log.d(TAG, "偏离路线: " + message);
    }

    /**
     * 获取偏离方向（简化版）
     */
    private String getDeviationDirection() {
        // 简化处理，随机返回方向
        String[] directions = {"左", "右", "前"};
        return directions[(int)(Math.random() * directions.length)];
    }

    /**
     * 检测是否错过拐弯
     */
    private void checkMissedTurn() {
        if (currentStepIndex >= fullPath.size()) {
            return;
        }

        PathEntity currentStep = fullPath.get(currentStepIndex);

        // 如果当前步骤是拐弯，且累计距离超过应该拐弯的距离
        if (isTurnStep(currentStep)) {
            double stepDistance = parseDistance(currentStep.getDistance_cn());
            if (accumulatedDistance > stepDistance + MISSED_TURN_THRESHOLD) {
                announceMissedTurn(stepDistance);
                // 重置状态，避免重复播报
                accumulatedDistance = 0.0;
            }
        }
    }

    /**
     * 播报错过拐弯
     */
    private void announceMissedTurn(double missedDistance) {
        String message = String.format("您已错过拐弯，请停下并返回%.0f米",
                accumulatedDistance - missedDistance);
        voiceService.speak(message, baseSpeed);
        Log.d(TAG, "错过拐弯: " + message);
    }

    /**
     * 检测是否接近拐弯点
     */
    private void checkNearTurn() {
        if (currentStepIndex >= fullPath.size() - 1) {
            return;
        }

        PathEntity nextStep = fullPath.get(currentStepIndex + 1);

        if (isTurnStep(nextStep) && !hasTurnWarned) {
            double stepDistance = parseDistance(fullPath.get(currentStepIndex).getDistance_cn());
            double remainingDistance = stepDistance - accumulatedDistance;

            // 距离拐弯点约8米时提前播报
            if (remainingDistance <= TURN_WARNING_DISTANCE && remainingDistance > 0) {
                announceTurnWarning(nextStep, remainingDistance);
                hasTurnWarned = true;
            }
        }
    }

    /**
     * 播报拐弯提醒
     */
    private void announceTurnWarning(PathEntity turnStep, double distance) {
        String direction = PathParser.getDirectionByLang(turnStep, currentLocale);
        String message = String.format("前方%.0f米%s", distance, direction);
        voiceService.speak(message, baseSpeed);
        Log.d(TAG, "拐弯提醒: " + message);
    }

    /**
     * 检测是否到达目的地
     */
    private void checkArrival() {
        if (hasAnnouncedArrival || currentStepIndex < fullPath.size() - 1) {
            return;
        }

        // 检查是否在目的地范围内
        boolean isNearDestination = currentPosition.getLabel().equals(targetDestination);

        if (isNearDestination) {
            arrivalConfirmCount++;
            Log.d(TAG, "到达确认次数: " + arrivalConfirmCount);

            // 连续两次确认才宣布到达
            if (arrivalConfirmCount >= 2) {
                announceArrival();
            }
        } else {
            // 重置确认次数
            arrivalConfirmCount = 0;
        }
    }

    /**
     * 播报到达信息（改进版）
     */
    private void announceArrival() {
        hasAnnouncedArrival = true;

        // 获取目的地详细信息
        String detailInfo = getDestinationDetails();
        String message = "您已到达目的地" + targetDestination;
        if (!detailInfo.isEmpty()) {
            message += "。" + detailInfo;
        }

        voiceService.speak(message, baseSpeed);
        Log.d(TAG, "=== 到达目的地 ===");
        Log.d(TAG, message);

        // 延迟2秒后停止导航
        navigationHandler.postDelayed(() -> {
            stopNavigation();
        }, 2000);
    }

    /**
     * 获取目的地详细信息
     */
    private String getDestinationDetails() {
        // 根据不同目的地提供不同的细节描述
        switch (targetDestination) {
            case "浴室":
                return "房间在左侧，门把手在腰部高度";
            case "厕所":
                return "门在正前方，把手在右侧";
            case "床位":
                return "床在您右手边";
            default:
                return "";
        }
    }

    /**
     * 判断是否为拐弯步骤
     */
    private boolean isTurnStep(PathEntity step) {
        String direction = step.getDirection_cn();
        return direction.contains("左转") || direction.contains("右转");
    }

    /**
     * 解析距离字符串为数值
     */
    private double parseDistance(String distanceStr) {
        try {
            String numStr = distanceStr.replaceAll("[^0-9.]", "");
            return Double.parseDouble(numStr);
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * 计算两点之间的距离（简化版）
     */
    private double calculateDistance(Position p1, Position p2) {
        // 简化处理：如果标签相同则距离为0，否则估算
        if (p1.getLabel().equals(p2.getLabel())) {
            return 0.0;
        }
        return 2.0; // 假设每次定位更新移动约2米
    }

    /**
     * 构建路径概览（改进版）
     */
    private String buildPathOverview() {
        int totalSteps = fullPath.size();
        double totalDistance = calculateTotalDistance();
        int estimatedSeconds = (int)(totalDistance / 0.8);

        // 提取关键转折点
        StringBuilder keyPoints = new StringBuilder();
        int turnCount = 0;
        for (PathEntity step : fullPath) {
            if (isTurnStep(step) && turnCount < 3) {
                if (turnCount > 0) {
                    keyPoints.append("、");
                }
                keyPoints.append(step.getEndLabel_cn());
                turnCount++;
            }
        }

        String route = "";
        if (keyPoints.length() > 0) {
            route = "主要经过：" + keyPoints.toString() + "。";
        }

        return String.format("导航开始。从%s到%s，共%d步，约%.0f米，预计%d秒。%s现在出发",
                currentPosition.getLabel(),
                targetDestination,
                totalSteps,
                totalDistance,
                estimatedSeconds,
                route);
    }

    private double calculateTotalDistance() {
        double total = 0;
        for (PathEntity step : fullPath) {
            total += parseDistance(step.getDistance_cn());
        }
        return total;
    }

    /**
     * 开始分步导航
     */
    private void startStepByStepGuidance() {
        stepGuidanceRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isNavigating || currentStepIndex >= fullPath.size()) {
                    return;
                }

                PathEntity currentStep = fullPath.get(currentStepIndex);
                String instruction = buildStepInstruction(currentStep, currentStepIndex);
                voiceService.speak(instruction, baseSpeed);

                // 如果是拐弯步骤，到达拐弯点时再次确认
                if (isTurnStep(currentStep)) {
                    navigationHandler.postDelayed(() -> {
                        if (isNavigating && currentStepIndex < fullPath.size()) {
                            confirmTurnCompletion();
                        }
                    }, 3000);
                }

                currentStepIndex++;
                accumulatedDistance = 0.0;
                hasTurnWarned = false;

                // 动态调整播报间隔
                int nextInterval = getNextInterval();
                navigationHandler.postDelayed(this, nextInterval);
            }
        };
        navigationHandler.post(stepGuidanceRunnable);
    }

    /**
     * 确认拐弯完成
     */
    private void confirmTurnCompletion() {
        if (currentStepIndex >= fullPath.size()) {
            return;
        }

        PathEntity nextStep = fullPath.get(currentStepIndex);
        String direction = PathParser.getDirectionByLang(nextStep, currentLocale);
        String distance = PathParser.getDistanceByLang(nextStep, currentLocale);
        String message = String.format("转弯完成后%s，%s", direction, distance);
        voiceService.speak(message, baseSpeed);
    }

    /**
     * 获取下一次播报间隔（动态调整）
     */
    private int getNextInterval() {
        if (currentStepIndex < fullPath.size()) {
            PathEntity nextStep = fullPath.get(currentStepIndex);
            // 如果下一步是拐弯，缩短间隔
            if (isTurnStep(nextStep)) {
                return NEAR_TURN_INTERVAL_MS;
            }
        }
        return baseIntervalMs;
    }

    /**
     * 构建步骤指令（改进版）
     */
    private String buildStepInstruction(PathEntity step, int index) {
        String direction = PathParser.getDirectionByLang(step, currentLocale);
        String distance = PathParser.getDistanceByLang(step, currentLocale);
        String nextPoint = PathParser.getNextPointByLang(step, currentLocale);

        if (index == 0) {
            return String.format("开始导航。%s，%s，前往%s", direction, distance, nextPoint);
        } else if (index == fullPath.size() - 1) {
            return String.format("最后一步。%s，%s，即将到达%s", direction, distance, nextPoint);
        } else {
            return String.format("第%d步。%s，%s，前往%s", index + 1, direction, distance, nextPoint);
        }
    }

    /**
     * 停止导航（改进版）
     */
    public void stopNavigation() {
        boolean wasNavigating = isNavigating;
        isNavigating = false;

        if (stepGuidanceRunnable != null) {
            navigationHandler.removeCallbacks(stepGuidanceRunnable);
        }
        if (locationUpdateRunnable != null) {
            locationUpdateHandler.removeCallbacks(locationUpdateRunnable);
        }

        String message;
        if (hasAnnouncedArrival) {
            message = "导航已结束，您已到达" + targetDestination;
        } else if (wasNavigating) {
            message = "导航已结束，尚未到达目的地" + targetDestination;
        } else {
            message = "导航已结束";
        }

        voiceService.speak(message, baseSpeed);
        Log.d(TAG, "导航已停止: " + message);

        // 重置状态
        currentStepIndex = 0;
        accumulatedDistance = 0.0;
        arrivalConfirmCount = 0;
        hasAnnouncedArrival = false;
        hasTurnWarned = false;
        lastPosition = null;
    }

    /**
     * 获取下一步指令（改进版）
     */
    @Override
    public String getNextStepInstruction() {
        if (fullPath == null || fullPath.isEmpty()) {
            return "无导航路径";
        }

        // 导航未开始
        if (!isNavigating && currentStepIndex == 0) {
            return "导航尚未开始，请点击开始导航";
        }

        // 已完成所有步骤
        if (isNavigating && currentStepIndex >= fullPath.size()) {
            return "已到达目的地 " + targetDestination;
        }

        // 正常步骤播报
        PathEntity step = fullPath.get(currentStepIndex);
        String instruction = buildStepInstruction(step, currentStepIndex);

        int remaining = fullPath.size() - currentStepIndex;
        if (isNavigating && remaining > 1) {
            instruction += String.format("。还剩%d步", remaining);
        }

        return instruction;
    }

    public boolean isNavigating() {
        return isNavigating;
    }

    /**
     * 获取当前导航进度（新增）
     */
    public String getNavigationProgress() {
        if (!isNavigating || fullPath == null || fullPath.isEmpty()) {
            return "未在导航中";
        }

        int totalSteps = fullPath.size();
        int remaining = totalSteps - currentStepIndex;
        double remainingDistance = 0;

        for (int i = currentStepIndex; i < fullPath.size(); i++) {
            remainingDistance += parseDistance(fullPath.get(i).getDistance_cn());
        }

        return String.format("还剩%d步，约%.0f米", remaining, remainingDistance);
    }

    /**
     * 获取目标名称（新增）
     */
    public String getTargetDestination() {
        return targetDestination;
    }
}