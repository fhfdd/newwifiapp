package com.example.indoornavblind.database;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import com.example.indoornavblind.App;
import com.example.indoornavblind.database.entity.PositionEntity;
import com.example.indoornavblind.database.entity.WiFiFingerprintEntity;

@Database(
        entities = {PositionEntity.class, WiFiFingerprintEntity.class},
        version = 3,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase instance;
    // 数据库名称常量
    private static final String DB_NAME = "indoor_nav_db";

    // DAO获取方法
    public abstract PositionDao positionDao();
    public abstract WiFiFingerprintDao wifiFingerprintDao();

    // 单例获取（自动使用Application上下文）
    public static AppDatabase getInstance() {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    // 直接使用Application的上下文，避免内存泄漏
                    instance = Room.databaseBuilder(
                            App.getInstance().getApplicationContext(),
                            AppDatabase.class,
                            DB_NAME
                    ).build();
                }
            }
        }
        return instance;
    }
}