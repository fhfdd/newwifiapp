package com.example.indoornavblind.model;

import java.util.List;

// 用于解析指纹JSON的外层结构
public class LocationWithWifis {
    private int floor;
    private String label;
    private String path;
    private double pixelX;
    private double pixelY;
    private String zone;
    private List<WiFiData> filteredWifis; // 对应JSON中的"wifis"数组
    private List<WiFiData> wifis;

    // 必须提供完整的Getter和Setter
    public int getFloor() {
        return floor;
    }

    public void setFloor(int floor) {
        this.floor = floor;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public double getPixelX() {
        return pixelX;
    }

    public void setPixelX(double pixelX) {
        this.pixelX = pixelX;
    }

    public double getPixelY() {
        return pixelY;
    }

    public void setPixelY(double pixelY) {
        this.pixelY = pixelY;
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public List<WiFiData> getWifis() {
        return wifis != null ? wifis : filteredWifis;
    }
    public void setWifis(List<WiFiData> filteredWifis) {
        this.filteredWifis = filteredWifis;
    }
}