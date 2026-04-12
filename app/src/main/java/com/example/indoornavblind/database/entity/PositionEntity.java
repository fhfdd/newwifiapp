package com.example.indoornavblind.database.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "positions")
public class PositionEntity {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private double pixelX;
    private double pixelY;
    private int floor;
    private String zone;
    private String label;
    private String path; // 仅保留JSON中存在的字段

    // 仅保留对应字段的Getter/Setter
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
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
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
}