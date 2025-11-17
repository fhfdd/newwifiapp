package com.example.indoornavblind.database;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import com.example.indoornavblind.database.entity.PositionEntity;
import com.example.indoornavblind.database.entity.WiFiFingerprintEntity;

@Database(
        entities = {PositionEntity.class, WiFiFingerprintEntity.class},
        version = 3,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase instance;

    public abstract PositionDao positionDao();
    public abstract WiFiFingerprintDao wifiFingerprintDao();

    public static AppDatabase getInstance(android.content.Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "indoor_nav_db"
                    ).build();
                }
            }
        }
        return instance;
    }
}