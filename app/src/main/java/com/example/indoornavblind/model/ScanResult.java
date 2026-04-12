package com.example.indoornavblind.model;

import java.util.List;

public class ScanResult {
    private List<WiFiData> filteredWifis;
    private int floor;
    private String label;
    private String path;
    private double pixelX;
    private double pixelY;
    private String zone;

    // Getter和Setter（必须与JSON字段一一对应，大小写敏感）
    public List<WiFiData> getFilteredWifis() {
        return filteredWifis;
    }

    public void setFilteredWifis(List<WiFiData> filteredWifis) {
        this.filteredWifis = filteredWifis;
    }

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
}