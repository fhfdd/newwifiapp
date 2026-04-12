package com.example.indoornavblind.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import com.example.indoornavblind.database.entity.WiFiFingerprintEntity;
import java.util.List;

@Dao
public interface WiFiFingerprintDao {

    // 批量插入指纹数据
    @Insert
    void insertAll(List<WiFiFingerprintEntity> fingerprints);

    // 关键：查询包含任何一个目标BSSID的指纹（使用IN关键字）
    @Query("SELECT * FROM wifi_fingerprints WHERE bssid IN (:bssids)")
    List<WiFiFingerprintEntity> findByBssids(List<String> bssids);

    // 新增：验证数据是否导入成功（用于调试）
    @Query("SELECT COUNT(*) FROM wifi_fingerprints")
    int getTotalCount();


    @Query("SELECT * FROM wifi_fingerprints WHERE bssid = :bssid LIMIT 1")
    WiFiFingerprintEntity findByBssid(String bssid);
}