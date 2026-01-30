package com.example.indoornavblind.service;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;
import com.example.indoornavblind.model.Position;

/**
 * 增强版行人航位推算服务
 * 功能：
 * 1. 步数计数（使用加速度计）
 * 2. 方向追踪（使用陀螺仪和磁力计）
 * 3. 移动检测
 * 4. 东西南北方向判断
 */
public class EnhancedPDService implements PDService, SensorEventListener {
    private static final String TAG = "EnhancedPDService";
    
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor gyroscope;
    private Sensor magnetometer;
    private Sensor stepDetector;
    
    private Position lastPosition;
    
    // 步行参数
    private float stepLength = 0.65f; // 成年人平均步长（米）
    private int totalSteps = 0; // 总步数
    private int sessionSteps = 0; // 本次导航的步数
    
    // 方向参数
    private float currentDirection = 0; // 当前方向（0-360度，0为北）
    private float[] gravity = new float[3];
    private float[] geomagnetic = new float[3];
    private float[] rotationMatrix = new float[9];
    private float[] orientation = new float[3];
    
    // 移动检测参数
    private boolean isMoving = false;
    private long lastStepTime = 0;
    private static final long STATIONARY_THRESHOLD = 3000; // 3秒未检测到步伐则认为静止
    
    // 回调监听器
    private MovementListener movementListener;
    private DirectionListener directionListener;
    
    @Override
    public void init(Context context, Position initialPosition) {
        this.lastPosition = initialPosition;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        
        // 初始化传感器
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        
        // 注册传感器监听器
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        }
        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL);
        }
        if (magnetometer != null) {
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_NORMAL);
        }
        if (stepDetector != null) {
            sensorManager.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_NORMAL);
        }
        
        sessionSteps = 0;
        Log.d(TAG, "EnhancedPDService initialized");
    }

    @Override
    public Position updatePosition() {
        if (lastPosition == null) {
            return null;
        }
        
        // 根据步数和方向更新位置
        double rad = Math.toRadians(currentDirection);
        double dx = sessionSteps * stepLength * Math.sin(rad);
        double dy = sessionSteps * stepLength * Math.cos(rad);
        
        lastPosition.setPixelX(lastPosition.getPixelX() + dx);
        lastPosition.setPixelY(lastPosition.getPixelY() + dy);
        
        return lastPosition;
    }

    @Override
    public void stop() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        Log.d(TAG, "EnhancedPDService stopped");
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                handleAccelerometer(event);
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                handleMagnetometer(event);
                break;
            case Sensor.TYPE_GYROSCOPE:
                handleGyroscope(event);
                break;
            case Sensor.TYPE_STEP_DETECTOR:
                handleStepDetector(event);
                break;
        }
        
        // 检查移动状态
        checkMovementStatus();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 可以在这里处理传感器精度变化
    }

    /**
     * 处理加速度计数据
     */
    private void handleAccelerometer(SensorEvent event) {
        System.arraycopy(event.values, 0, gravity, 0, 3);
        updateOrientation();
    }

    /**
     * 处理磁力计数据
     */
    private void handleMagnetometer(SensorEvent event) {
        System.arraycopy(event.values, 0, geomagnetic, 0, 3);
        updateOrientation();
    }

    /**
     * 处理陀螺仪数据（用于精细方向调整）
     */
    private void handleGyroscope(SensorEvent event) {
        // 陀螺仪的Z轴旋转速率
        float rotationRate = event.values[2];
        currentDirection += rotationRate * 0.1f;
        
        // 确保方向在0-360度范围内
        currentDirection = normalizeDirection(currentDirection);
        
        if (directionListener != null) {
            directionListener.onDirectionChanged(currentDirection, getDirectionDescription(currentDirection));
        }
    }

    /**
     * 处理步数检测器（最准确的步数检测）
     */
    private void handleStepDetector(SensorEvent event) {
        onStepDetected();
    }

    /**
     * 步数检测回调
     */
    private void onStepDetected() {
        totalSteps++;
        sessionSteps++;
        lastStepTime = System.currentTimeMillis();
        isMoving = true;
        
        if (movementListener != null) {
            movementListener.onStepDetected(sessionSteps, totalSteps);
        }
        
        Log.d(TAG, "Step detected: session=" + sessionSteps + ", total=" + totalSteps);
    }

    /**
     * 更新方向（基于磁力计和加速度计）
     */
    private void updateOrientation() {
        if (gravity == null || geomagnetic == null) {
            return;
        }
        
        boolean success = SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic);
        if (success) {
            SensorManager.getOrientation(rotationMatrix, orientation);
            
            // orientation[0] 是方位角（azimuth），范围 -π 到 π
            float azimuth = (float) Math.toDegrees(orientation[0]);
            currentDirection = normalizeDirection(azimuth);
            
            if (directionListener != null) {
                directionListener.onDirectionChanged(currentDirection, getDirectionDescription(currentDirection));
            }
        }
    }

    /**
     * 检查移动状态
     */
    private void checkMovementStatus() {
        long currentTime = System.currentTimeMillis();
        boolean wasMoving = isMoving;
        isMoving = (currentTime - lastStepTime) < STATIONARY_THRESHOLD;
        
        if (movementListener != null && wasMoving != isMoving) {
            movementListener.onMovementStatusChanged(isMoving);
        }
    }

    /**
     * 将方向归一化到0-360度范围
     */
    private float normalizeDirection(float direction) {
        while (direction < 0) {
            direction += 360;
        }
        while (direction >= 360) {
            direction -= 360;
        }
        return direction;
    }

    /**
     * 获取方向描述（东西南北）
     */
    public String getDirectionDescription(float direction) {
        if (direction >= 337.5 || direction < 22.5) {
            return "北";
        } else if (direction >= 22.5 && direction < 67.5) {
            return "东北";
        } else if (direction >= 67.5 && direction < 112.5) {
            return "东";
        } else if (direction >= 112.5 && direction < 157.5) {
            return "东南";
        } else if (direction >= 157.5 && direction < 202.5) {
            return "南";
        } else if (direction >= 202.5 && direction < 247.5) {
            return "西南";
        } else if (direction >= 247.5 && direction < 292.5) {
            return "西";
        } else {
            return "西北";
        }
    }

    /**
     * 获取英文方向描述
     */
    public String getDirectionDescriptionEn(float direction) {
        if (direction >= 337.5 || direction < 22.5) {
            return "North";
        } else if (direction >= 22.5 && direction < 67.5) {
            return "Northeast";
        } else if (direction >= 67.5 && direction < 112.5) {
            return "East";
        } else if (direction >= 112.5 && direction < 157.5) {
            return "Southeast";
        } else if (direction >= 157.5 && direction < 202.5) {
            return "South";
        } else if (direction >= 202.5 && direction < 247.5) {
            return "Southwest";
        } else if (direction >= 247.5 && direction < 292.5) {
            return "West";
        } else {
            return "Northwest";
        }
    }

    // Getters
    public int getTotalSteps() {
        return totalSteps;
    }

    public int getSessionSteps() {
        return sessionSteps;
    }

    public float getCurrentDirection() {
        return currentDirection;
    }

    public boolean isMoving() {
        return isMoving;
    }

    public float getStepLength() {
        return stepLength;
    }

    public void setStepLength(float stepLength) {
        this.stepLength = stepLength;
    }

    public void resetSessionSteps() {
        this.sessionSteps = 0;
    }

    // 设置监听器
    public void setMovementListener(MovementListener listener) {
        this.movementListener = listener;
    }

    public void setDirectionListener(DirectionListener listener) {
        this.directionListener = listener;
    }

    // ✅ 修复#4: 为 EnhancedPDService 添加以下方法
