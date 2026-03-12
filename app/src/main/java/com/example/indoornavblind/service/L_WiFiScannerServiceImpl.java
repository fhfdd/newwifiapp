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

/**
 * WiFi 扫描实现：支持权限/状态检查、系统节流时使用缓存、结果过滤与统一日志。
 */
public class L_WiFiScannerServiceImpl implements WiFiScannerService {
    private static final String TAG = "WiFiScanner";

    private static final int WAIT_SCAN_MS = 3000;       // startScan 成功后等待结果最长时间
    private static final int WAIT_CACHE_MS = 600;      // startScan 失败时等待缓存的最长时间
    private static final int POLL_INTERVAL_MS = 100;    // 轮询间隔
    private static final int RSSI_THRESHOLD = -90;      // 信号强度阈值（≥此值才纳入，弱信号也可参与指纹匹配）
    private static final int WIFI_START_RETRY_SEC = 5;  // WiFi 开启后等待就绪秒数

    private WifiManager wifiManager;
    private Context context;

    @Override
    public void init(Context context) {
        this.context = context;
        if (context != null) {
            wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        }
    }

    @Override
    public List<WiFiData> scanWiFi() {
        List<WiFiData> results = new ArrayList<>();
        if (context == null || wifiManager == null) {
            Log.e(TAG, "未初始化或 WifiManager 不可用");
            return results;
        }

        if (!hasPermission()) {
            Log.e(TAG, "权限不足：需 ACCESS_FINE_LOCATION（Android13+ 或 NEARBY_WIFI_DEVICES/定位）");
            return results;
        }
        if (!isLocationEnabled()) {
            Log.e(TAG, "位置服务未开启，请在系统设置中开启「位置信息」");
            return results;
        }

        if (!ensureWifiEnabled()) {
            return results;
        }

        try {
            boolean scanStarted = wifiManager.startScan();
            Log.d(TAG, "startScan=" + scanStarted + "（false 多为系统节流，将尝试缓存）");

            List<ScanResult> raw = waitForScanResults(scanStarted);
            if (raw == null || raw.isEmpty()) {
                Log.e(TAG, "无扫描结果与缓存。请确认：位置已开、WiFi 已开；若刚打开应用可稍后再试");
                return results;
            }

            results = filterAndConvert(raw);
            Log.d(TAG, "有效WiFi数：" + results.size() + (scanStarted ? "" : "（来自缓存）"));
        } catch (SecurityException e) {
            Log.e(TAG, "权限异常：" + e.getMessage(), e);
        }
        return results;
    }

    /** 等待扫描结果；startScan 失败时短等并依赖 getScanResults 缓存 */
    private List<ScanResult> waitForScanResults(boolean scanStarted) {
        int maxWait = scanStarted ? WAIT_SCAN_MS : WAIT_CACHE_MS;
        int elapsed = 0;
        while (elapsed < maxWait) {
            List<ScanResult> list = wifiManager.getScanResults();
            if (list != null && !list.isEmpty()) {
                if (!scanStarted && elapsed > 0) {
                    Log.d(TAG, "使用缓存结果，共 " + list.size() + " 条");
                }
                return list;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            elapsed += POLL_INTERVAL_MS;
        }
        return wifiManager.getScanResults();
    }

    /** 过滤无效项并转为 WiFiData（BSSID 为空或信号低于阈值则丢弃） */
    private List<WiFiData> filterAndConvert(List<ScanResult> raw) {
        List<WiFiData> list = new ArrayList<>();
        for (ScanResult r : raw) {
            if (r.BSSID == null || r.BSSID.isEmpty() || r.level < RSSI_THRESHOLD) continue;
            WiFiData d = new WiFiData();
            d.setBssid(r.BSSID);
            d.setRssi(r.level);
            d.setSsid(r.SSID);
            list.add(d);
        }
        return list;
    }

    private boolean ensureWifiEnabled() {
        if (wifiManager.isWifiEnabled()) return true;
        Log.w(TAG, "WiFi 未开启，尝试开启…");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            wifiManager.setWifiEnabled(true);
            for (int i = 0; i < WIFI_START_RETRY_SEC && !wifiManager.isWifiEnabled(); i++) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            }
        }
        if (!wifiManager.isWifiEnabled()) {
            Log.e(TAG, "WiFi 无法开启，无法扫描");
            return false;
        }
        return true;
    }

    public interface WifiStatusListener {
        void onWifiDisabled();
    }
    private WifiStatusListener wifiStatusListener;
    public void setWifiStatusListener(WifiStatusListener listener) {
        this.wifiStatusListener = listener;
    }

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
        // 使用专门的WiFi扫描权限检查，支持Android 13+的新权限
        return PermissionUtil.hasWiFiScanPermission(context);
    }
}