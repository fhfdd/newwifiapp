package com.example.indoornavblind.factory;

import android.content.Context;
import android.util.Log;

import com.example.indoornavblind.service.LocationService;
import com.example.indoornavblind.service.NavigationService;
import com.example.indoornavblind.service.VoskSpeechRecognizerService; // 替换为Vosk
import com.example.indoornavblind.service.VoiceService;
import com.example.indoornavblind.service.WiFiScannerService;
import com.example.indoornavblind.service.impl.L_KnnLocationService;
import com.example.indoornavblind.service.impl.L_PathNavigationService;
import com.example.indoornavblind.service.C_TextToSpeechService;
import com.example.indoornavblind.service.L_WiFiScannerServiceImpl;
import com.example.indoornavblind.service.impl.CompassEnhancedNavigationService; // 你实际使用的导航服务

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 服务工厂 - Vosk+TTS版本
 * 支持：离线语音识别(Vosk) + 增强版TTS播报
 */
public class ServiceFactory {
    private static final String TAG = "ServiceFactory";
    private static ServiceFactory instance;
    private Context context;

    // 服务实例缓存
    private C_TextToSpeechService ttsService;
    private VoskSpeechRecognizerService voskService;
    private LocationService locationService;
    private NavigationService navigationService;
    private WiFiScannerService wifiScannerService;

    // 初始化状态
    private boolean isVoskModelPrepared = false;

    private ServiceFactory(Context context) {
        this.context = context.getApplicationContext();
        prepareVoskModels(); // 提前准备模型
    }

    public static synchronized ServiceFactory getInstance(Context context) {
        if (instance == null) {
            instance = new ServiceFactory(context);
        }
        return instance;
    }

    /**
     * 准备Vosk模型文件（关键步骤）
     */
    private void prepareVoskModels() {
        new Thread(() -> {
            Log.d(TAG, "开始准备Vosk模型文件...");

            // 模型文件列表
            String[] modelAssets = {
                    "vosk-model-small-cn-0.22.zip",
                    "vosk-model-small-en-us-0.15.zip"
            };

            boolean allSuccess = true;

            for (String modelAsset : modelAssets) {
                // 检查文件是否存在
                if (!assetExists(modelAsset)) {
                    Log.e(TAG, "❌ Assets中缺少模型文件: " + modelAsset);
                    allSuccess = false;
                    continue;
                }

                // 确定目标目录
                String langCode = modelAsset.contains("cn") ? "zh-CN" : "en-US";
                File targetDir = new File(context.getFilesDir(), "model_" + langCode);

                // 如果目录已存在且非空，跳过解压
                if (targetDir.exists() && targetDir.isDirectory() &&
                        targetDir.list() != null && targetDir.list().length > 10) {
                    Log.d(TAG, "✅ 模型已存在: " + langCode);
                    continue;
                }

                // 解压模型
                Log.d(TAG, "解压模型: " + modelAsset);
                if (!unpackModel(modelAsset, targetDir)) {
                    Log.e(TAG, "❌ 模型解压失败: " + modelAsset);
                    allSuccess = false;
                }
            }

            isVoskModelPrepared = allSuccess;
            if (isVoskModelPrepared) {
                Log.d(TAG, "✅ Vosk模型准备完成");
            } else {
                Log.e(TAG, "❌ Vosk模型准备失败，语音识别功能将受限");
            }
        }).start();
    }

