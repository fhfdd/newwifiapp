package com.example.indoornavblind.factory;

import android.content.Context;
import com.example.indoornavblind.service.LocationService;
import com.example.indoornavblind.service.NavigationService;
import com.example.indoornavblind.service.SpeechRecognizerService; // 新增导入
import com.example.indoornavblind.service.VoiceService;
import com.example.indoornavblind.service.WiFiScannerService;
import com.example.indoornavblind.service.impl.KnnLocationService;
import com.example.indoornavblind.service.impl.PathNavigationService;
import com.example.indoornavblind.service.TextToSpeechService;
import com.example.indoornavblind.service.WiFiScannerServiceImpl;

public class ServiceFactory {
    // 单例模式（已有代码不变）
    private static ServiceFactory instance;
    private Context context;

    private ServiceFactory(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized ServiceFactory getInstance(Context context) {
        if (instance == null) {
            instance = new ServiceFactory(context);
        }
        return instance;
    }

    // 已有方法不变（语音、WiFi、定位、导航）
    public VoiceService createVoiceService() {
        VoiceService service = new TextToSpeechService();
        service.init(context);
        return service;
    }

    public WiFiScannerService createWiFiScannerService() {
        WiFiScannerService service = new WiFiScannerServiceImpl();
        service.init(context);
        return service;
    }

    public LocationService createLocationService() {
        return new KnnLocationService(createWiFiScannerService());
    }

    public NavigationService createNavigationService() {
        return new PathNavigationService();
    }

    // 新增：创建语音识别服务的方法（解决第一个错误）
    public SpeechRecognizerService createSpeechRecognizerService() {
        SpeechRecognizerService service = new SpeechRecognizerService();
        service.init(context); // 初始化服务（需在SpeechRecognizerService中实现init方法）
        return service;
    }
}