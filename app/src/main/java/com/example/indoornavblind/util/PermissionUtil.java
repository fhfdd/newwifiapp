package com.example.indoornavblind.util;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;

/**
 * 权限工具类：补充语音识别所需的麦克风权限检查
 */
public class PermissionUtil {
    public static final int REQUEST_CODE = 101;
    // 基础定位权限（原有关联）
    private static final String[] BASE_PERMISSIONS = {
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_WIFI_STATE,
            android.Manifest.permission.CHANGE_WIFI_STATE
    };
    // 语音识别额外需要的麦克风权限
    private static final String[] SPEECH_PERMISSIONS = {
            android.Manifest.permission.RECORD_AUDIO
    };

    // 检查基础定位权限（原方法保留）
    public static boolean checkPermissions(Context context) {
        return checkPermissions(context, BASE_PERMISSIONS);
    }

    // 新增：检查语音识别所需的所有权限（包括麦克风）
    public static boolean hasAllPermissions(Context context) {
        // 合并基础权限和语音权限
        String[] allPermissions = new String[BASE_PERMISSIONS.length + SPEECH_PERMISSIONS.length];
        System.arraycopy(BASE_PERMISSIONS, 0, allPermissions, 0, BASE_PERMISSIONS.length);
        System.arraycopy(SPEECH_PERMISSIONS, 0, allPermissions, BASE_PERMISSIONS.length, SPEECH_PERMISSIONS.length);
        return checkPermissions(context, allPermissions);
    }

    // 通用权限检查逻辑（私有工具方法）
    private static boolean checkPermissions(Context context, String[] permissions) {
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    // 申请所有必要权限（包括语音）
    public static void requestAllPermissions(Activity activity) {
        String[] allPermissions = new String[BASE_PERMISSIONS.length + SPEECH_PERMISSIONS.length];
        System.arraycopy(BASE_PERMISSIONS, 0, allPermissions, 0, BASE_PERMISSIONS.length);
        System.arraycopy(SPEECH_PERMISSIONS, 0, allPermissions, BASE_PERMISSIONS.length, SPEECH_PERMISSIONS.length);
        activity.requestPermissions(allPermissions, REQUEST_CODE);
    }
}