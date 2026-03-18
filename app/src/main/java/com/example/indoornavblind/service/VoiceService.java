package com.example.indoornavblind.service;

import android.content.Context;

import java.util.Locale;

/**
 * 语音服务接口：定义语音功能标准，支持替换不同实现（如TTS、第三方语音）
 */
public interface VoiceService {
    void init(Context context);
    void speak(String text, float speed);
    void stop();
    void shutdown();
    Context getContext();
    // 新增：设置语言（扩展接口，不修改原有方法）
    void setLanguage(Locale locale);
    void setSpeed(float speed);
}