    /**
     * 检查Assets文件是否存在
     */
    private boolean assetExists(String filename) {
        try {
            InputStream is = context.getAssets().open(filename);
            is.close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 解压模型文件
     */
    private boolean unpackModel(String assetName, File targetDir) {
        try {
            if (targetDir.exists()) {
                deleteDirectory(targetDir);
            }
            targetDir.mkdirs();

            InputStream is = context.getAssets().open(assetName);
            ZipInputStream zis = new ZipInputStream(is);
            ZipEntry entry;

            while ((entry = zis.getNextEntry()) != null) {
                File file = new File(targetDir, entry.getName());

                if (entry.isDirectory()) {
                    file.mkdirs();
                } else {
                    File parent = file.getParentFile();
                    if (!parent.exists()) parent.mkdirs();

                    FileOutputStream fos = new FileOutputStream(file);
                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                    fos.close();
                }
                zis.closeEntry();
            }
            zis.close();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "解压失败: " + assetName, e);
            return false;
        }
    }

    private void deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) deleteDirectory(child);
            }
        }
        dir.delete();
    }

    /**
     * 创建语音服务 - 返回增强版TTS
     */
    public VoiceService createVoiceService() {
        if (ttsService == null) {
            ttsService = new C_TextToSpeechService(context);
        }
        return ttsService;
    }

    /**
     * 获取TTS服务（具体类型）
     */
    public C_TextToSpeechService getTtsService() {
        if (ttsService == null) {
            ttsService = new C_TextToSpeechService(context);
        }
        return ttsService;
    }

    /**
     * 创建Vosk离线语音识别服务
     */
    public VoskSpeechRecognizerService createVoskSpeechRecognizerService() {
        if (voskService == null) {
            voskService = new VoskSpeechRecognizerService();
            voskService.init(context, null);
        }
        return voskService;
    }

    /**
     * 获取Vosk服务实例
     */
    public VoskSpeechRecognizerService getVoskService() {
        if (voskService == null) {
            voskService = createVoskSpeechRecognizerService();
        }
        return voskService;
    }

    /**
     * 创建WiFi扫描服务
     */
    public WiFiScannerService createWiFiScannerService() {
        if (wifiScannerService == null) {
            wifiScannerService = new L_WiFiScannerServiceImpl();
            wifiScannerService.init(context);
        }
        return wifiScannerService;
    }

    /**
     * 创建定位服务
     */
    public LocationService createLocationService() {
        if (locationService == null) {
            locationService = new L_KnnLocationService(createWiFiScannerService());
            locationService.init(context);
        }
        return locationService;
    }

    /**
     * 创建导航服务 - 注意：根据你的MainActivity，这里应该返回CompassEnhancedNavigationService
     */
    public NavigationService createNavigationService() {
        if (navigationService == null) {
            // 使用你MainActivity中实际使用的CompassEnhancedNavigationService
            navigationService = new CompassEnhancedNavigationService(
                    getTtsService(), // 传入TTS服务
                    createLocationService() // 传入定位服务
            );
        }
        return navigationService;
    }

    /**
     * 创建旧版语音识别服务（兼容性保留，但建议使用Vosk）
     */
    public com.example.indoornavblind.service.C_SpeechRecognizerService createSpeechRecognizerService() {
        com.example.indoornavblind.service.C_SpeechRecognizerService service =
                new com.example.indoornavblind.service.C_SpeechRecognizerService();
        service.init(context);
        return service;
    }

    /**
     * 检查Vosk模型是否准备就绪
     */
    public boolean isVoskReady() {
        return isVoskModelPrepared && voskService != null && voskService.isInitialized();
    }

    /**
     * 检查TTS是否就绪
     */
    public boolean isTtsReady() {
        return ttsService != null && ttsService.isReady();
    }

    /**
     * 切换系统语言（同时切换Vosk和TTS）
     */
    public void switchLanguage(VoskSpeechRecognizerService.Language language) {
        // 切换Vosk识别语言
        if (voskService != null) {
            voskService.switchLanguage(language);
        }

        // 切换TTS播报语言
        if (ttsService != null && ttsService.isReady()) {
            ttsService.setLanguage(language.locale);
        }

        Log.d(TAG, "系统语言切换至: " + language.displayName);
    }

    /**
     * 销毁资源
     */
    public void shutdown() {
        if (ttsService != null) {
            ttsService.shutdown();
        }
        if (voskService != null) {
            voskService.destroy();
        }
        instance = null;
        Log.d(TAG, "服务工厂已关闭");
    }
}