//
// 说明：这些方法应该添加到 EnhancedPDService 类中
// 位置：在现有的方法之后添加
//
// 用途：获取方向描述，用于语音播报方向信息

    /**
     * 获取方向的详细描述（用于播报）
     *
     * 示例：
     * getDirectionInstructionCn(45) 返回 "东北方向"
     * getDirectionInstructionCn(0) 返回 "北方向" / "正北"
     *
     * @param direction 方向角度
     * @return 用于语音播报的方向指令
     */
    public String getDirectionInstructionCn(float direction) {
        String desc = getDirectionDescription(direction);

        // 对于正方向（北、东、南、西）使用更自然的表述
        if (direction >= 337.5 || direction < 22.5) {
            return "正北";
        } else if (direction >= 157.5 && direction < 202.5) {
            return "正南";
        } else if (direction >= 67.5 && direction < 112.5) {
            return "正东";
        } else if (direction >= 247.5 && direction < 292.5) {
            return "正西";
        } else {
            return desc + "方向";
        }
    }

    /**
     * 计算两个方向之间的最小夹角
     *
     * @param angle1 第一个方向角度
     * @param angle2 第二个方向角度
     * @return 两个方向之间的最小角度差 (0-180度)
     */
    public float calculateDirectionDifference(float angle1, float angle2) {
        // 归一化两个角度
        angle1 = angle1 % 360;
        angle2 = angle2 % 360;

        if (angle1 < 0) angle1 += 360;
        if (angle2 < 0) angle2 += 360;

        // 计算最小夹角
        float diff = Math.abs(angle1 - angle2);
        if (diff > 180) {
            diff = 360 - diff;
        }

        return diff;
    }

    /**
     * 判断用户是否朝向目标方向
     *
     * @param currentDirection 用户当前朝向 (0-360度)
     * @param targetDirection 目标方向 (0-360度)
     * @param tolerance 允许的角度容差 (默认30度)
     * @return true 如果用户朝向目标方向
     */
    public boolean isFacingDirection(float currentDirection, float targetDirection, float tolerance) {
        float diff = calculateDirectionDifference(currentDirection, targetDirection);
        return diff <= tolerance;
    }

    /**
     * 判断用户是否朝向目标方向（使用默认容差）
     */
    public boolean isFacingDirection(float currentDirection, float targetDirection) {
        return isFacingDirection(currentDirection, targetDirection, 30f);
    }

    /**
     * 获取用户需要转向的方向建议
     *
     * 返回值示例：
     * "向右转" (从北转向东)
     * "向左转" (从北转向西)
     * "继续前进" (方向正确)
     * "掉头" (需要转身180度)
     *
     * @param currentDirection 用户当前朝向
     * @param targetDirection 目标方向
     * @return 转向建议
     */
    public String getTurnInstruction(float currentDirection, float targetDirection) {
        float diff = calculateDirectionDifference(currentDirection, targetDirection);

        // 方向已正确
        if (diff < 30) {
            return "继续前进";
        }

        // 计算需要转向的方向（左还是右）
        currentDirection = currentDirection % 360;
        if (currentDirection < 0) currentDirection += 360;
        targetDirection = targetDirection % 360;
        if (targetDirection < 0) targetDirection += 360;

        float clockwiseDiff = (targetDirection - currentDirection) % 360;
        if (clockwiseDiff < 0) clockwiseDiff += 360;

        // 判断是顺时针还是逆时针转向
        boolean turnRight = clockwiseDiff < 180;

        // 根据角度差和转向方向返回建议
        if (diff < 90) {
            return turnRight ? "轻微向右转" : "轻微向左转";
        } else if (diff < 150) {
            return turnRight ? "向右转" : "向左转";
        } else if (diff < 180) {
            return turnRight ? "大幅向右转" : "大幅向左转";
        } else {
            return "掉头";
        }
    }

    /**
     * 获取方向相对于用户当前方向的相对描述
     *
     * 示例：
     * 用户面朝北，目标在东方向 → "右前方"
     * 用户面朝北，目标在西方向 → "左前方"
     * 用户面朝北，目标在南方向 → "后方"
     *
     * @param currentDirection 用户当前朝向
     * @param targetDirection 目标方向
     * @return 相对方向描述
     */
    public String getRelativeDirectionDescription(float currentDirection, float targetDirection) {
        float diff = calculateDirectionDifference(currentDirection, targetDirection);

        if (diff < 30) {
            return "正前方";
        } else if (diff < 90) {
            currentDirection = currentDirection % 360;
            if (currentDirection < 0) currentDirection += 360;
            targetDirection = targetDirection % 360;
            if (targetDirection < 0) targetDirection += 360;

            float clockwiseDiff = (targetDirection - currentDirection) % 360;
            if (clockwiseDiff < 0) clockwiseDiff += 360;

            if (clockwiseDiff < 180) {
                return "右前方";
            } else {
                return "左前方";
            }
        } else if (diff < 150) {
            currentDirection = currentDirection % 360;
            if (currentDirection < 0) currentDirection += 360;
            targetDirection = targetDirection % 360;
            if (targetDirection < 0) targetDirection += 360;

            float clockwiseDiff = (targetDirection - currentDirection) % 360;
            if (clockwiseDiff < 0) clockwiseDiff += 360;

            if (clockwiseDiff < 180) {
                return "右方";
            } else {
                return "左方";
            }
        } else {
            return "后方";
        }
    }

