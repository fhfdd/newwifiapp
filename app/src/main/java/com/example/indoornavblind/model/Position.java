package com.example.indoornavblind.model;

public class Position {
    private double pixelX;
    private double pixelY;
    private int floor;
    private String zone;
    private String label;

    public double getPixelX() { return pixelX; }
    public void setPixelX(double pixelX) { this.pixelX = pixelX; }
    public double getPixelY() { return pixelY; }
    public void setPixelY(double pixelY) { this.pixelY = pixelY; }
    public int getFloor() { return floor; }
    public void setFloor(int floor) { this.floor = floor; }
    public String getZone() { return zone; }
    public void setZone(String zone) { this.zone = zone; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}