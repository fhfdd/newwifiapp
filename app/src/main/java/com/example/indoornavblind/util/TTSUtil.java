package com.example.indoornavblind.util;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Locale;

/**
 * Copyright (C) 2023-2024 Author
 *
 * TTS语音播报工具类
 *
 * @author   xiaolu
 * @date     2023/11/10
 * @version  1.0.0
 */
public class TTSUtil {
    private static final String TAG = "TTSUtil";

    private TextToSpeech textToSpeech;
    private Context context;
    private boolean initialized = false;
    private float defaultSpeechRate = 1.0f;  // 默认语速
    private float defaultPitch = 1.0f;       // 默认音调
    private Locale defaultLocale = Locale.CHINESE; // 默认语言
    private TTSListener listener;

    public interface TTSListener {
        void onInitSuccess();
        void onInitFailure();
        void onSpeechStart(String utteranceId);
        void onSpeechDone();
        void onSpeechError(String errorMessage);
    }

    public TextToSpeech getTextToSpeech() {
        return textToSpeech;
    }

    public TTSUtil(Context context, final TTSListener listener) {
        this.context = context;
        this.listener = listener;
        textToSpeech = new TextToSpeech(context, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    initialized = true;

                    // 设置播报进度监听器（只设置一次）
                    setupUtteranceListener();

                    if (listener != null) {
                        listener.onInitSuccess();
                    }
                    Log.d(TAG, "TTS初始化成功");
                } else {
                    Log.e(TAG, "TTS初始化失败，状态码: " + status);
                    Toast.makeText(context, "TTS初始化失败：" + status, Toast.LENGTH_SHORT).show();
                    if (listener != null) {
                        listener.onInitFailure();
                    }
                }
            }
        });
    }

    /**
     * 设置播报进度监听器
     */
    private void setupUtteranceListener() {
        if (textToSpeech != null) {
            textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    Log.d(TAG, "onStart: " + utteranceId);
                    if (listener != null) {
                        listener.onSpeechStart(utteranceId);
                    }
                }

                @Override
                public void onDone(String utteranceId) {
                    Log.d(TAG, "onDone: " + utteranceId);
                    if (listener != null) {
                        listener.onSpeechDone();
                    }
                }

                @Override
                public void onError(String utteranceId) {
                    Log.e(TAG, "onError: " + utteranceId);
                    if (listener != null) {
                        listener.onSpeechError("播报失败");
                    }
                }
            });
        }
    }

    // 设置默认语速
    public void setDefaultSpeechRate(float speechRate) {
        defaultSpeechRate = speechRate;
        if (textToSpeech != null && initialized) {
            textToSpeech.setSpeechRate(speechRate);
        }
    }

    // 设置默认音调
    public void setDefaultPitch(float pitch) {
        defaultPitch = pitch;
        if (textToSpeech != null && initialized) {
            textToSpeech.setPitch(pitch);
        }
    }

    // 设置默认语言
    public void setDefaultLocale(Locale locale) {
        defaultLocale = locale;
        if (textToSpeech != null && initialized) {
            int result = textToSpeech.setLanguage(locale);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "不支持的语言: " + locale.getDisplayLanguage());
            }
        }
    }

    // 文本转语音
    public void speak(String text) {
        speak(text, "" + System.currentTimeMillis());
    }

    public void speak(String text, String utteranceId) {
        speak(text, utteranceId, defaultLocale, defaultSpeechRate, defaultPitch);
    }

    public void speak(String text, String utteranceId, Locale locale, float speechRate, float pitch) {
        if (!initialized) {
            Log.w(TAG, "TTS未初始化，无法播报: " + text);
            return;
        }

        if (textToSpeech == null) {
            Log.e(TAG, "TextToSpeech对象为null");
            return;
        }

        try {
            // 设置语言
            int langResult = textToSpeech.setLanguage(locale);
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "不支持的语言，使用默认语言");
                textToSpeech.setLanguage(Locale.getDefault());
            }

            // 设置语速
            textToSpeech.setSpeechRate(speechRate);

            // 设置音调
            textToSpeech.setPitch(pitch);

            // 播报
            HashMap<String, String> params = new HashMap<>();
            params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
            int result = textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params);

            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "播报失败: " + text);
            } else {
                Log.d(TAG, "开始播报: " + text);
            }
        } catch (Exception e) {
            Log.e(TAG, "播报异常: " + e.getMessage(), e);
        }
    }

    public void stop() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            Log.d(TAG, "停止播报");
        }
    }

    // 释放资源
    public void release() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        initialized = false;
        Log.d(TAG, "TTS资源已释放");
    }
}
