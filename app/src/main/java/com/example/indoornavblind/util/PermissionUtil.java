package com.example.indoornavblind.util;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class PermissionUtil {
    public static final int REQUEST_CODE = 100;

    // 基础权限（所有Android版本都需要）
    private static final String[] BASE_PERMISSIONS = {
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.VIBRATE,
            Manifest.permission.ACCESS_NETWORK_STATE
    };

    /**
     * 获取当前Android版本需要的所有权限列表
     * Android 12 (API 31)之前：需要定位权限来扫描WiFi
     * Android 12+ (API 31+)：NEARBY_WIFI_DEVICES权限用于WiFi扫描
     * Android 13+ (API 33+)：NEARBY_WIFI_DEVICES成为必需权限
     */
    private static String[] getRequiredPermissions() {
        List<String> permissions = new ArrayList<>();

        // 添加基础权限
        for (String perm : BASE_PERMISSIONS) {
            permissions.add(perm);
        }

        // Android 13+ (API 33+) 使用 NEARBY_WIFI_DEVICES 权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            // Android 13+ 仍需要定位权限（如果应用使用位置功能）
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        } else {
            // Android 12 及以下版本：WiFi扫描需要定位权限
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        return permissions.toArray(new String[0]);
    }

    /**
     * 检查是否拥有所有必需权限
     */
    public static boolean hasAllPermissions(Context context) {
        String[] permissions = getRequiredPermissions();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(context, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * 请求所有必需权限
     */
    public static void requestAllPermissions(Activity activity) {
        if (!hasAllPermissions(activity)) {
            String[] permissions = getRequiredPermissions();
            ActivityCompat.requestPermissions(activity, permissions, REQUEST_CODE);
        }
    }

    /**
     * 检查WiFi扫描权限是否足够
     * @return true 如果有足够的权限进行WiFi扫描
     */
    public static boolean hasWiFiScanPermission(Context context) {
        // Android 13+ 检查 NEARBY_WIFI_DEVICES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            boolean hasNearbyWifi = ContextCompat.checkSelfPermission(context,
                    Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
            boolean hasLocation = ContextCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            return hasNearbyWifi || hasLocation;
        }

        // Android 12 及以下：检查定位权限
        boolean hasFineLocation = ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasWifiState = ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED;

        return hasFineLocation && hasWifiState;
    }
}