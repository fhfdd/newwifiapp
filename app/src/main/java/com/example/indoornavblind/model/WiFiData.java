package com.example.indoornavblind.model;

public class WiFiData {
    private String bssid;
    private int rssi;
    private String ssid;

    public String getBssid() { return bssid; }
    public void setBssid(String bssid) { this.bssid = bssid; }
    public int getRssi() { return rssi; }
    public void setRssi(int rssi) { this.rssi = rssi; }
    public String getSsid() { return ssid; }
    public void setSsid(String ssid) { this.ssid = ssid; }
}