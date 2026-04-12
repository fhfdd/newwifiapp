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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Position position = (Position) o;
        return floor == position.floor &&
                label != null && label.equals(position.label);
    }

    @Override
    public int hashCode() {
        int result = floor;
        result = 31 * result + (label != null ? label.hashCode() : 0);
        return result;
    }
}