package com.example.indoornavblind.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import com.example.indoornavblind.database.entity.PositionEntity;

@Dao
public interface PositionDao {
    @Insert
    long insert(PositionEntity position);

    @Query("SELECT * FROM positions WHERE id = :id")
    PositionEntity findById(int id);

    @Query("SELECT COUNT(*) FROM positions")
    int getCount();

    // 暂时注释（后续实现时再打开）
    // @Query("SELECT * FROM positions WHERE label = :label AND floor = :floor LIMIT 1")
    // PositionEntity findByLabelAndFloor(String label, int floor);
}