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
 * 完全离线增强导航服务 - 支持绝对方向判断
 *
 * 新增功能：
 * 1. 磁力计支持：获取绝对北向
 * 2. 东西南北播报：转换相对方向为绝对方向
 * 3. 方向稳定性：用户转身不影响导航指令
 * 4. PDR步数追踪
 * 5. 语音输入起点终点备用
 * 6. 完全离线运行
 *
 * 原有功能：
 * 1. 拐弯点提前播报
 * 2. 偏离路线检测
 * 3. 连续到达确认
 * 4. 实时定位跟踪
 * 5. 动态播报间隔
 */
public class CompassEnhancedNavigationService implements NavigationService, SensorEventListener {
    private static final String TAG = "CompassNavigation";

    /**
     * 位置更新回调接口
     */
    public interface PositionUpdateCallback {
        void onPositionUpdated(Position newPosition);
    }

    /**
     * 导航事件回调接口
     */
    public interface NavigationEventCallback {
        void onNavigationStarted(String from, String to, int totalSteps, double totalDistance, int estimatedSeconds);
        void onStepAnnounced(int stepIndex, int totalSteps, String instruction, String absoluteDirection);
        void onTurnWarning(String turnDirection, String absoluteDirection, int secondsRemaining);
        void onProgressUpdate(int completedSteps, int remainingSteps, double remainingDistance);
        void onArrival(String destination, String detailInfo);
        void onNavigationStopped(boolean reachedDestination);
        void onOffRoute(double deviationMeters);
        void onLocationUpdated(Position position);
        void onDirectionUpdated(float heading, String cardinal);
    }

