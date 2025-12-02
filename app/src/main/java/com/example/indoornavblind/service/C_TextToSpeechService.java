package com.example.indoornavblind.service;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import java.util.Locale;

/**
 * 文字转语音服务实现 - 修复版
 */
public class C_TextToSpeechService implements VoiceService {
    private static final String TAG = "TextToSpeechService";
    private TextToSpeech tts;
    private Context context;
    private boolean isInitialized = false;
    private float currentSpeed = 1.0f;
    private Locale currentLocale = Locale.CHINESE;

    public C_TextToSpeechService(Context context) {
        this.context = context;
        initTTS();
    }

    @Override
    public void init(Context context) {
        this.context = context;
        if (tts != null) {
            tts.shutdown();
        }
        initTTS();
    }

    private void initTTS() {
        if (tts != null) {
            tts.shutdown();
        }

        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(currentLocale);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "TTS语言不支持: " + currentLocale.getDisplayLanguage());
                    // 回退到系统默认语言
                    tts.setLanguage(Locale.getDefault());
                }
                tts.setSpeechRate(currentSpeed);
                isInitialized = true;
                Log.d(TAG, "TTS初始化成功");
            } else {
                Log.e(TAG, "TTS初始化失败");
                isInitialized = false;
            }
        });

        // 设置进度监听器
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                Log.d(TAG, "开始播报: " + utteranceId);
            }

            @Override
            public void onDone(String utteranceId) {
                Log.d(TAG, "播报完成: " + utteranceId);
            }

            @Override
            public void onError(String utteranceId) {
                Log.e(TAG, "播报错误: " + utteranceId);
                // 尝试重新初始化
                reinitTTS();
            }
        });
    }

    /**
     * 重新初始化TTS（当发生错误时）
     */
    private void reinitTTS() {
        Log.w(TAG, "TTS发生错误，尝试重新初始化...");
        isInitialized = false;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            initTTS();
        }, 500);
    }

    @Override
    public void speak(String text, float speed) {
        if (!isInitialized) {
            Log.w(TAG, "TTS未初始化，尝试重新初始化...");
            initTTS();
            // 延迟播报
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                speakInternal(text, speed);
            }, 1000);
            return;
        }

        speakInternal(text, speed);
    }

    private void speakInternal(String text, float speed) {
        try {
            if (tts == null) {
                Log.e(TAG, "TTS对象为null");
                return;
            }

            // 检查TTS是否正在播放，如果是则停止
            if (tts.isSpeaking()) {
                tts.stop();
            }

            tts.setSpeechRate(speed);

            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, String.valueOf(System.currentTimeMillis()));

            int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, String.valueOf(System.currentTimeMillis()));

            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "TTS播报失败，尝试重新初始化");
                reinitTTS();
            } else {
                Log.d(TAG, "TTS播报成功: " + text);
            }
        } catch (Exception e) {
            Log.e(TAG, "TTS播报异常", e);
            reinitTTS();
        }
    }

    @Override
    public void stop() {
        if (tts != null && isInitialized) {
            tts.stop();
            Log.d(TAG, "TTS停止播报");
        }
    }

    @Override
    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        isInitialized = false;
        Log.d(TAG, "TTS服务已关闭");
    }

    @Override
    public Context getContext() {
        return context;
    }

    @Override
    public void setLanguage(Locale locale) {
        this.currentLocale = locale;
        if (tts != null && isInitialized) {
            int result = tts.setLanguage(locale);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "不支持的语言: " + locale.getDisplayLanguage());
            }
        }
    }

    @Override
    public void setSpeed(float speed) {
        this.currentSpeed = speed;
        if (tts != null && isInitialized) {
            tts.setSpeechRate(speed);
        }
    }

    /**
     * 检查TTS是否已初始化
     */
    public boolean isInitialized() {
        return isInitialized && tts != null;
    }
}