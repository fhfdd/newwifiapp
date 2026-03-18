package com.example.indoornavblind.service;

import android.content.Context;
import com.example.indoornavblind.model.Position;
import java.util.List;

/**
 * 定位服务接口：抽象定位能力，可扩展不同定位算法（KNN、指纹匹配等）
 */
public interface LocationService {
    void init(Context context);
    // 异步定位（新增回调，避免阻塞）
    void locate(LocationCallback callback);
    interface LocationCallback {
        void onSuccess(Position position);
        void onFailure(String error);
    }
}