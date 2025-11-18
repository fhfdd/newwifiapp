package com.example.indoornavblind.service.impl;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.example.indoornavblind.model.PathEntity;
import com.example.indoornavblind.model.Position;
import com.example.indoornavblind.service.NavigationService;
import com.example.indoornavblind.service.VoiceService;
import com.example.indoornavblind.util.PathParser;
import java.util.List;
import java.util.Locale;

public class EnhancedNavigationService implements NavigationService {
    private static final String TAG = "EnhancedNavigation";
    private VoiceService voiceService;
    private Position currentPosition;
    private String targetDestination;
    private List<PathEntity> fullPath;
    private int currentStepIndex = 0;
    private boolean isNavigating = false;
    private int stepIntervalMs = 3000;
    private float baseSpeed = 1.0f;
    private Locale currentLocale = Locale.CHINESE;
    private Handler navigationHandler = new Handler(Looper.getMainLooper());
    private Runnable stepGuidanceRunnable;

    public EnhancedNavigationService(VoiceService voiceService) {
        this.voiceService = voiceService;
    }

    @Override
    public void setCurrentPosition(Position position) {
        this.currentPosition = position;
        Log.d(TAG, "设置当前位置: " + position.getLabel());
    }

    @Override
    public void setTarget(String target) {
        this.targetDestination = target;
        Log.d(TAG, "设置目标位置: " + target);
    }

    public void setNavigationConfig(int intervalMs, float speed, Locale locale) {
        this.stepIntervalMs = intervalMs;
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
        Log.d(TAG, String.format("路径计算完成: %d步", fullPath.size()));
        return fullPath;
    }

    public void startContinuousNavigation() {
        if (fullPath == null || fullPath.isEmpty()) {
            voiceService.speak("未找到导航路径", baseSpeed);
            return;
        }
        
        isNavigating = true;
        String overview = buildPathOverview();
        voiceService.speak(overview, baseSpeed);
        navigationHandler.postDelayed(() -> startStepByStepGuidance(), 2000);
    }

    private String buildPathOverview() {
        int totalSteps = fullPath.size();
        double totalDistance = calculateTotalDistance();
        int estimatedSeconds = (int)(totalDistance / 0.8);
        return String.format("已规划路径，共%d步，约%.0f米，预计%d秒到达",
                totalSteps, totalDistance, estimatedSeconds);
    }

    private double calculateTotalDistance() {
        double total = 0;
        for (PathEntity step : fullPath) {
            String distStr = step.getDistance_cn();
            total += Double.parseDouble(distStr.replaceAll("[^0-9.]", ""));
        }
        return total;
    }

    private void startStepByStepGuidance() {
        stepGuidanceRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isNavigating || currentStepIndex >= fullPath.size()) {
                    onArriveAtDestination();
                    return;
                }
                
                PathEntity currentStep = fullPath.get(currentStepIndex);
                String instruction = buildStepInstruction(currentStep, currentStepIndex);
                voiceService.speak(instruction, baseSpeed);
                
                currentStepIndex++;
                navigationHandler.postDelayed(this, stepIntervalMs);
            }
        };
        navigationHandler.post(stepGuidanceRunnable);
    }

    private String buildStepInstruction(PathEntity step, int index) {
        String direction = PathParser.getDirectionByLang(step, currentLocale);
        String distance = PathParser.getDistanceByLang(step, currentLocale);
        String nextPoint = PathParser.getNextPointByLang(step, currentLocale);
        
        if (index == 0) {
            return String.format("第1步，%s，%s。目标：%s", direction, distance, nextPoint);
        } else if (index == fullPath.size() - 1) {
            return String.format("最后一步，%s，%s，即将到达%s", direction, distance, nextPoint);
        } else {
            return String.format("第%d步，%s，%s", index + 1, direction, distance);
        }
    }

    private void onArriveAtDestination() {
        isNavigating = false;
        voiceService.speak("恭喜，您已到达" + targetDestination, baseSpeed);
        Log.d(TAG, "导航完成");
    }

    public void stopNavigation() {
        isNavigating = false;
        navigationHandler.removeCallbacks(stepGuidanceRunnable);
        currentStepIndex = 0;
        voiceService.speak("导航已结束", baseSpeed);
    }

    @Override
    public String getNextStepInstruction() {
        if (fullPath == null || fullPath.isEmpty() || currentStepIndex >= fullPath.size()) {
            return "已到达目的地";
        }
        PathEntity step = fullPath.get(currentStepIndex);
        return buildStepInstruction(step, currentStepIndex);
    }

    public boolean isNavigating() {
        return isNavigating;
    }
}
