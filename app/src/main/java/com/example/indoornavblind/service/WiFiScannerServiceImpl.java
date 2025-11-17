package com.example.indoornavblind.service;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.util.Log;

import com.example.indoornavblind.model.WiFiData;
import com.example.indoornavblind.util.PermissionUtil;
import java.util.ArrayList;
import java.util.List;

public class WiFiScannerServiceImpl implements WiFiScannerService {
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

        // 2. 检查WiFi是否开启
        if (!wifiManager.isWifiEnabled()) {
            Log.e("WiFiScanner", "WiFi未开启，无法扫描");
            return results;
        }

        try {
            boolean scanStarted = wifiManager.startScan();
            Log.d("WiFiScanner", "扫描启动结果：" + (scanStarted ? "成功" : "失败"));
            if (!scanStarted) {
                Log.e("WiFiScanner", "扫描启动失败（可能被系统限制）");
                return results;
            }

            // 3. 延迟获取扫描结果（部分设备需要等待扫描完成）
            Thread.sleep(1000); // 等待1秒再获取结果
            List<ScanResult> scans = wifiManager.getScanResults();
            Log.d("WiFiScanner", "扫描到的WiFi数量：" + scans.size());

            for (ScanResult scan : scans) {
                if (scan.BSSID == null || scan.BSSID.isEmpty()) {
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
        } catch (InterruptedException e) {
            Log.e("WiFiScanner", "扫描等待被中断：" + e.getMessage());
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

    @Override
    public boolean hasPermission() {
        // 调用修复后的checkPermissions（传入Context参数）
        return PermissionUtil.checkPermissions(context);
    }
}