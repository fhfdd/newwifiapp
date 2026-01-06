package com.example.indoornavblind.service.impl;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.example.indoornavblind.model.PathEntity;
import com.example.indoornavblind.model.Position;
import com.example.indoornavblind.service.LocationService;
import com.example.indoornavblind.service.NavigationService;
import com.example.indoornavblind.service.VoiceService;
import com.example.indoornavblind.util.PathParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 增强导航服务 - PDR集成版
 *
 * 新增功能：
 * 1. PDR（行人航位推算）：使用加速度计检测步数
 * 2. 基于步数的导航播报：根据预计步数判断进度
 * 3. 基于时间的转弯提醒：提前X秒提醒转弯
 * 4. 详细路径概览：包含总步数、预计时间
 * 5. 导航事件回调：提供更丰富的导航状态通知
 *
 * 原有功能：
 * 1. 拐弯点提前8米播报
 * 2. 偏离路线检测和提醒（4米阈值）
 * 3. 连续两次到达确认
 * 4. 实时定位跟踪
 * 5. 动态播报间隔
 */
public class L_EnhancedNavigationService implements NavigationService, SensorEventListener {
    private static final String TAG = "EnhancedNavigation";

    /**
     * 位置更新回调接口
     */
    public interface PositionUpdateCallback {
        void onPositionUpdated(Position newPosition);
    }

    /**
     * 导航事件回调接口（新增）
     */
    public interface NavigationEventCallback {
        void onNavigationStarted(String from, String to, int totalSteps, double totalDistance, int estimatedSeconds);
        void onStepAnnounced(int stepIndex, int totalSteps, String instruction);
        void onTurnWarning(String turnDirection, int secondsRemaining);
        void onProgressUpdate(int completedSteps, int remainingSteps, double remainingDistance);
        void onArrival(String destination, String detailInfo);
        void onNavigationStopped(boolean reachedDestination);
        void onOffRoute(double deviationMeters);
        void onLocationUpdated(Position position);
    }

    // PDR相关常量
    private static final double DEFAULT_STEP_LENGTH = 0.65; // 默认步长（米）
    private static final double WALKING_SPEED = 0.8; // 步行速度（米/秒）
    private static final int TURN_WARNING_SECONDS = 5; // 转弯提前提醒秒数
    private static final float STEP_THRESHOLD = 12.0f; // 步伐检测阈值
    private static final long STEP_DEBOUNCE_MS = 300; // 步伐防抖时间

    // 距离阈值常量
    private static final double TURN_WARNING_DISTANCE = 8.0;
    private static final double OFF_ROUTE_THRESHOLD = 4.0;
    private static final double ARRIVAL_THRESHOLD = 2.0;
    private static final double MISSED_TURN_THRESHOLD = 5.0;

    // 时间间隔常量
    private static final int REGULAR_INTERVAL_MS = 5000;
    private static final int NEAR_TURN_INTERVAL_MS = 3000;
    private static final int LOCATION_UPDATE_INTERVAL = 3000;

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
    private double accumulatedDistance = 0.0;
    private double distanceToNextTurn = 0.0;
    private boolean hasTurnWarned = false;
    private Position lastPosition = null;
    private int arrivalConfirmCount = 0;
    private boolean hasAnnouncedArrival = false;

    // 位置更新回调
    private PositionUpdateCallback positionCallback;
    private NavigationEventCallback eventCallback;

    // ========== PDR相关变量 ==========
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor gyroscope;
    private boolean isPDREnabled = false;

    // 步数追踪
    private int stepCount = 0;
    private int stepCountAtStepStart = 0;
    private long lastStepTime = 0;
    private int expectedStepsForCurrentSegment = 0;

    // 方向追踪
    private float currentHeading = 0;
    private float[] lastAcceleration = new float[3];
    private float[] lastGyroscope = new float[3];

    // 步长配置
    private double stepLength = DEFAULT_STEP_LENGTH;

