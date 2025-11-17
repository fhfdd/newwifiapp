package com.example.indoornavblind;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.example.indoornavblind.database.AppDatabase;
import com.example.indoornavblind.database.entity.PositionEntity;
import com.example.indoornavblind.database.entity.WiFiFingerprintEntity;
import com.example.indoornavblind.model.ScanResult;
import com.example.indoornavblind.model.WiFiData;
import com.example.indoornavblind.util.GsonUtil;
import com.example.indoornavblind.util.PathParser;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
        db = AppDatabase.getInstance(this);
        prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE);

        PathParser.init(this);

        // 强制重新导入（首次运行/JSON更新后使用，稳定后可改为 !prefs.getBoolean("data_imported", false)）
        Executors.newSingleThreadExecutor().execute(this::importFingerprints);
    }

    private void importFingerprints() {
        try {
            // 读取assets中的JSON文件
            InputStream is = getAssets().open("fingerprint_db.json");
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();
            String jsonContent = sb.toString();
            Log.d("App", "JSON文件读取成功，长度：" + jsonContent.length());

            // 解析JSON为ScanResult列表
            Type scanResultType = new TypeToken<List<ScanResult>>() {}.getType();
            List<ScanResult> scanResults = GsonUtil.fromJson(jsonContent, scanResultType);

            // 空值判断1：解析结果非空
            if (scanResults == null || scanResults.isEmpty()) {
                Log.e("App", "JSON解析结果为空，无数据可导入");
                return;
            }
            Log.d("App", "JSON解析成功，共" + scanResults.size() + "个位置数据");

            // 循环插入每个位置和对应的WiFi指纹
            for (int i = 0; i < scanResults.size(); i++) {
                ScanResult result = scanResults.get(i);
                // 空值判断2：单个位置数据非空
                if (result == null) {
                    Log.e("App", "第" + (i + 1) + "条位置数据为空，跳过");
                    continue;
                }

                // 空值判断3：核心字段非空（label和WiFi列表）
                if (result.getLabel() == null || result.getLabel().trim().isEmpty()) {
                    Log.e("App", "第" + (i + 1) + "条位置缺少label字段，跳过");
                    continue;
                }
                if (result.getFilteredWifis() == null || result.getFilteredWifis().isEmpty()) {
                    Log.e("App", "第" + (i + 1) + "条位置（" + result.getLabel() + "）WiFi列表为空，跳过");
                    continue;
                }

                // 转换为PositionEntity（确保对象非空）
                PositionEntity position = new PositionEntity();
                position.setFloor(result.getFloor());
                position.setLabel(result.getLabel().trim());
                position.setPath(result.getPath() != null ? result.getPath().trim() : "");
                position.setPixelX(result.getPixelX());
                position.setPixelY(result.getPixelY());
                position.setZone(result.getZone() != null ? result.getZone().trim() : "");

                // 插入位置到数据库，获取自动生成的ID
                long locationId = db.positionDao().insert(position);
                Log.d("App", "插入位置：" + result.getLabel() + "，ID：" + locationId);

                // 插入对应的WiFi指纹
                for (WiFiData wifi : result.getFilteredWifis()) {
                    if (wifi == null || wifi.getBssid() == null || wifi.getBssid().trim().isEmpty()) {
                        Log.w("App", "位置" + result.getLabel() + "的WiFi数据为空，跳过");
                        continue;
                    }
                    WiFiFingerprintEntity fingerprint = new WiFiFingerprintEntity();
                    fingerprint.setBssid(wifi.getBssid().trim());
                    fingerprint.setRssi(wifi.getRssi());
                    fingerprint.setSsid(wifi.getSsid() != null ? wifi.getSsid().trim() : "");
                    fingerprint.setLocationId((int) locationId);
                    fingerprint.setFloor(result.getFloor());
                    db.wifiFingerprintDao().insert(fingerprint);
                }
            }

            // 标记数据已导入，避免重复执行
            prefs.edit().putBoolean("data_imported", true).apply();
            Log.d("App", "所有有效位置数据导入成功！");

        } catch (IOException e) {
            Log.e("App", "JSON文件读取失败：" + e.getMessage(), e);
        } catch (Exception e) {
            Log.e("App", "数据导入异常：" + e.getMessage(), e);
        }
    }

    // 单例获取方法
    public static App getInstance() {
        return instance;
    }

    public AppDatabase getDb() {
        return db;
    }
}