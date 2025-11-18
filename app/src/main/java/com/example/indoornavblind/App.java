package com.example.indoornavblind;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.indoornavblind.database.AppDatabase;
import com.example.indoornavblind.database.entity.WiFiFingerprintEntity;
import com.example.indoornavblind.util.PathParser;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.Executors;

public class App extends Application {
    private static App instance;
    private AppDatabase db;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // 初始化数据库
        db = AppDatabase.getInstance(this);
        // 初始化SharedPreferences
        prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        // 初始化路径解析器（加载path_db.json）
        PathParser.init(this);
        // 初始化定位指纹数据（仅首次启动）
        if (!prefs.getBoolean("data_imported", false)) {
            Executors.newSingleThreadExecutor().execute(this::importFingerprintData);
        }
    }

    /** 导入指纹库数据（从assets/fingerprint_db.json到Room） */
    private void importFingerprintData() {
        try {
            // 读取assets中的JSON文件
            InputStream is = getAssets().open("fingerprint_db.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String json = new String(buffer, "UTF-8");

            // 解析JSON为WiFi指纹实体列表（根据实际JSON结构调整）
            Gson gson = new Gson();
            Type fingerprintType = new TypeToken<List<WiFiFingerprintEntity>>(){}.getType();
            List<WiFiFingerprintEntity> fingerprints = gson.fromJson(json, fingerprintType);

            // 插入数据库
            db.wifiFingerprintDao().insertAll(fingerprints);
            Log.d("App", "指纹数据导入成功，共" + fingerprints.size() + "条");

            // 标记为已导入
            prefs.edit().putBoolean("data_imported", true).apply();
        } catch (Exception e) {
            Log.e("App", "指纹数据导入失败", e);
        }
    }

    public static App getInstance() {
        return instance;
    }

    public AppDatabase getDb() {
        return db;
    }

    public SharedPreferences getPrefs() {
        return prefs;
    }
}