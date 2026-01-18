package com.example.indoornavblind.service;

import android.content.Context;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import com.example.indoornavblind.model.WiFiData;
import com.example.indoornavblind.util.PermissionUtil;
import java.util.ArrayList;
import java.util.List;

public class L_WiFiScannerServiceImpl implements WiFiScannerService {
    private WifiManager wifiManager;
    private Context context;

    @Override
    public void init(Context context) {
        this.context = context;
        wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
    }

    // 在WiFiScannerServiceImpl的scanWiFi()方法中添加权限详细日志
    @Override
    public List<WiFiData> scanWiFi() {
        List<WiFiData> results = new ArrayList<>();

        // 1. 详细权限检查日志
        Log.d("WiFiScanner", "检查权限：hasPermission=" + hasPermission());
        if (!hasPermission()) {
            Log.e("WiFiScanner", "权限不足！需要：ACCESS_FINE_LOCATION、ACCESS_WIFI_STATE、CHANGE_WIFI_STATE");
            return results;
        }

        // 2. 检查位置服务是否开启（安卓系统强制要求）
        if (!isLocationEnabled()) {
            Log.e("WiFiScanner", "位置服务未开启，无法扫描WiFi");
            return results;
        }

        // 3. 检查并尝试开启WiFi
        if (!wifiManager.isWifiEnabled()) {
            Log.e("WiFiScanner", "WiFi未开启，尝试开启...");
            // 仅安卓Q以下支持自动开启WiFi
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                boolean enabled = wifiManager.setWifiEnabled(true);
                Log.d("WiFiScanner", "WiFi开启结果：" + (enabled ? "成功" : "失败"));
                // 等待WiFi启动（最多5秒）
                int waitCount = 0;
                while (!wifiManager.isWifiEnabled() && waitCount < 5) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                    waitCount++;
                }
            }
            // 再次检查WiFi状态
            if (!wifiManager.isWifiEnabled()) {
                Log.e("WiFiScanner", "WiFi仍未开启，无法扫描");
                return results;
            }
        }

        try {
            // 4. 启动WiFi扫描
            boolean scanStarted = wifiManager.startScan();
            Log.d("WiFiScanner", "扫描启动结果：" + (scanStarted ? "成功" : "失败"));
            if (!scanStarted) {
                Log.e("WiFiScanner", "扫描启动失败（可能被系统限制）");
                return results;
            }

            // 5. 循环等待扫描结果（最多3秒，每100ms检查一次）
            List<ScanResult> scans = null;
            int waitMs = 0;
            while (waitMs < 3000) {
                scans = wifiManager.getScanResults();
                if (scans != null && !scans.isEmpty()) {
                    break; // 拿到结果立即退出等待
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
                waitMs += 100;
            }

            // 6. 处理扫描结果
            if (scans == null || scans.isEmpty()) {
                Log.e("WiFiScanner", "扫描超时，未获取到有效结果");
                return results;
            }

            Log.d("WiFiScanner", "扫描到的WiFi数量：" + scans.size());
            for (ScanResult scan : scans) {
                if (scan.BSSID == null || scan.BSSID.isEmpty() || scan.level < -80) {
                    Log.w("WiFiScanner", "过滤无效WiFi（BSSID为空）");
                    continue;
                }
                WiFiData data = new WiFiData();
                data.setBssid(scan.BSSID);
                data.setRssi(scan.level);
                data.setSsid(scan.SSID);
                results.add(data);
                Log.d("WiFiScanner", "有效WiFi：BSSID=" + scan.BSSID + ", RSSI=" + scan.level);
            }
        } catch (SecurityException e) {
            Log.e("WiFiScanner", "权限异常：" + e.getMessage(), e);
        }
        return results;
    }

    public interface WifiStatusListener {
        void onWifiDisabled();
    }
    private WifiStatusListener wifiStatusListener;
    public void setWifiStatusListener(WifiStatusListener listener) {
        this.wifiStatusListener = listener;
    }

    // 在WiFiScannerServiceImpl的scanWiFi()方法中，权限检查后添加
    private boolean isLocationEnabled() {
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return lm != null && lm.isLocationEnabled();
        } else {
            try {
                int mode = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.LOCATION_MODE);
                return mode != Settings.Secure.LOCATION_MODE_OFF;
            } catch (Exception e) {
                return false;
            }
        }
    }


    @Override
    public boolean hasPermission() {
        // 调用修复后的checkPermissions（传入Context参数）
        return PermissionUtil.hasAllPermissions(context);
    }
}