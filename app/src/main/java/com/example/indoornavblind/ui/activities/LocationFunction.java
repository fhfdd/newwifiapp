package com.example.indoornavblind.ui.activities;

import android.content.Context;
import com.example.indoornavblind.model.Position;
import com.example.indoornavblind.service.LocationService;
import com.example.indoornavblind.service.VoiceService;

/**
 * 定位功能：实现MainFunction接口，依赖定位服务和语音服务
 * 扩展开放：如需新增定位逻辑，只需实现新的MainFunction
 */
public class LocationFunction implements MainFunction {
    private final LocationService locationService;
    private final VoiceService voiceService;
    private final LocationCallback callback;

    // 依赖注入（通过构造器传入接口，而非具体实现）
    public LocationFunction(LocationService locationService,
                            VoiceService voiceService,
                            LocationCallback callback) {
        this.locationService = locationService;
        this.voiceService = voiceService;
        this.callback = callback;
    }

    @Override
    public void execute(Context context) {
        voiceService.speak("开始定位...", 1.0f);
        locationService.locate(new LocationService.LocationCallback() {
            @Override
            public void onSuccess(Position position) {
                String msg = "你当前在" + position.getLabel();
                voiceService.speak(msg, 1.0f);
                callback.onLocationUpdated(position);
            }

            @Override
            public void onFailure(String error) {
                voiceService.speak(error, 1.0f);
                callback.onLocationUpdated(null);
            }
        });
    }

    @Override
    public String getFunctionName() {
        return "定位功能";
    }

    @Override
    public boolean isExecutable(Context context) {
        // 前置条件检查：定位服务是否初始化
        return locationService != null;
    }

    // 回调接口（扩展点）
    public interface LocationCallback {
        void onLocationUpdated(Position position);
    }
}