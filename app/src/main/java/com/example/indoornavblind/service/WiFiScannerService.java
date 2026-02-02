package com.example.indoornavblind.service;

import android.content.Context;
import com.example.indoornavblind.model.WiFiData;
import java.util.List;

public interface WiFiScannerService {
    void init(Context context);
    List<WiFiData> scanWiFi();
    boolean hasPermission();
}