// ============================================================
// 使用示例
// ============================================================
/*

// 示例1：获取方向描述
float direction = 45.0f;  // 东北方向
String desc = pdService.getDirectionDescription(direction);
// 返回: "东北"

// 示例2：获取转向指令
float currentDir = 0.0f;   // 当前面朝北
float targetDir = 90.0f;   // 目标方向东
String instruction = pdService.getTurnInstruction(currentDir, targetDir);
// 返回: "向右转"

// 示例3：检查用户是否朝向目标方向
if (pdService.isFacingDirection(currentDirection, targetDirection)) {
    // 用户已朝向目标方向，可以前进
    speak("请继续前进");
}

// 示例4：获取相对方向描述
String relative = pdService.getRelativeDirectionDescription(currentDir, targetDir);
// 用户面朝北，目标在东 → "右前方"

// 示例5：在导航逻辑中使用
if (pdService.isFacingDirection(currentDirection, node.getDirection(), 30)) {
    // 用户已正确对齐方向，可以播报导航指令
    announceNavigation(node.getInstruction());
} else {
    // 用户需要调整方向
    String turnInstruction = pdService.getTurnInstruction(currentDirection, node.getDirection());
    announceNavigation(turnInstruction);
}

*/


    /**
     * 移动监听器接口
     */
    public interface MovementListener {
        void onStepDetected(int sessionSteps, int totalSteps);
        void onMovementStatusChanged(boolean isMoving);
    }

    /**
     * 方向监听器接口
     */
    public interface DirectionListener {
        void onDirectionChanged(float direction, String directionDesc);
    }
}
