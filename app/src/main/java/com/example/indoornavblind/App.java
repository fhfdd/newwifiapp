package com.example.indoornavblind;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Log;
import com.example.indoornavblind.database.AppDatabase;
import com.example.indoornavblind.database.entity.PositionEntity;
import com.example.indoornavblind.database.entity.WiFiFingerprintEntity;
import com.example.indoornavblind.model.LocationWithWifis;
import com.example.indoornavblind.model.WiFiData;
import com.example.indoornavblind.util.PathParser;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class App extends Application {
    private static App instance;
    private AppDatabase db;
    private SharedPreferences prefs;

    // 配置需要导入的指纹文件（确保文件名和路径正确）

    private static final String[] FINGERPRINT_FILES = {"3c.json","2c.json","3a.json","26.json","3cComplex.json","2cComplex.json"};


    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        db = AppDatabase.getInstance();
        prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        PathParser.init(this);

        // 🔥 优化：先检查数据库是否有指纹数据，没有则重新导入（不管标记）
        Executors.newSingleThreadExecutor().execute(() -> {
            int existingCount = db.wifiFingerprintDao().getTotalCount();
            Log.d("App", "数据库中现有指纹数量：" + existingCount);

            importAllFingerprintFiles();

            if (existingCount == 0 || prefs.getBoolean("force_reimport", false)) {
                importAllFingerprintFiles();
                prefs.edit().putBoolean("force_reimport", false).apply();
            } else {
                Log.d("App", "数据库已有指纹数据，跳过导入");
            }
        });
    }
    /** 批量导入所有配置的指纹文件 */
    private void importAllFingerprintFiles() {
        int totalSuccess = 0;
        int totalFail = 0;
        int totalRecords = 0; // 记录总导入条数
        AssetManager assetManager = getAssets();

        // 遍历所有配置的文件
        for (String fileName : FINGERPRINT_FILES) {
            try {
                // 1. 读取单个文件（代码不变）
                InputStream is = getAssets().open(fileName);
                Log.d("App", "成功打开文件：" + fileName + "，大小：" + is.available());
                int size = is.available();
                byte[] buffer = new byte[size];
                is.read(buffer);
                is.close();
                String json = new String(buffer, "UTF-8");

                // 2. 解析JSON外层结构（代码不变）
                Gson gson = new Gson();
                Type locationType = new TypeToken<List<LocationWithWifis>>() {}.getType();
                List<LocationWithWifis> locationList = gson.fromJson(json, locationType);

                if (locationList == null || locationList.isEmpty()) {
                    Log.w("App", "文件 " + fileName + " 解析为空（无位置数据）");
                    totalFail++;
                    continue;
                }

                // 3. 批量处理位置和指纹（代码不变）
                List<WiFiFingerprintEntity> allFingerprints = new ArrayList<>();
                for (LocationWithWifis location : locationList) {
                    // 插入位置信息（代码不变）
                    PositionEntity position = new PositionEntity();
                    position.setFloor(location.getFloor());
                    position.setLabel(location.getLabel());
                    position.setPixelX(location.getPixelX());
                    position.setPixelY(location.getPixelY());
                    position.setZone(location.getZone());
                    long locationId = db.positionDao().insert(position);

                    // 处理WiFi指纹（代码不变）
                    List<WiFiData> filteredWifis = location.getWifis();
                    if (filteredWifis != null && !filteredWifis.isEmpty()) {
                        for (WiFiData wifi : filteredWifis) {
                            WiFiFingerprintEntity fingerprint = new WiFiFingerprintEntity();
                            fingerprint.setLocationId((int) locationId);
                            fingerprint.setBssid(wifi.getBssid().trim());
                            fingerprint.setRssi(wifi.getRssi());
                            fingerprint.setSsid(wifi.getSsid());
                            fingerprint.setFloor(location.getFloor());
                            allFingerprints.add(fingerprint);
                        }
                    } else {
                        Log.w("App", "位置 " + location.getLabel() + " 下无WiFi数据");
                    }
                }

                // 4. 批量插入指纹到数据库
                if (!allFingerprints.isEmpty()) {
                    db.wifiFingerprintDao().insertAll(allFingerprints);
                    int fileRecords = allFingerprints.size(); // 当前文件的指纹数
                    totalRecords += fileRecords; // 累加到总记录数（关键修复）
                    Log.d("App", "文件 " + fileName + " 导入成功：" +
                            "位置数=" + locationList.size() +
                            "，指纹数=" + fileRecords);
                    totalSuccess++;
                } else {
                    Log.w("App", "文件 " + fileName + " 没有可导入的指纹数据");
                    totalFail++;
                }

            } catch (Exception e) {
                totalFail++;
                Log.e("App", "文件 " + fileName + " 导入失败：" + e.getMessage(), e);
            }
        }

        // 所有文件处理完成后，根据总记录数设置标记（关键修复）
        Log.d("App", "所有文件处理完成：成功" + totalSuccess + "个，失败" + totalFail + "个，总指纹数：" + totalRecords);
        if (totalRecords > 0) {
            prefs.edit().putBoolean("all_files_imported", true).apply();
            Log.d("App", "导入成功，标记为已导入");
        } else {
            prefs.edit().putBoolean("all_files_imported", false).apply();
            Log.w("App", "未导入任何有效数据，下次启动将重试");
        }

        // 调试：检查导入后的数据总量
        new Thread(() -> {
            int count = db.wifiFingerprintDao().getTotalCount();
            Log.d("App", "导入后数据库指纹总数：" + count);
            WiFiFingerprintEntity test = db.wifiFingerprintDao().findByBssid("74:3e:2b:bb:4c:fd");
            Log.d("App", "测试BSSID 74:3e:2b:bb:4c:fd 是否存在：" + (test != null ? "存在" : "不存在"));
        }).start();
    }

    public static App getInstance() {
        return instance;
    }

    public AppDatabase getDb() {
        return db;
    }
}