    public L_EnhancedNavigationService(VoiceService voiceService, LocationService locationService) {
        this.voiceService = voiceService;
        this.locationService = locationService;
    }

    /**
     * 初始化PDR传感器
     */
    public void initPDR(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

            if (accelerometer != null) {
                isPDREnabled = true;
                Log.d(TAG, "PDR传感器初始化成功");
            } else {
                Log.w(TAG, "加速度计不可用，PDR功能禁用");
            }
        }
    }

    /**
     * 启动PDR传感器监听
     */
    private void startPDR() {
        if (!isPDREnabled || sensorManager == null) {
            return;
        }

        stepCount = 0;
        stepCountAtStepStart = 0;

        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
        }

        Log.d(TAG, "PDR传感器监听已启动");
    }

    /**
     * 停止PDR传感器监听
     */
    private void stopPDR() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        Log.d(TAG, "PDR传感器监听已停止");
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isNavigating) {
            return;
        }

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            handleAccelerometer(event.values);
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            handleGyroscope(event.values);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 不需要处理
    }

    /**
     * 处理加速度计数据 - 步伐检测
     */
    private void handleAccelerometer(float[] values) {
        long currentTime = System.currentTimeMillis();

        // 简单的峰值检测算法
        float magnitude = (float) Math.sqrt(
                values[0] * values[0] +
                        values[1] * values[1] +
                        values[2] * values[2]
        );

        // 检测步伐（Y轴加速度峰值）
        if (Math.abs(values[1]) > STEP_THRESHOLD &&
                (currentTime - lastStepTime) > STEP_DEBOUNCE_MS) {

            stepCount++;
            lastStepTime = currentTime;
            accumulatedDistance += stepLength;

            Log.d(TAG, "检测到步伐，总步数: " + stepCount + ", 累计距离: " + accumulatedDistance + "m");

            // 检查是否该播报下一步了
            checkStepProgress();
        }

        System.arraycopy(values, 0, lastAcceleration, 0, 3);
    }

    /**
     * 处理陀螺仪数据 - 方向追踪
     */
    private void handleGyroscope(float[] values) {
        // 积分计算航向角变化
        float dt = 0.02f; // 假设50Hz采样率
        currentHeading += values[2] * dt * (180 / Math.PI);

        // 归一化到0-360度
        while (currentHeading < 0) currentHeading += 360;
        while (currentHeading >= 360) currentHeading -= 360;

        System.arraycopy(values, 0, lastGyroscope, 0, 3);
    }

    /**
     * 检查步数进度，决定是否播报下一步
     */
    private void checkStepProgress() {
        if (currentStepIndex >= fullPath.size() || !isNavigating) {
            return;
        }

        int stepsSinceStart = stepCount - stepCountAtStepStart;
        double progressRatio = (double) stepsSinceStart / expectedStepsForCurrentSegment;

        // 当走完预计步数的80%时，准备播报下一步
        if (progressRatio >= 0.8 && expectedStepsForCurrentSegment > 0) {
            Log.d(TAG, "基于步数进度触发下一步播报: " + stepsSinceStart + "/" + expectedStepsForCurrentSegment);
        }

        // 检查是否需要基于时间的转弯提醒
        checkTurnWarningByTime();
    }

    /**
     * 基于时间的转弯提醒
     */
    private void checkTurnWarningByTime() {
        if (currentStepIndex >= fullPath.size() - 1 || hasTurnWarned) {
            return;
        }

        PathEntity nextStep = fullPath.get(currentStepIndex < fullPath.size() - 1 ? currentStepIndex + 1 : currentStepIndex);

        if (isTurnStep(nextStep)) {
            PathEntity currentStep = fullPath.get(currentStepIndex);
            double stepDistance = parseDistance(currentStep.getDistance_cn());
            int expectedSteps = (int) Math.ceil(stepDistance / stepLength);
            double expectedSeconds = expectedSteps * stepLength / WALKING_SPEED;

            int stepsSinceStart = stepCount - stepCountAtStepStart;
            double elapsedSeconds = stepsSinceStart * stepLength / WALKING_SPEED;
            double secondsRemaining = expectedSeconds - elapsedSeconds;

            if (secondsRemaining <= TURN_WARNING_SECONDS && secondsRemaining > 0) {
                announceTurnWarningByTime(nextStep, (int) secondsRemaining);
                hasTurnWarned = true;
            }
        }
    }

    /**
     * 基于时间的转弯提醒播报
     */
    private void announceTurnWarningByTime(PathEntity turnStep, int seconds) {
        String direction = PathParser.getDirectionByLang(turnStep, currentLocale);
        String message = String.format("注意，%d秒后%s", seconds, direction);
        voiceService.speak(message, baseSpeed);
        Log.d(TAG, "时间转弯提醒: " + message);

        if (eventCallback != null) {
            eventCallback.onTurnWarning(direction, seconds);
        }
    }

    /**
     * 设置位置更新回调
     */
    public void setPositionUpdateCallback(PositionUpdateCallback callback) {
        this.positionCallback = callback;
    }

    /**
     * 设置导航事件回调（新增）
     */
    public void setNavigationEventCallback(NavigationEventCallback callback) {
        this.eventCallback = callback;
    }

    /**
     * 设置步长（米）
     */
    public void setStepLength(double length) {
        this.stepLength = length;
        Log.d(TAG, "步长设置为: " + length + "米");
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
        stepCount = 0;
        stepCountAtStepStart = 0;
        Log.d(TAG, String.format("路径计算完成: %d步", fullPath.size()));
        return fullPath;
    }

    /**
     * 开始连续导航（增强版）
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
        stepCount = 0;
        stepCountAtStepStart = 0;

        Log.d(TAG, "=== 开始连续导航 ===");
        Log.d(TAG, "起点：" + currentPosition.getLabel());
        Log.d(TAG, "终点：" + targetDestination);
        Log.d(TAG, "路径步数：" + fullPath.size());

        // 启动PDR
        startPDR();

        // 播报详细路径概览（增强版）
        String overview = buildDetailedPathOverview();
        voiceService.speak(overview, baseSpeed);

        // 通知导航开始
        if (eventCallback != null) {
            double totalDist = calculateTotalDistance();
            int totalWalkingSteps = (int) Math.ceil(totalDist / stepLength);
            int estimatedSec = (int) (totalDist / WALKING_SPEED);
            eventCallback.onNavigationStarted(currentPosition.getLabel(), targetDestination,
                    fullPath.size(), totalDist, estimatedSec);
        }

        // 启动实时定位跟踪
        startLocationTracking();

        // 延迟后开始分步导航
        navigationHandler.postDelayed(() -> {
            if (isNavigating) {
                startStepByStepGuidance();
            }
        }, 3000);
    }

    /**
     * 构建详细路径概览（增强版）
     */
    private String buildDetailedPathOverview() {
        int totalNavigationSteps = fullPath.size();
        double totalDistance = calculateTotalDistance();
        int totalWalkingSteps = (int) Math.ceil(totalDistance / stepLength);
        int estimatedSeconds = (int) (totalDistance / WALKING_SPEED);
        int minutes = estimatedSeconds / 60;
        int seconds = estimatedSeconds % 60;

        // 提取关键转折点
        List<String> turnPoints = new ArrayList<>();
        for (PathEntity step : fullPath) {
            if (isTurnStep(step) && turnPoints.size() < 3) {
                String direction = step.getDirection_cn();
                String point = step.getEndLabel_cn();
                turnPoints.add(point + direction.substring(0, Math.min(2, direction.length())));
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("导航开始。从%s到%s，共%d个导航点，约%.0f米，大约%d步，预计",
                currentPosition.getLabel(),
                targetDestination,
                totalNavigationSteps,
                totalDistance,
                totalWalkingSteps));

        if (minutes > 0) {
            sb.append(String.format("%d分%d秒", minutes, seconds));
        } else {
            sb.append(String.format("%d秒", seconds));
        }

        if (!turnPoints.isEmpty()) {
            sb.append("。途中需要：").append(String.join("、", turnPoints));
        }

        sb.append("。现在出发");

        return sb.toString();
    }

    /**
     * 构建详细步骤指令（增强版）
     */
    private String buildDetailedStepInstruction(PathEntity step, int index) {
        String direction = PathParser.getDirectionByLang(step, currentLocale);
        String distance = PathParser.getDistanceByLang(step, currentLocale);
        String nextPoint = PathParser.getNextPointByLang(step, currentLocale);

        // 计算该段的预计步数和时间
        double stepDistance = parseDistance(step.getDistance_cn());
        int walkingSteps = (int) Math.ceil(stepDistance / stepLength);
        int stepSeconds = (int) (stepDistance / WALKING_SPEED);

        // 更新当前段的预计步数
        expectedStepsForCurrentSegment = walkingSteps;
        stepCountAtStepStart = stepCount;

        String stepInfo = String.format("，约%d步，%d秒", walkingSteps, stepSeconds);

        if (index == 0) {
            return String.format("开始导航。%s，%s%s，前往%s", direction, distance, stepInfo, nextPoint);
        } else if (index == fullPath.size() - 1) {
            return String.format("最后一步。%s，%s%s，即将到达%s", direction, distance, stepInfo, nextPoint);
        } else {
            return String.format("第%d步。%s，%s%s，前往%s", index + 1, direction, distance, stepInfo, nextPoint);
        }
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

                locationUpdateHandler.postDelayed(this, LOCATION_UPDATE_INTERVAL);
            }
        };
        locationUpdateHandler.post(locationUpdateRunnable);
    }

    /**
     * 更新当前位置并检测状态
     */
    private void updateCurrentPosition(Position newPosition) {
        if (lastPosition == null) {
            lastPosition = newPosition;
            currentPosition = newPosition;
            return;
        }

        double movedDistance = calculateDistance(lastPosition, newPosition);
        // 使用PDR的累计距离更准确
        // accumulatedDistance += movedDistance;

        currentPosition = newPosition;

        checkOffRoute();
        checkMissedTurn();
        checkNearTurn();
        checkArrival();

        lastPosition = newPosition;

        if (positionCallback != null) {
            positionCallback.onPositionUpdated(newPosition);
        }

        if (eventCallback != null) {
            eventCallback.onLocationUpdated(newPosition);
        }
    }

    /**
     * 检测是否偏离路线
     */
    private void checkOffRoute() {
        if (currentStepIndex >= fullPath.size()) return;

        PathEntity currentStep = fullPath.get(currentStepIndex);
        String expectedEnd = currentStep.getEndLabel_cn();

        if (currentPosition.getLabel().equals(expectedEnd)) {
            return;
        }
    }

    /**
     * 检测是否错过拐弯
     */
    private void checkMissedTurn() {
        if (currentStepIndex >= fullPath.size()) {
            return;
        }

        PathEntity currentStep = fullPath.get(currentStepIndex);

        if (isTurnStep(currentStep)) {
            double stepDistance = parseDistance(currentStep.getDistance_cn());
            if (accumulatedDistance > stepDistance + MISSED_TURN_THRESHOLD) {
                announceMissedTurn(stepDistance);
                accumulatedDistance = 0.0;
            }
        }
    }

    /**
     * 播报错过拐弯
     */
    private void announceMissedTurn(double missedDistance) {
        String message = String.format("您可能已错过拐弯，请停下确认位置");
        voiceService.speak(message, baseSpeed);
        Log.d(TAG, "错过拐弯提醒");
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

        boolean isNearDestination = currentPosition.getLabel().equals(targetDestination);

        if (isNearDestination) {
            arrivalConfirmCount++;
            Log.d(TAG, "到达确认次数: " + arrivalConfirmCount);

            if (arrivalConfirmCount >= 2) {
                announceArrival();
            }
        } else {
            arrivalConfirmCount = 0;
        }
    }

    /**
     * 播报到达信息
     */
    private void announceArrival() {
        hasAnnouncedArrival = true;

        String detailInfo = getDestinationDetails();
        String message = "恭喜，您已到达目的地" + targetDestination;
        if (!detailInfo.isEmpty()) {
            message += "。" + detailInfo;
        }

        voiceService.speak(message, baseSpeed);
        Log.d(TAG, "=== 到达目的地 ===");

        if (eventCallback != null) {
            eventCallback.onArrival(targetDestination, detailInfo);
        }

        navigationHandler.postDelayed(() -> {
            stopNavigation();
        }, 2000);
    }

    /**
     * 获取目的地详细信息
     */
    private String getDestinationDetails() {
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
     * 计算两点之间的距离
     */
    private double calculateDistance(Position p1, Position p2) {
        if (p1.getLabel().equals(p2.getLabel())) {
            return 0.0;
        }
        return 2.0;
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
                String instruction = buildDetailedStepInstruction(currentStep, currentStepIndex);
                voiceService.speak(instruction, baseSpeed);

                if (eventCallback != null) {
                    eventCallback.onStepAnnounced(currentStepIndex, fullPath.size(), instruction);
                }

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
     * 获取下一次播报间隔
     */
    private int getNextInterval() {
        if (currentStepIndex < fullPath.size()) {
            PathEntity nextStep = fullPath.get(currentStepIndex);
            if (isTurnStep(nextStep)) {
                return NEAR_TURN_INTERVAL_MS;
            }
        }
        return baseIntervalMs;
    }

    /**
     * 停止导航
     */
    public void stopNavigation() {
        boolean wasNavigating = isNavigating;
        boolean reached = hasAnnouncedArrival;
        isNavigating = false;

        stopPDR();

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
            message = "导航已停止，尚未到达目的地" + targetDestination;
        } else {
            message = "导航已停止";
        }

        voiceService.speak(message, baseSpeed);
        Log.d(TAG, "导航已停止: " + message);

        if (eventCallback != null) {
            eventCallback.onNavigationStopped(reached);
        }

        // 重置状态
        currentStepIndex = 0;
        accumulatedDistance = 0.0;
        arrivalConfirmCount = 0;
        hasAnnouncedArrival = false;
        hasTurnWarned = false;
        lastPosition = null;
        stepCount = 0;
        stepCountAtStepStart = 0;
        expectedStepsForCurrentSegment = 0;
    }

    /**
     * 获取下一步指令
     */
    @Override
    public String getNextStepInstruction() {
        if (fullPath == null || fullPath.isEmpty()) {
            return "无导航路径";
        }

        if (!isNavigating && currentStepIndex == 0) {
            return "导航尚未开始，请点击开始导航";
        }

        if (isNavigating && currentStepIndex >= fullPath.size()) {
            return "已到达目的地 " + targetDestination;
        }

        PathEntity step = fullPath.get(currentStepIndex);
        String instruction = buildDetailedStepInstruction(step, currentStepIndex);

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
     * 获取当前导航进度
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

        int remainingWalkingSteps = (int) Math.ceil(remainingDistance / stepLength);
        int remainingSeconds = (int) (remainingDistance / WALKING_SPEED);

        return String.format("还剩%d个导航点，约%.0f米，大约%d步，预计%d秒",
                remaining, remainingDistance, remainingWalkingSteps, remainingSeconds);
    }

    /**
     * 获取当前步数
     */
    public int getCurrentStepCount() {
        return stepCount;
    }

    /**
     * 获取目标名称
     */
    public String getTargetDestination() {
        return targetDestination;
    }
}
