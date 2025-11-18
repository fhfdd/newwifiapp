package com.example.indoornavblind.factory;

import android.content.Context;
import com.example.indoornavblind.service.LocationService;
import com.example.indoornavblind.service.NavigationService;
import com.example.indoornavblind.service.SpeechRecognizerService;
import com.example.indoornavblind.service.VoiceService;
import com.example.indoornavblind.service.WiFiScannerService;
import com.example.indoornavblind.service.impl.KnnLocationService;
import com.example.indoornavblind.service.impl.PathNavigationService;
import com.example.indoornavblind.service.TextToSpeechService;
import com.example.indoornavblind.service.WiFiScannerServiceImpl;

/**
 * 服务工厂 - 修复版
 * 解决所有构造函数参数问题
 */
public class ServiceFactory {
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

    /**
     * 创建语音服务 - 修复：传入Context参数
     */
    public VoiceService createVoiceService() {
        return new TextToSpeechService(context);  // ✅ 传入context
    }

    /**
     * 创建WiFi扫描服务
     */
    public WiFiScannerService createWiFiScannerService() {
        WiFiScannerService service = new WiFiScannerServiceImpl();
        service.init(context);
        return service;
    }

    /**
     * 创建定位服务
     */
    public LocationService createLocationService() {
        return new KnnLocationService(createWiFiScannerService());
    }

    /**
     * 创建导航服务
     */
    public NavigationService createNavigationService() {
        return new PathNavigationService();
    }

    /**
     * 创建语音识别服务 - 修复：使用init方法初始化Context
     */
    public SpeechRecognizerService createSpeechRecognizerService() {
        SpeechRecognizerService service = new SpeechRecognizerService();  // ✅ 使用无参构造函数
        service.init(context);  // ✅ 通过init方法传入context
        return service;
    }
}