package com.example.indoornavblind.service;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import java.util.Locale;

/**
 * TTS语音实现：实现VoiceService接口，可被替换为其他语音服务
 */
public class TextToSpeechService implements VoiceService {
    private TextToSpeech tts;
    private boolean isInitialized = false;
    private float currentSpeed = 1.0f;
    private Locale currentLocale = Locale.CHINESE;
    private Context context;

    @Override
    public void init(Context context) {
        this.context = context;
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                initLanguage();
                tts.setSpeechRate(currentSpeed);
                isInitialized = true;
            }
        });
    }

    // 抽取语言初始化逻辑（便于扩展）
    private void initLanguage() {
        int result = tts.setLanguage(currentLocale);
        // 粤语兼容处理（扩展点：可添加更多语言适配）
        if (result == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (currentLocale.getLanguage().equals("yue")) {
                currentLocale = Locale.CHINESE;
                tts.setLanguage(currentLocale);
            }
        }
    }

    @Override
    public void speak(String text, float speed) {
        if (isInitialized) {
            tts.setSpeechRate(speed);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_" + System.currentTimeMillis());
        }
    }

    @Override
    public void setLanguage(Locale locale) {
        this.currentLocale = locale;
        if (tts != null) initLanguage();
    }

    @Override
    public void setSpeed(float speed) {
        this.currentSpeed = speed;
        if (tts != null) tts.setSpeechRate(speed);
    }

    @Override
    public void stop() { if (tts != null) tts.stop(); }

    @Override
    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            isInitialized = false;
        }
    }

    @Override
    public Context getContext() { return context; }
}