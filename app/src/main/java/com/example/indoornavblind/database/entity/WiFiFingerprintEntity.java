package com.example.indoornavblind.database.entity;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "wifi_fingerprints",
        foreignKeys = @ForeignKey(
                entity = PositionEntity.class,
                parentColumns = "id",
                childColumns = "locationId",
                onDelete = ForeignKey.CASCADE
        ),
        indices = @Index(value = "locationId") // 新增索引，消除外键无索引警告
)
public class WiFiFingerprintEntity {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private int locationId; // 关联PositionEntity的id
    private String bssid;
    private int rssi;
    private String ssid;
    private int floor; // 存储对应楼层，方便筛选

    // 完整Getter和Setter
    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getLocationId() {
        return locationId;
    }

    public void setLocationId(int locationId) {
        this.locationId = locationId;
    }

    public String getBssid() {
        return bssid;
    }

    public void setBssid(String bssid) {
        this.bssid = bssid;
    }

    public int getRssi() {
        return rssi;
    }

    public void setRssi(int rssi) {
        this.rssi = rssi;
    }

    public String getSsid() {
        return ssid;
    }

    public void setSsid(String ssid) {
        this.ssid = ssid;
    }

    public int getFloor() {
        return floor;
    }

    public void setFloor(int floor) {
        this.floor = floor;
    }
}