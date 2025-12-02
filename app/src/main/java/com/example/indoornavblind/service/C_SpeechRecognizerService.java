package com.example.indoornavblind.service;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

public class C_SpeechRecognizerService {
    private SpeechRecognizer speechRecognizer;
    private Context context;
    private OnRecognitionListener listener; // 监听器实例

    // 初始化服务（在ServiceFactory中调用）
    public void init(Context context) {
        this.context = context;
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
        } else {
            if (listener != null) {
                listener.onError("设备不支持语音识别");
            }
        }
    }

    // 新增：设置识别监听器（解决第二个错误）
    public void setRecognitionListener(OnRecognitionListener listener) {
        this.listener = listener;
        // 绑定监听器到SpeechRecognizer
        if (speechRecognizer != null && listener != null) {
            speechRecognizer.setRecognitionListener(new android.speech.RecognitionListener() {
                @Override
                public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    listener.onResult(matches);
                }

                @Override
                public void onError(int errorCode) {
                    String errorMsg = getErrorMsg(errorCode);
                    listener.onError(errorMsg);
                }

                // 其他必要的空实现（系统要求必须实现所有方法）
                @Override public void onReadyForSpeech(Bundle params) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
        }
    }

    // 开始语音识别（已有逻辑，确保权限检查正确）
    public void startListening() {
        if (speechRecognizer == null) {
            if (listener != null) {
                listener.onError("语音识别服务未初始化");
            }
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN"); // 中文识别
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        speechRecognizer.startListening(intent);
    }

    // 停止识别
    public void stopListening() {
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
        }
    }

    // 销毁资源
    public void destroy() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        listener = null;
        context = null;
    }

    // 错误码转文字提示
    private String getErrorMsg(int errorCode) {
        switch (errorCode) {
            case SpeechRecognizer.ERROR_NETWORK: return "网络错误";
            case SpeechRecognizer.ERROR_AUDIO: return "音频错误";
            case SpeechRecognizer.ERROR_SERVER: return "服务器错误";
            case SpeechRecognizer.ERROR_CLIENT: return "客户端错误";
            case SpeechRecognizer.ERROR_NO_MATCH: return "未找到匹配结果";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "识别器正忙";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "权限不足";
            default: return "识别错误，错误码：" + errorCode;
        }
    }

    // 识别结果回调接口（保持不变）
    public interface OnRecognitionListener {
        void onResult(ArrayList<String> results);
        void onError(String errorMsg);
    }
}