package com.example.indoornavblind.service;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class VoskSpeechRecognizerService {
    private static final String TAG = "VoskServiceDebug";
    private static final String PREFS_NAME = "vosk_settings";
    private static final String KEY_CURRENT_LANGUAGE = "current_language";

    public enum Language {
        // 确保这里的文件名和 assets 里的完全一致
        CHINESE("vosk-model-small-cn-0.22.zip", "zh-CN", "中文", Locale.CHINESE),
        ENGLISH("vosk-model-small-en-us-0.15.zip", "en-US", "英文", Locale.US),
        CANTONESE("vosk-model-small-cn-0.22.zip", "yue", "粤語", new Locale("zh", "HK"));

        public final String modelName;
        public final String code;
        public final String displayName;
        public final Locale locale;

        Language(String modelName, String code, String displayName, Locale locale) {
            this.modelName = modelName;
            this.code = code;
            this.displayName = displayName;
            this.locale = locale;
        }
    }

    private Map<Language, Model> loadedModels = new HashMap<>();
    private boolean voskAvailable = true;
    private SpeechService speechService;
    private Context context;
    private OnRecognitionListener listener;
    private Language currentLanguage = Language.CHINESE;
    private boolean isListening = false;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    public void init(Context context, String unusedPath) {
        this.context = context;
        loadLanguagePreference();
        loadAllModels();
    }

    private void loadAllModels() {
        new Thread(() -> {
            // ✅ 大范围 try-catch，防止任何步骤导致崩溃
            try {
                for (Language lang : Language.values()) {
                    String assetFileName = lang.modelName;
                    String targetDirName = "model_" + lang.code;
                    File targetDir = new File(context.getFilesDir(), targetDirName);

                    // 1. 解压阶段
                    if (!isModelExtracted(targetDir)) {
                        Log.d(TAG, "开始解压模型: " + lang.displayName);
                        try {
                            unpackZip(context.getAssets().open(assetFileName), targetDir);
                        } catch (IOException e) {
                            Log.e(TAG, "解压失败: " + assetFileName, e);
                            notifyError("模型文件丢失: " + lang.displayName);
                            continue; // 跳过这个语言，继续下一个
                        }
                    }

                    // 2. 加载阶段 (这是最容易崩的地方)
                    Log.d(TAG, "正在加载模型到底层: " + lang.displayName);
                    try {
                        Model model = new Model(targetDir.getAbsolutePath());
                        synchronized (loadedModels) {
                            loadedModels.put(lang, model);
                        }
                        Log.d(TAG, "✅ 模型加载成功: " + lang.displayName);

                        if (lang == currentLanguage) {
                            mainHandler.post(() -> initRecognizer(lang));
                        }
                    } catch (UnsatisfiedLinkError e) {
                        Log.e(TAG, "❌ Vosk库不支持当前架构", e);
                        voskAvailable = false;
                        notifyError("语音识别不可用，请用文字输入");
                        return;
                    }catch (Exception e) {
                        Log.e(TAG, "❌ 模型加载异常", e);
                        notifyError("模型加载失败");
                    }
                }
            } catch (Throwable t) {
                // ✅ 捕获所有可能的运行时错误
                Log.e(TAG, "❌ 未知致命错误", t);
                notifyError("启动失败: " + t.getMessage());
            }
        }).start();
    }

    // 简单的检查文件夹是否非空
    private boolean isModelExtracted(File dir) {
        return dir.exists() && dir.isDirectory() && dir.list() != null && dir.list().length > 0;
    }

    private static void unpackZip(InputStream zipInputStream, File targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(zipInputStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File file = new File(targetDir, entry.getName());
                if (entry.isDirectory()) {
                    if (!file.exists()) file.mkdirs();
                } else {
                    if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
            }
        }
    }

    private void initRecognizer(Language language) {
        Model model = loadedModels.get(language);
        if (model == null) {
            notifyError("模型未就绪");
            return;
        }

        try {
            if (speechService != null) {
                speechService.stop();
                speechService.shutdown();
                speechService = null;
            }

            Recognizer recognizer = new Recognizer(model, 16000.0f);
            speechService = new SpeechService(recognizer, 16000.0f);
            Log.d(TAG, "SpeechService initialized");

        } catch (IOException e) {
            Log.e(TAG, "Recognizer init failed", e);
            notifyError("识别器初始化失败");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "JNA库加载失败", e);
            notifyError("模拟器缺少底层库");
        }
    }

    // ... 下面保持不变 (startListening, stopListening, switchLanguage, Grammars) ...

    public void startListening() {
        if (speechService != null) {
            if (isListening) return;
            speechService.startListening(recognitionListener);
            isListening = true;
        } else {
            notifyError("服务未就绪");
        }
    }

    public void stopListening() {
        if (speechService != null) {
            speechService.stop();
            isListening = false;
        }
    }

    public void switchLanguage(Language language) {
        if (language == currentLanguage && speechService != null) return;
        currentLanguage = language;
        saveLanguagePreference();
        initRecognizer(language);
    }

    private RecognitionListener recognitionListener = new RecognitionListener() {
        @Override
        public void onPartialResult(String hypothesis) {}

        @Override
        public void onResult(String hypothesis) {
            isListening = false;
            try {
                JSONObject json = new JSONObject(hypothesis);
                String text = json.optString("text", "").trim();
                if (!text.isEmpty()) {
                    Log.d(TAG, "Result: " + text);
                    if (handleLanguageSwitch(text)) return;
                    notifyResult(text);
                }
            } catch (Exception e) {
                Log.e(TAG, "Result parse error", e);
            }
        }

        @Override
        public void onFinalResult(String hypothesis) { onResult(hypothesis); }

        @Override
        public void onError(Exception exception) {
            isListening = false;
            notifyError(exception.getMessage());
        }

        @Override
        public void onTimeout() { isListening = false; }
    };

    private boolean handleLanguageSwitch(String text) {
        text = text.toLowerCase();
        if (text.contains("切换英文") || text.contains("switch to english")) {
            switchLanguage(Language.ENGLISH);
            notifyResult("Switched to English");
            return true;
        } else if (text.contains("切换中文") || text.contains("switch to chinese")) {
            switchLanguage(Language.CHINESE);
            notifyResult("Switched to Chinese");
            return true;
        }
        return false;
    }

    private void notifyResult(String text) {
        if (listener != null) mainHandler.post(() -> listener.onResult(new ArrayList<String>(){{add(text);}}));
    }

    private void notifyError(String msg) {
        if (listener != null) mainHandler.post(() -> listener.onError(msg));
    }

    private void saveLanguagePreference() {
        if (context != null) context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_CURRENT_LANGUAGE, currentLanguage.name()).apply();
    }

    private void loadLanguagePreference() {
        if (context != null) {
            String name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_CURRENT_LANGUAGE, Language.CHINESE.name());
            try { currentLanguage = Language.valueOf(name); } catch (Exception e) {}
        }
    }

    public void setRecognitionListener(OnRecognitionListener listener) { this.listener = listener; }
    public boolean isInitialized() { return voskAvailable && loadedModels.get(currentLanguage) != null; }
    public boolean isListening() { return isListening; }
    public Language getCurrentLanguage() { return currentLanguage; }
    public ArrayList<Language> getLoadedLanguages() { return new ArrayList<>(loadedModels.keySet()); }
    public String getModelStatus() { return "Models: " + loadedModels.size(); }
    public void destroy() { if (speechService != null) speechService.shutdown(); loadedModels.clear(); }

    public interface OnRecognitionListener {
        void onResult(ArrayList<String> results);
        void onError(String errorMsg);
    }

    private String getGrammarForLanguage(Language language) {
        if (language == Language.ENGLISH) return getEnglishGrammar();
        if (language == Language.CANTONESE) return getCantoneseGrammar();
        return getChineseGrammar();
    }

    private String getCantoneseGrammar() {
        return "[" +
                "\"去浴室\", \"去门口\", \"去楼梯\", \"去电梯\", \"去厕所\", \"去出口\", " +
                "\"我喺边\", \"我喺边度\", \"定位\", " +
                "\"附近有咩\", \"停止导航\", \"取消导航\", \"重复\", \"再讲一次\", " +
                "\"切换英文\", \"切换中文\", \"切换粤语\", " +
                "\"帮助\"" +
                "]";
    }

    private String getChineseGrammar() {
        return "[" +
                "\"去浴室\", \"去门口\", \"去楼梯\", \"去电梯\", \"去厕所\", \"去洗手间\", \"去出口\", \"去入口\", \"去办公室\", \"去会议室\", \"去大厅\", " +
                "\"我在浴室\", \"我在门口\", \"我在楼梯\", \"我在电梯\", \"我在厕所\", \"我在洗手间\", \"我在出口\", \"我在入口\", " +
                "\"我在哪\", \"我在哪里\", \"当前位置\", \"定位\", " +
                "\"附近有什么\", \"周围有什么\", \"查询附近\", \"导航进度\", \"还有多远\", " +
                "\"开始导航\", \"停止导航\", \"取消导航\", \"重复\", \"再说一遍\", \"加快速度\", \"减慢速度\", " +
                "\"切换英文\", \"切换中文\", \"英文模式\", \"中文模式\", " +
                "\"帮助\", \"打开设置\", \"紧急求助\", \"帮我\"" +
                "]";
    }

    private String getEnglishGrammar() {
        return "[" +
                "\"go to bathroom\", \"go to entrance\", \"go to stairs\", \"go to elevator\", \"go to toilet\", \"go to exit\", \"go to office\", \"go to hall\", " +
                "\"i am at bathroom\", \"i am at entrance\", \"i am at stairs\", " +
                "\"where am i\", \"current location\", \"locate me\", " +
                "\"what's nearby\", \"navigation progress\", \"how far\", " +
                "\"start navigation\", \"stop navigation\", \"repeat\", \"speed up\", \"slow down\", " +
                "\"switch to chinese\", \"switch to english\", " +
                "\"help\", \"settings\", \"emergency\"" +
                "]";
    }
}