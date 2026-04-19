package com.example.indoornavblind.service.impl;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.example.indoornavblind.model.PathEntity;
import com.example.indoornavblind.model.Position;
import com.example.indoornavblind.service.LocationService;
import com.example.indoornavblind.service.NavigationService;
import com.example.indoornavblind.service.VoiceService;
import com.example.indoornavblind.util.PathParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CompassEnhancedNavigationService implements NavigationService, SensorEventListener {
    private static final String TAG = "CompassNavigation";
    private boolean useSteps = false;
    public void setUseSteps(boolean useSteps) {
        this.useSteps = useSteps;
    }

    public boolean isUseSteps() {
        return useSteps;
    }

    public interface PositionUpdateCallback {
        void onPositionUpdated(Position newPosition);
    }

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
    private static final long STEP_DEBOUNCE_MS = 300;
    private static final float STEP_SENSITIVITY = 11.5f;
    private static final float STEP_THRESHOLD_LOW = 9.0f;
    private static final float STEP_THRESHOLD_HIGH = 12.0f;

    // 移动状态
    private boolean isUserMoving = false;
    private static final long MOVEMENT_TIMEOUT = 3000;
    private int stepsInCurrentSegment = 0;
    private int expectedStepsForSegment = 0;

    // 计时器相关
    private long segmentTimerStartTime = 0;
    private long segmentPausedTime = 0;
    private boolean isTimerPaused = false;
    private long segmentExpectedDuration = 0;
    private long pauseStartTime = 0;

    // 距离阈值
    private static final double TURN_WARNING_DISTANCE = 8.0;
    private static final double OFF_ROUTE_THRESHOLD = 4.0;
    private static final double ARRIVAL_THRESHOLD = 2.0;

    // 时间间隔
    private static final int REGULAR_INTERVAL_MS = 5000;
    private static final int NEAR_TURN_INTERVAL_MS = 3000;
    private static final int LOCATION_UPDATE_INTERVAL = 3000;
    private static final int COMPASS_UPDATE_INTERVAL = 500;

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
    private Runnable locationUpdateRunnable;
    private Runnable compassUpdateRunnable;

    // 导航状态
    private double accumulatedDistance = 0.0;
    private boolean hasTurnWarned = false;
    private Position lastPosition = null;
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

    // 方向追踪（绝对方向）
    private float[] accelerometerReading = new float[3];
    private float[] magnetometerReading = new float[3];
    private float[] rotationMatrix = new float[9];
    private float[] orientationAngles = new float[3];

    private float currentAzimuth = 0;
    private String currentCardinalDirection = "北";

    // 方向平滑处理
    private static final int AZIMUTH_HISTORY_SIZE = 10;
    private ArrayList<Float> azimuthHistory = new ArrayList<>();

    // 步长配置
    private double stepLength = DEFAULT_STEP_LENGTH;

    // 步伐检测相关
    private long lastStepTime = 0;
    private float lastMagnitude = 0;
    private boolean stepPeakDetected = false;

    private Context appContext;

    public CompassEnhancedNavigationService(VoiceService voiceService, LocationService locationService) {
        this.voiceService = voiceService;
        this.locationService = locationService;
    }

    public void loadUserSettings(Context context) {
        this.appContext = context;
        SharedPreferences prefs = context.getSharedPreferences("UserSettings", Context.MODE_PRIVATE);
        String stepLengthStr = prefs.getString("stepLength", "0.65");
        try {
            this.stepLength = Double.parseDouble(stepLengthStr);
            Log.d(TAG, "用户步长设置: " + this.stepLength + "米");
        } catch (NumberFormatException e) {
            this.stepLength = DEFAULT_STEP_LENGTH;
        }
    }

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

    private void startSensors() {
        if (sensorManager == null) {
            return;
        }

        stepCount = 0;
        stepCountAtStepStart = 0;

        if (accelerometer != null && isPDREnabled) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            Log.d(TAG, "✓ 加速度计监听已启动");
        }

        if (magnetometer != null && isCompassEnabled) {
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME);
            Log.d(TAG, "✓ 磁力计监听已启动");
        }

        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
            Log.d(TAG, "✓ 陀螺仪监听已启动");
        }

        startCompassUpdate();
    }

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

    private void updateCompass() {
        if (!isCompassEnabled) {
            return;
        }

        boolean success = SensorManager.getRotationMatrix(
                rotationMatrix, null,
                accelerometerReading, magnetometerReading
        );

        if (success) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles);
            float azimuthRad = orientationAngles[0];
            float azimuthDeg = (float) Math.toDegrees(azimuthRad);

            if (azimuthDeg < 0) {
                azimuthDeg += 360;
            }

            currentAzimuth = smoothAzimuth(azimuthDeg);
            currentCardinalDirection = getCardinalDirection(currentAzimuth);
        }
    }

    private float smoothAzimuth(float newAzimuth) {
        azimuthHistory.add(newAzimuth);

        if (azimuthHistory.size() > AZIMUTH_HISTORY_SIZE) {
            azimuthHistory.remove(0);
        }

        float sum = 0;
        int count = 0;
        for (float az : azimuthHistory) {
            sum += az;
            count++;
        }

        return sum / count;
    }

    private String getCardinalDirection(float azimuth) {
        String[] directions = currentLocale.equals(Locale.CHINESE) || currentLocale.equals(Locale.TRADITIONAL_CHINESE)
                ? CARDINAL_DIRECTIONS
                : CARDINAL_DIRECTIONS_EN;

        int index = (int) ((azimuth + 22.5) / 45.0) % 8;
        return directions[index];
    }

    private String getDynamicTurnDirection(float targetBearing, Locale locale) {
        // 没有指南针时， fallback 用原来的方向
        if (!isCompassEnabled) {
            return PathParser.getDirectionByLang(fullPath.get(currentStepIndex), locale);
        }

        // 1. 计算 面朝的方向和路径要走的方向的角度差
        float angleDiff = targetBearing - currentAzimuth;
        // 归一化到 -180 ~ 180 度（标准角度计算）
        while (angleDiff > 180) angleDiff -= 360;
        while (angleDiff < -180) angleDiff += 360;

        String dir;
        // 2. 行业标准转向规则
        if (Math.abs(angleDiff) <= 25) {
            dir = "直行";
        } else if (angleDiff > 25 && angleDiff <= 135) {
            dir = "向右转";
        } else if (angleDiff < -25 && angleDiff >= -135) {
            dir = "向左转";
        } else {
            dir = "掉头";
        }

        if (locale.equals(Locale.ENGLISH)) {
            switch (dir) {
                case "直行": return "Go straight";
                case "向左转": return "Turn left";
                case "向右转": return "Turn right";
                default: return "Turn around";
            }
        } else if (locale.getLanguage().equals("yue")) {
            switch (dir) {
                case "直行": return "直行";
                case "向左转": return "轉左";
                case "向右转": return "轉右";
                default: return "掉頭";
            }
        }
        return dir;
    }

    private String calculateAbsoluteDirection(String relativeDirection) {
        if (!isCompassEnabled) {
            return relativeDirection;
        }

        float turnAngle = 0;
        if (relativeDirection.contains("左") || relativeDirection.toLowerCase().contains("left")) {
            turnAngle = -90;
        } else if (relativeDirection.contains("右") || relativeDirection.toLowerCase().contains("right")) {
            turnAngle = 90;
        } else if (relativeDirection.contains("直") || relativeDirection.toLowerCase().contains("straight")) {
            turnAngle = 0;
        } else if (relativeDirection.contains("后") || relativeDirection.toLowerCase().contains("back")) {
            turnAngle = 180;
        }

        float absoluteAzimuth = currentAzimuth + turnAngle;

        while (absoluteAzimuth < 0) absoluteAzimuth += 360;
        while (absoluteAzimuth >= 360) absoluteAzimuth -= 360;

        return getCardinalDirection(absoluteAzimuth);
    }

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

    private Handler movementHandler = new Handler(Looper.getMainLooper());
    private Runnable movementRunnable;

    private void startMovementMonitor() {
        movementRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isNavigating) return;
                checkMovementStatus();
                movementHandler.postDelayed(this, 1000);
            }
        };
        movementHandler.post(movementRunnable);
    }

    private void stopCompassUpdate() {
        if (compassUpdateRunnable != null) {
            compassUpdateHandler.removeCallbacks(compassUpdateRunnable);
        }
    }

    private void handleAccelerometer(float[] values) {
        if (!isNavigating) return;

        long currentTime = System.currentTimeMillis();

        float magnitude = (float) Math.sqrt(
                values[0] * values[0] +
                        values[1] * values[1] +
                        values[2] * values[2]
        );

        if (currentTime - lastStepTime < STEP_DEBOUNCE_MS) {
            return;
        }

        if (!stepPeakDetected && magnitude > STEP_THRESHOLD_HIGH) {
            stepPeakDetected = true;
        } else if (stepPeakDetected && magnitude < STEP_THRESHOLD_LOW) {
            onStepDetected(currentTime, magnitude);
            stepPeakDetected = false;
        }

        lastMagnitude = magnitude;
    }

    private void onStepDetected(long currentTime, float magnitude) {
        lastStepTime = currentTime;
        stepCount++;
        stepsInCurrentSegment++;

        isUserMoving = true;

        Log.d(TAG, String.format("检测到有效步伐 #%d，当前段%d步/预期%d步",
                stepCount, stepsInCurrentSegment, expectedStepsForSegment));

        showDebugToast("步伐#" + stepCount + " | 段内" + stepsInCurrentSegment + "/" + expectedStepsForSegment);

        if (expectedStepsForSegment > 0 &&
                stepsInCurrentSegment >= expectedStepsForSegment * 0.8) {
            Log.d(TAG, "步数达到阈值，触发下一步");
            navigationHandler.removeCallbacks(timerCheckRunnable);
            advanceToNextStepByTimer();
        }
    }

    private void checkMovementStatus() {
        long currentTime = System.currentTimeMillis();
        boolean wasMoving = isUserMoving;

        isUserMoving = (currentTime - lastStepTime) < MOVEMENT_TIMEOUT;

        if (wasMoving != isUserMoving) {
            Log.d(TAG, "移动状态: " + (isUserMoving ? "移动中" : "静止"));

            if (isUserMoving) {
                if (isTimerPaused) {
                    isTimerPaused = false;
                    segmentPausedTime += System.currentTimeMillis() - pauseStartTime;
                    Log.d(TAG, "计时器恢复，已累计暂停" + segmentPausedTime + "ms");
                    showDebugToast("计时器恢复 | 暂停累计" + segmentPausedTime/1000 + "秒");
                    navigationHandler.post(timerCheckRunnable);
                }
            } else {
                if (!isTimerPaused) {
                    isTimerPaused = true;
                    pauseStartTime = System.currentTimeMillis();
                    Log.d(TAG, "计时器暂停");
                    showDebugToast("检测静止 | 计时器暂停");
                    navigationHandler.removeCallbacks(timerCheckRunnable);
                }
            }
        }
    }


    // 创建定时检查Runnable
    private Runnable timerCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isNavigating || currentStepIndex >= fullPath.size()) {
                return;
            }

            if (isTimerPaused) {
                navigationHandler.postDelayed(this, 500);
                return;
            }

            long elapsed = System.currentTimeMillis() - segmentTimerStartTime - segmentPausedTime;

            if (elapsed >= segmentExpectedDuration) {
                advanceToNextStepByTimer();
            } else {
                navigationHandler.postDelayed(this, 500);
            }
        }
    };
    private void advanceToNextStepByTimer() {
        // 添加额外检查：必须检测到用户移动才进入下一步
        if (!isUserMoving && stepsInCurrentSegment < expectedStepsForSegment * 0.3) {
            Log.d(TAG, "用户未移动，暂不进入下一步");
            navigationHandler.postDelayed(timerCheckRunnable, 1000);
            return;
        }

        if (!isNavigating || currentStepIndex >= fullPath.size()) {
            return;
        }

        navigationHandler.removeCallbacks(timerCheckRunnable);
        currentStepIndex++;

        if (currentStepIndex < fullPath.size()) {
            PathEntity nextStep = fullPath.get(currentStepIndex);
            setupSegmentTimer(nextStep);
            announceCurrentStep(nextStep);
        } else {
            handleArrival();
        }
    }

    private void startStepByStepGuidance() {
        if (fullPath == null || fullPath.isEmpty() || currentStepIndex >= fullPath.size()) {
            return;
        }

        PathEntity currentStep = fullPath.get(currentStepIndex);
        announceCurrentStep(currentStep);

        setupSegmentTimer(currentStep);
        startMovementMonitor();
    }

    // 替换 setupSegmentTimer 方法
    private void setupSegmentTimer(PathEntity step) {
        double segmentDistance = step.getDistanceMeters();

        segmentExpectedDuration = (long) ((segmentDistance / WALKING_SPEED) * 1000);

        expectedStepsForSegment = (int) Math.ceil(segmentDistance / stepLength);

        stepsInCurrentSegment = 0;

        segmentTimerStartTime = System.currentTimeMillis();
        isTimerPaused = false;
        segmentPausedTime = 0;

        Log.d(TAG, String.format("段计时器：距离%.1fm，预期%d步，约%d秒",
                segmentDistance, expectedStepsForSegment, segmentExpectedDuration/1000));

        // 显示调试Toast
        showDebugToast(String.format("距离%.1fm | 步幅%.2fm | %d步 | %d秒",
                segmentDistance, stepLength, expectedStepsForSegment, segmentExpectedDuration/1000));

        navigationHandler.post(timerCheckRunnable);
    }

    private void showDebugToast(String msg) {
        if (appContext != null) {
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
            );
        }
    }

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
        fullPath = PathParser.getFullPath(currentPosition.getLabel(), targetDestination, currentPosition.getFloor());
        currentStepIndex = 0;
        accumulatedDistance = 0.0;
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

        Log.d(TAG, "=== 开始导航（支持绝对方向） ===");
        Log.d(TAG, "起点：" + currentPosition.getLabel());
        Log.d(TAG, "终点：" + targetDestination);
        Log.d(TAG, "路径步数：" + fullPath.size());
        Log.d(TAG, "指南针：" + (isCompassEnabled ? "启用" : "禁用"));

        startSensors();

        String overview = buildDetailedPathOverview();
        voiceService.speak(overview, baseSpeed);

        if (eventCallback != null) {
            double totalDist = calculateTotalDistance();
            int totalWalkingSteps = (int) Math.ceil(totalDist / stepLength);
            int estimatedSec = (int) (totalDist / WALKING_SPEED);
            eventCallback.onNavigationStarted(currentPosition.getLabel(), targetDestination,
                    fullPath.size(), totalDist, estimatedSec);
        }

        startLocationTracking();

        if (isNavigating) {
            startStepByStepGuidance();
        }
    }

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

    private double calculateTotalDistance() {
        double total = 0;
        for (PathEntity step : fullPath) {
            total += step.getDistanceMeters();
        }
        return total;
    }

    private double parseDistance(String distStr) {
        try {
            String numStr = distStr.replaceAll("[^0-9.]", "");
            return Double.parseDouble(numStr);
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean isTurnStep(PathEntity step) {
        String dir = step.getDirection_cn();
        return dir.contains("左") || dir.contains("右") ||
                dir.toLowerCase().contains("left") || dir.toLowerCase().contains("right");
    }

    private void announceCurrentStep(PathEntity step) {
        String relativeDirection = getDynamicTurnDirection(step.getBearing(), currentLocale);

        String distance = step.getDistance_cn();
        String endPoint = step.getEndLabel_cn();

        String absoluteDirection = "";
        if (isCompassEnabled) {
            absoluteDirection = "，朝" + currentCardinalDirection + "方向";
        }

        String message = String.format("%s，%s，到达%s%s",
                relativeDirection, distance, endPoint, absoluteDirection);

        voiceService.speak(message, baseSpeed);
        Log.d(TAG, String.format("[%d/%d] %s", currentStepIndex + 1, fullPath.size(), message));

        if (step.getDirection_cn() != null && step.getDirection_cn().startsWith("乘电梯")) {
            isWaitingForElevator = true;
            voiceService.speak("请乘坐电梯，到达后点击屏幕继续导航", baseSpeed);
            navigationHandler.removeCallbacks(timerCheckRunnable);
        }
    }

    private boolean isWaitingForElevator = false;
    public void confirmElevatorArrival() {
        if (isWaitingForElevator) {
            isWaitingForElevator = false;
            isUserMoving = true; // 强制设置移动状态，绕过检测
            stepsInCurrentSegment = expectedStepsForSegment; // 强制满足步数条件
            voiceService.speak("已确认，继续导航", baseSpeed);
            advanceToNextStepByTimer();
        }
    }

    public boolean isWaitingForElevator() {
        return isWaitingForElevator;
    }

    private void startLocationTracking() {
        locationUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (isNavigating && locationService != null) {
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

    private void handlePositionUpdate(Position newPos) {
        currentPosition = newPos;

        if (positionCallback != null) {
            positionCallback.onPositionUpdated(newPos);
        }

        if (eventCallback != null) {
            eventCallback.onLocationUpdated(newPos);
        }

        // 用WiFi定位校正导航步骤
        if (isNavigating && fullPath != null && !fullPath.isEmpty()) {
            for (int i = currentStepIndex; i < fullPath.size(); i++) {
                PathEntity step = fullPath.get(i);
                if (step.getEndLabel_cn().equals(newPos.getLabel())) {
                    Log.d(TAG, "WiFi校正：跳到步骤" + (i + 1));
                    currentStepIndex = i;
                    navigationHandler.removeCallbacks(timerCheckRunnable);
                    advanceToNextStepByTimer();
                    break;
                }
            }
        }

        Log.d(TAG, "位置更新: " + newPos.getLabel());
    }

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

        if (navigationHandler != null) {
            navigationHandler.removeCallbacks(timerCheckRunnable);
        }

        if (movementHandler != null && movementRunnable != null) {
            movementHandler.removeCallbacks(movementRunnable);
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

    public String getCurrentDirectionInfo() {
        if (isCompassEnabled) {
            return String.format("当前朝向: %.1f° (%s)", currentAzimuth, currentCardinalDirection);
        } else {
            return "指南针不可用";
        }
    }

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