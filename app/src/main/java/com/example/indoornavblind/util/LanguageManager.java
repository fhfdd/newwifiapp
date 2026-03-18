package com.example.indoornavblind.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

public class LanguageManager {

    private static final String PREFS_NAME = "AppLanguagePrefs";
    private static final String KEY_LANGUAGE = "selected_language";

    public static void setLanguage(Context context, String langCode) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LANGUAGE, langCode).apply();
    }

    public static String getLanguage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // Default to Simplified Chinese ("zh")
        return prefs.getString(KEY_LANGUAGE, "zh");
    }

    public static Context updateBaseContextLocale(Context context) {
        String lang = getLanguage(context);
        Locale locale = new Locale(lang);
        Locale.setDefault(locale);

        Resources res = context.getResources();
        Configuration config = res.getConfiguration();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale);
            config.setLayoutDirection(locale);
            return context.createConfigurationContext(config);
        } else {
            config.locale = locale;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                config.setLayoutDirection(locale);
            }
            res.updateConfiguration(config, res.getDisplayMetrics());
            return context;
        }
    }

    public static void applyTtsLanguage(TextToSpeech tts, String langCode) {
        if (tts == null) return;
        Locale locale = "zh".equals(langCode) ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        int result = tts.setLanguage(locale);
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Optional: log or toast
        }
    }
}