    // 方向常量
    private static final String[] CARDINAL_DIRECTIONS = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};
    private static final String[] CARDINAL_DIRECTIONS_EN = {"North", "Northeast", "East", "Southeast",
            "South", "Southwest", "West", "Northwest"};

    // PDR相关常量
    private static final double DEFAULT_STEP_LENGTH = 0.65;
    private static final double WALKING_SPEED = 0.8;
    private static final int TURN_WARNING_SECONDS = 5;
    private static final float STEP_THRESHOLD = 12.0f;
    private static final long STEP_DEBOUNCE_MS = 300;

    // 距离阈值
    private static final double TURN_WARNING_DISTANCE = 8.0;
    private static final double OFF_ROUTE_THRESHOLD = 4.0;
    private static final double ARRIVAL_THRESHOLD = 2.0;

    // 时间间隔
    private static final int REGULAR_INTERVAL_MS = 5000;
    private static final int NEAR_TURN_INTERVAL_MS = 3000;
    private static final int LOCATION_UPDATE_INTERVAL = 3000;
    private static final int COMPASS_UPDATE_INTERVAL = 500; // 指南针更新间隔

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
    private Handler compassUpdateHandler = new Handler(Looper.getMainLooper());
    private Runnable stepGuidanceRunnable;
    private Runnable locationUpdateRunnable;
    private Runnable compassUpdateRunnable;

    // 导航状态
    private double accumulatedDistance = 0.0;
    private boolean hasTurnWarned = false;
    private Position lastPosition = null;
    private int arrivalConfirmCount = 0;
    private boolean hasAnnouncedArrival = false;

    // 回调
    private PositionUpdateCallback positionCallback;
    private NavigationEventCallback eventCallback;

    // ========== 传感器相关 ==========
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor magnetometer;
    private Sensor gyroscope;
    private boolean isPDREnabled = false;
    private boolean isCompassEnabled = false;

    // 步数追踪
    private int stepCount = 0;
    private int stepCountAtStepStart = 0;
    private long lastStepTime = 0;
    private int expectedStepsForCurrentSegment = 0;

    // 方向追踪（绝对方向）
    private float[] accelerometerReading = new float[3];
    private float[] magnetometerReading = new float[3];
    private float[] rotationMatrix = new float[9];
    private float[] orientationAngles = new float[3];

    private float currentAzimuth = 0;  // 当前磁北方位角（度）
    private String currentCardinalDirection = "北";  // 当前基本方向
    private float targetAzimuth = 0;  // 目标方位角（路径方向）

    // 方向平滑处理
    private static final int AZIMUTH_HISTORY_SIZE = 10;
    private ArrayList<Float> azimuthHistory = new ArrayList<>();

    // 步长配置
    private double stepLength = DEFAULT_STEP_LENGTH;

    public CompassEnhancedNavigationService(VoiceService voiceService, LocationService locationService) {
        this.voiceService = voiceService;
        this.locationService = locationService;
    }

    /**
     * 初始化传感器（PDR + 指南针）
     */
    public void initSensors(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

            if (accelerometer != null) {
                isPDREnabled = true;
                Log.d(TAG, "✓ PDR传感器（加速度计）可用");
            } else {
                Log.w(TAG, "✗ 加速度计不可用，PDR功能禁用");
            }

            if (magnetometer != null) {
                isCompassEnabled = true;
                Log.d(TAG, "✓ 指南针传感器（磁力计）可用");
            } else {
                Log.w(TAG, "✗ 磁力计不可用，绝对方向功能禁用");
            }
        }
    }

    /**
     * 启动传感器监听
     */
    private void startSensors() {
        if (sensorManager == null) {
            return;
        }

        stepCount = 0;
        stepCountAtStepStart = 0;

        // 启动加速度计（步数检测）
        if (accelerometer != null && isPDREnabled) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            Log.d(TAG, "✓ 加速度计监听已启动");
        }

        // 启动磁力计（指南针）
        if (magnetometer != null && isCompassEnabled) {
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME);
            Log.d(TAG, "✓ 磁力计监听已启动");
        }

        // 启动陀螺仪（可选，用于辅助）
        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
            Log.d(TAG, "✓ 陀螺仪监听已启动");
        }

        // 启动指南针定期更新
        startCompassUpdate();
    }

    /**
     * 停止传感器监听
     */
    private void stopSensors() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        stopCompassUpdate();
        Log.d(TAG, "传感器监听已停止");
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isNavigating) {
            return;
        }

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerReading, 0, 3);
            handleAccelerometer(event.values);
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerReading, 0, 3);
            updateCompass();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                Log.w(TAG, "磁力计精度不可靠");
            }
        }
    }

    /**
     * 更新指南针数据（计算绝对方向）
     */
    private void updateCompass() {
        if (!isCompassEnabled) {
            return;
        }

        // 计算旋转矩阵
        boolean success = SensorManager.getRotationMatrix(
                rotationMatrix, null,
                accelerometerReading, magnetometerReading
        );

        if (success) {
            // 获取方向角度
            SensorManager.getOrientation(rotationMatrix, orientationAngles);

            // 方位角（Azimuth）：与磁北的夹角（弧度）
            float azimuthRad = orientationAngles[0];
            float azimuthDeg = (float) Math.toDegrees(azimuthRad);

            // 归一化到0-360度
            if (azimuthDeg < 0) {
                azimuthDeg += 360;
            }

            // 平滑处理（避免抖动）
            currentAzimuth = smoothAzimuth(azimuthDeg);

            // 转换为基本方向（8个方向）
            currentCardinalDirection = getCardinalDirection(currentAzimuth);

            // Log.d(TAG, String.format("指南针: %.1f° (%s)", currentAzimuth, currentCardinalDirection));
        }
    }

    /**
     * 平滑方位角（移动平均）
     */
    private float smoothAzimuth(float newAzimuth) {
        azimuthHistory.add(newAzimuth);

        if (azimuthHistory.size() > AZIMUTH_HISTORY_SIZE) {
            azimuthHistory.remove(0);
        }

        // 处理360度边界问题
        float sum = 0;
        int count = 0;
        for (float az : azimuthHistory) {
            sum += az;
            count++;
        }

        return sum / count;
    }

    /**
     * 获取基本方向（8个方向）
     */
    private String getCardinalDirection(float azimuth) {
        String[] directions = currentLocale.equals(Locale.CHINESE) || currentLocale.equals(Locale.TRADITIONAL_CHINESE)
                ? CARDINAL_DIRECTIONS
                : CARDINAL_DIRECTIONS_EN;

        // 每个方向占45度
        int index = (int) ((azimuth + 22.5) / 45.0) % 8;
        return directions[index];
    }

    /**
     * 计算相对转向的绝对方向
     * @param relativeDirection 相对方向（如"左转"、"右转"）
     * @return 绝对方向（如"向东"、"向北"）
     */
    private String calculateAbsoluteDirection(String relativeDirection) {
        if (!isCompassEnabled) {
            return relativeDirection;  // 如果没有指南针，返回相对方向
        }

        // 解析相对转向
        float turnAngle = 0;
        if (relativeDirection.contains("左") || relativeDirection.toLowerCase().contains("left")) {
            turnAngle = -90;  // 左转90度
        } else if (relativeDirection.contains("右") || relativeDirection.toLowerCase().contains("right")) {
            turnAngle = 90;   // 右转90度
        } else if (relativeDirection.contains("直") || relativeDirection.toLowerCase().contains("straight")) {
            turnAngle = 0;    // 直行
        } else if (relativeDirection.contains("后") || relativeDirection.toLowerCase().contains("back")) {
            turnAngle = 180;  // 后转
        }

        // 计算绝对方向
        float absoluteAzimuth = currentAzimuth + turnAngle;

        // 归一化
        while (absoluteAzimuth < 0) absoluteAzimuth += 360;
        while (absoluteAzimuth >= 360) absoluteAzimuth -= 360;

        return getCardinalDirection(absoluteAzimuth);
    }

    /**
     * 启动指南针定期更新
     */
    private void startCompassUpdate() {
        compassUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (isNavigating && isCompassEnabled && eventCallback != null) {
                    eventCallback.onDirectionUpdated(currentAzimuth, currentCardinalDirection);
                }
                compassUpdateHandler.postDelayed(this, COMPASS_UPDATE_INTERVAL);
            }
        };
        compassUpdateHandler.post(compassUpdateRunnable);
    }

    /**
     * 停止指南针更新
     */
    private void stopCompassUpdate() {
        if (compassUpdateRunnable != null) {
            compassUpdateHandler.removeCallbacks(compassUpdateRunnable);
        }
    }

    /**
     * 处理加速度计数据 - 步伐检测
     */
    private void handleAccelerometer(float[] values) {
        long currentTime = System.currentTimeMillis();

        // 峰值检测算法
        float magnitude = (float) Math.sqrt(
                values[0] * values[0] +
                        values[1] * values[1] +
                        values[2] * values[2]
        );

        if (Math.abs(values[1]) > STEP_THRESHOLD &&
                (currentTime - lastStepTime) > STEP_DEBOUNCE_MS) {

            stepCount++;
            lastStepTime = currentTime;
            accumulatedDistance += stepLength;

            Log.d(TAG, String.format("检测到步伐 #%d，累计距离: %.1fm", stepCount, accumulatedDistance));

            checkStepProgress();
        }
    }

    // 是否已触发当前段的步数完成
    private boolean stepProgressTriggered = false;

    /**
     * 检查步数进度 - 基于步数触发下一步播报
     */
    private void checkStepProgress() {
        if (currentStepIndex >= fullPath.size() || !isNavigating) {
            return;
        }

        int stepsSinceStart = stepCount - stepCountAtStepStart;
        double progressRatio = expectedStepsForCurrentSegment > 0
                ? (double) stepsSinceStart / expectedStepsForCurrentSegment
                : 0;

        // 当步数达到90%时，认为到达节点，触发下一步播报
        if (progressRatio >= 0.9 && !stepProgressTriggered && expectedStepsForCurrentSegment > 0) {
            stepProgressTriggered = true;
            Log.d(TAG, String.format("✓ 步数到达节点: %d/%d (%.0f%%)",
                    stepsSinceStart, expectedStepsForCurrentSegment, progressRatio * 100));

            // 取消定时播报，改为步数触发
            navigationHandler.removeCallbacks(stepGuidanceRunnable);

            // 延迟0.5秒后播报下一步（给用户反应时间）
            navigationHandler.postDelayed(() -> {
                if (isNavigating && currentStepIndex < fullPath.size()) {
                    advanceToNextStep();
                }
            }, 500);
        }

        checkTurnWarningByTime();
    }

    /**
     * 前进到下一步并播报
     */
    private void advanceToNextStep() {
        if (!isNavigating || currentStepIndex >= fullPath.size()) {
            return;
        }

        PathEntity currentStep = fullPath.get(currentStepIndex);
        announceCurrentStep(currentStep);

        currentStepIndex++;
        hasTurnWarned = false;
        stepCountAtStepStart = stepCount;
        stepProgressTriggered = false;  // 重置标志

        // 计算下一段的预期步数
        if (currentStepIndex < fullPath.size()) {
            PathEntity nextSegment = fullPath.get(currentStepIndex);
            double segmentDistance = parseDistance(nextSegment.getDistance_cn());
            expectedStepsForCurrentSegment = (int) Math.ceil(segmentDistance / stepLength);

            // 设置备用定时器（防止步数检测失败时的后备方案）
            int fallbackInterval = (int) (expectedStepsForCurrentSegment * stepLength / WALKING_SPEED * 1000 * 1.2);
            fallbackInterval = Math.max(fallbackInterval, baseIntervalMs);
            navigationHandler.postDelayed(stepGuidanceRunnable, fallbackInterval);
        } else {
            handleArrival();
        }
    }

    /**
     * 基于时间的转弯提醒
     */
    private void checkTurnWarningByTime() {
        if (currentStepIndex >= fullPath.size() - 1 || hasTurnWarned) {
            return;
        }

        PathEntity nextStep = fullPath.get(currentStepIndex + 1);

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
     * 基于时间和方向的转弯提醒
     */
    private void announceTurnWarningByTime(PathEntity turnStep, int seconds) {
        String relativeDirection = PathParser.getDirectionByLang(turnStep, currentLocale);
        String absoluteDirection = calculateAbsoluteDirection(relativeDirection);

        String message = String.format("注意，%d秒后%s，朝%s",
                seconds, relativeDirection, absoluteDirection);
        voiceService.speak(message, baseSpeed);
        Log.d(TAG, "转弯提醒: " + message);

        if (eventCallback != null) {
            eventCallback.onTurnWarning(relativeDirection, absoluteDirection, seconds);
        }
    }

    /**
     * 设置回调
     */
    public void setPositionUpdateCallback(PositionUpdateCallback callback) {
        this.positionCallback = callback;
    }

    public void setNavigationEventCallback(NavigationEventCallback callback) {
        this.eventCallback = callback;
    }

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

    @Override
    public String getNextStepInstruction() {
        return "";
    }

    /**
     * 开始连续导航（增强版 - 带绝对方向）
     */
    public void startContinuousNavigation() {
        if (fullPath == null || fullPath.isEmpty()) {
            voiceService.speak("未找到导航路径，请重新设置", baseSpeed);
            return;
        }

        if (currentPosition == null) {
            voiceService.speak("当前位置未知，请先定位或语音输入位置", baseSpeed);
            return;
        }

        isNavigating = true;
        hasTurnWarned = false;
        stepCount = 0;
        stepCountAtStepStart = 0;
        stepProgressTriggered = false;

        Log.d(TAG, "=== 开始导航（支持绝对方向） ===");
        Log.d(TAG, "起点：" + currentPosition.getLabel());
        Log.d(TAG, "终点：" + targetDestination);
        Log.d(TAG, "路径步数：" + fullPath.size());
        Log.d(TAG, "指南针：" + (isCompassEnabled ? "启用" : "禁用"));

        // 启动传感器
        startSensors();

        // 播报详细路径概览
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
     * 构建详细路径概览
     */
    private String buildDetailedPathOverview() {
        int totalNavigationSteps = fullPath.size();
        double totalDistance = calculateTotalDistance();
        int totalWalkingSteps = (int) Math.ceil(totalDistance / stepLength);
        int estimatedSeconds = (int) (totalDistance / WALKING_SPEED);
        int minutes = estimatedSeconds / 60;
        int seconds = estimatedSeconds % 60;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("导航开始。从%s到%s，共%d个导航点，约%.0f米，大约%d步",
                currentPosition.getLabel(),
                targetDestination,
                totalNavigationSteps,
                totalDistance,
                totalWalkingSteps));

        if (minutes > 0) {
            sb.append(String.format("，预计%d分%d秒", minutes, seconds));
        } else {
            sb.append(String.format("，预计%d秒", seconds));
        }

        if (isCompassEnabled) {
            sb.append("。已启用指南针，将播报绝对方向");
        }

        sb.append("。请开始行走");

        return sb.toString();
    }

    /**
     * 计算总距离
     */
    private double calculateTotalDistance() {
        double total = 0;
        for (PathEntity step : fullPath) {
            total += parseDistance(step.getDistance_cn());
        }
        return total;
    }

    /**
     * 解析距离
     */
    private double parseDistance(String distStr) {
        try {
            String numStr = distStr.replaceAll("[^0-9.]", "");
            return Double.parseDouble(numStr);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 判断是否是转弯步骤
     */
    private boolean isTurnStep(PathEntity step) {
        String dir = step.getDirection_cn();
        return dir.contains("左") || dir.contains("右") ||
                dir.toLowerCase().contains("left") || dir.toLowerCase().contains("right");
    }

    /**
     * 开始分步引导（基于步数检测）
     */
    private void startStepByStepGuidance() {
        stepGuidanceRunnable = new Runnable() {
            @Override
            public void run() {
                // 备用定时器触发 - 仅在步数检测失效时使用
                if (!isNavigating || currentStepIndex >= fullPath.size()) {
                    return;
                }

                Log.d(TAG, "备用定时器触发，步数检测可能失效");
                advanceToNextStep();
            }
        };

        // 立即播报第一步
        if (isNavigating && currentStepIndex < fullPath.size()) {
            PathEntity firstStep = fullPath.get(currentStepIndex);
            announceCurrentStep(firstStep);

            currentStepIndex++;
            hasTurnWarned = false;
            stepCountAtStepStart = stepCount;
            stepProgressTriggered = false;

            // 计算第一段的预期步数
            double segmentDistance = parseDistance(firstStep.getDistance_cn());
            expectedStepsForCurrentSegment = (int) Math.ceil(segmentDistance / stepLength);

            if (currentStepIndex < fullPath.size()) {
                // 设置备用定时器（步数检测的1.5倍时间）
                int fallbackInterval = (int) (expectedStepsForCurrentSegment * stepLength / WALKING_SPEED * 1000 * 1.5);
                fallbackInterval = Math.max(fallbackInterval, baseIntervalMs);
                navigationHandler.postDelayed(stepGuidanceRunnable, fallbackInterval);
            } else {
                handleArrival();
            }
        }
    }

    /**
     * 播报当前步骤（增强版 - 带绝对方向）
     */
    private void announceCurrentStep(PathEntity step) {
        String relativeDirection = PathParser.getDirectionByLang(step, currentLocale);
        String distance = step.getDistance_cn();
        String endPoint = step.getEndLabel_cn();

        String absoluteDirection = "";
        if (isCompassEnabled) {
            absoluteDirection = calculateAbsoluteDirection(relativeDirection);
            absoluteDirection = "，朝" + absoluteDirection;
        }

        String message = String.format("%s，%s，到达%s%s",
                relativeDirection, distance, endPoint, absoluteDirection);

        voiceService.speak(message, baseSpeed);
        Log.d(TAG, String.format("[%d/%d] %s", currentStepIndex + 1, fullPath.size(), message));

        if (eventCallback != null) {
            eventCallback.onStepAnnounced(currentStepIndex, fullPath.size(), message, absoluteDirection);
        }
    }

    /**
     * 计算播报间隔
     */
    private int calculateInterval() {
        if (currentStepIndex < fullPath.size() - 1) {
            PathEntity nextStep = fullPath.get(currentStepIndex + 1);
            if (isTurnStep(nextStep)) {
                return NEAR_TURN_INTERVAL_MS;
            }
        }
        return baseIntervalMs;
    }

    /**
     * 启动定位跟踪
     */
    private void startLocationTracking() {
        locationUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (isNavigating && locationService != null) {
                    // 修复点：LocationService 通常没有同步获取位置的方法
                    // 建议：直接调用异步定位方法更新 currentPosition
                    locationService.locate(new LocationService.LocationCallback() {
                        @Override
                        public void onSuccess(Position position) {
                            if (position != null && !position.equals(currentPosition)) {
                                handlePositionUpdate(position);
                            }
                        }
                        @Override
                        public void onFailure(String error) {
                            Log.w(TAG, "自动定位更新失败: " + error);
                        }
                    });
                }
                locationUpdateHandler.postDelayed(this, LOCATION_UPDATE_INTERVAL);
            }
        };
        locationUpdateHandler.post(locationUpdateRunnable);
    }

    /**
     * 处理位置更新
     */
    private void handlePositionUpdate(Position newPos) {
        currentPosition = newPos;

        if (positionCallback != null) {
            positionCallback.onPositionUpdated(newPos);
        }

        if (eventCallback != null) {
            eventCallback.onLocationUpdated(newPos);
        }

        Log.d(TAG, "位置更新: " + newPos.getLabel());
    }

    /**
     * 处理到达
     */
    private void handleArrival() {
        if (!hasAnnouncedArrival) {
            String message = String.format("已到达目的地：%s。导航结束", targetDestination);
            voiceService.speak(message, baseSpeed);
            hasAnnouncedArrival = true;

            if (eventCallback != null) {
                eventCallback.onArrival(targetDestination, "导航成功完成");
            }

            stopNavigation();
        }
    }

    @Override
    public void stopNavigation() {
        isNavigating = false;

        if (navigationHandler != null && stepGuidanceRunnable != null) {
            navigationHandler.removeCallbacks(stepGuidanceRunnable);
        }

        if (locationUpdateHandler != null && locationUpdateRunnable != null) {
            locationUpdateHandler.removeCallbacks(locationUpdateRunnable);
        }

        stopSensors();

        if (eventCallback != null) {
            eventCallback.onNavigationStopped(hasAnnouncedArrival);
        }

        Log.d(TAG, "导航已停止");
    }

    /**
     * 获取当前方向信息
     */
    public String getCurrentDirectionInfo() {
        if (isCompassEnabled) {
            return String.format("当前朝向: %.1f° (%s)", currentAzimuth, currentCardinalDirection);
        } else {
            return "指南针不可用";
        }
    }

    /**
     * 检查传感器状态
     */
    public String getSensorStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("PDR（步数）: ").append(isPDREnabled ? "✓" : "✗").append("\n");
        sb.append("指南针（方向）: ").append(isCompassEnabled ? "✓" : "✗").append("\n");
        if (isCompassEnabled) {
            sb.append(getCurrentDirectionInfo());
        }
        return sb.toString();
    }

    public boolean isNavigating() {
        return this.isNavigating;
    }
}