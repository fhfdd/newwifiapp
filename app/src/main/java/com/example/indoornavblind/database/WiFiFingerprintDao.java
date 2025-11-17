package com.example.indoornavblind.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import com.example.indoornavblind.database.entity.WiFiFingerprintEntity;
import java.util.List;

@Dao
public interface WiFiFingerprintDao {
    // 批量插入WiFi指纹
    @Insert
    void insertAll(List<WiFiFingerprintEntity> fingerprints);

    // 单条插入WiFi指纹（新增：适配App.java中的循环插入）
    @Insert
    void insert(WiFiFingerprintEntity fingerprint);

    // 根据BSSID列表和楼层查询指纹
    @Query("SELECT * FROM wifi_fingerprints WHERE bssid IN (:bssids) AND floor = :floor")
    List<WiFiFingerprintEntity> findByBssidsAndFloor(List<String> bssids, int floor);
}