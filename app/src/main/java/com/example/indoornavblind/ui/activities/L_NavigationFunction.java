package com.example.indoornavblind.ui.activities;

import android.content.Context;
import com.example.indoornavblind.model.PathEntity;
import com.example.indoornavblind.service.NavigationService;
import com.example.indoornavblind.service.VoiceService;
import java.util.List;

/**
 * 导航功能：实现MainFunction接口，依赖导航服务和语音服务
 */
public class L_NavigationFunction implements MainFunction {
    private final NavigationService navigationService;
    private final VoiceService voiceService;
    private final NavCallback callback;

    public L_NavigationFunction(NavigationService navigationService,
                                VoiceService voiceService,
                                NavCallback callback) {
        this.navigationService = navigationService;
        this.voiceService = voiceService;
        this.callback = callback;
    }

    @Override
    public void execute(Context context) {
        List<PathEntity> path = navigationService.calculatePath();
        if (path.isEmpty()) {
            voiceService.speak("未找到路径", 1.0f);
            callback.onNavUpdated("导航失败");
            return;
        }
        voiceService.speak("导航开始，共" + path.size() + "步", 1.0f);
        callback.onNavUpdated(navigationService.getNextStepInstruction());
    }

    @Override
    public String getFunctionName() {
        return "导航功能";
    }

    @Override
    public boolean isExecutable(Context context) {
        // 前置条件：已设置起点和终点
        return navigationService != null;
    }

    public interface NavCallback {
        void onNavUpdated(String instruction);
    }
}