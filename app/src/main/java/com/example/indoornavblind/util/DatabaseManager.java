package com.example.indoornavblind.util;

import android.os.Handler;
import android.os.Looper;

import com.example.indoornavblind.database.AppDatabase;
import com.example.indoornavblind.database.entity.PositionEntity;
import com.example.indoornavblind.database.entity.WiFiFingerprintEntity;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 数据库管理工具类
 * 无需手动初始化，直接调用静态方法即可使用
 */
public class DatabaseManager {
    // 单线程池处理数据库操作（避免主线程阻塞）
    private static final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    // 主线程回调处理器
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 私有构造，禁止实例化
    private DatabaseManager() {}

    // ------------------------------ Position相关操作 ------------------------------
    /**
     * 插入位置信息
     * @param position 位置实体
     * @param callback 插入结果回调（主线程）
     */
    public static void insertPosition(PositionEntity position, OnOperationCallback<Long> callback) {
        dbExecutor.execute(() -> {
            try {
                long id = AppDatabase.getInstance().positionDao().insert(position);
                postToMainThread(() -> callback.onSuccess(id));
            } catch (Exception e) {
                postToMainThread(() -> callback.onFailure(e));
            }
        });
    }

    /**
     * 根据ID查询位置
     */
    public static void getPositionById(int id, OnOperationCallback<PositionEntity> callback) {
        dbExecutor.execute(() -> {
            try {
                PositionEntity entity = AppDatabase.getInstance().positionDao().findById(id);
                postToMainThread(() -> callback.onSuccess(entity));
            } catch (Exception e) {
                postToMainThread(() -> callback.onFailure(e));
            }
        });
    }

    // ------------------------------ WiFi指纹相关操作 ------------------------------
    /**
     * 批量插入WiFi指纹
     */
    public static void insertWiFiFingerprints(List<WiFiFingerprintEntity> fingerprints, OnOperationCallback<Void> callback) {
        dbExecutor.execute(() -> {
            try {
                AppDatabase.getInstance().wifiFingerprintDao().insertAll(fingerprints);
                postToMainThread(() -> callback.onSuccess(null));
            } catch (Exception e) {
                postToMainThread(() -> callback.onFailure(e));
            }
        });
    }

    /**
     * 根据BSSID列表查询指纹
     */
    public static void getWiFiFingerprintsByBssids(List<String> bssids, OnOperationCallback<List<WiFiFingerprintEntity>> callback) {
        dbExecutor.execute(() -> {
            try {
                List<WiFiFingerprintEntity> entities = AppDatabase.getInstance()
                        .wifiFingerprintDao()
                        .findByBssids(bssids);
                postToMainThread(() -> callback.onSuccess(entities));
            } catch (Exception e) {
                postToMainThread(() -> callback.onFailure(e));
            }
        });
    }

    // ------------------------------ 工具方法 ------------------------------
    // 主线程回调
    private static void postToMainThread(Runnable runnable) {
        mainHandler.post(runnable);
    }

    // 数据库操作回调接口
    public interface OnOperationCallback<T> {
        void onSuccess(T result);
        void onFailure(Exception e);
    }
}