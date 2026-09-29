package com.eyemonitor.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本地数据库（Room）。
 * <p>
 * v1：仅 chat 表。
 * v2：新增纪念日/位置/围栏/媒体四张缓存表（服务器为真源，本库为离线缓存）。
 * 升级走 Migration(1,2) 保留聊天数据；破坏性回退仅作为最后兜底。
 */
@Database(entities = {ChatEntity.class,
        AnniversaryCacheEntity.class,
        LocationCacheEntity.class,
        FenceCacheEntity.class,
        MediaCacheEntity.class}, version = 2, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    /** v1 -> v2：新增四张缓存表（CREATE TABLE 与原实体字段对齐） */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `anniversary_cache` (" +
                    "`serverId` INTEGER NOT NULL, `name` TEXT, `date` TEXT, " +
                    "`repeat` INTEGER NOT NULL, `isMine` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`serverId`))");
            db.execSQL("CREATE TABLE IF NOT EXISTS `location_cache` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`lat` REAL NOT NULL, `lng` REAL NOT NULL, " +
                    "`accuracy` REAL NOT NULL, `ts` INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS `fence_cache` (" +
                    "`serverId` INTEGER NOT NULL, `name` TEXT, `lat` REAL NOT NULL, " +
                    "`lng` REAL NOT NULL, `radius` REAL NOT NULL, `enabled` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`serverId`))");
            db.execSQL("CREATE TABLE IF NOT EXISTS `media_cache` (" +
                    "`fileId` TEXT NOT NULL, `serverFileName` TEXT, `mime` TEXT, " +
                    "`size` INTEGER NOT NULL, `duration` INTEGER NOT NULL, " +
                    "`localPath` TEXT, `ts` INTEGER NOT NULL, PRIMARY KEY(`fileId`))");
        }
    };

    private static volatile AppDatabase INSTANCE;

    /** 数据库写/读线程池（Room 禁止在主线程查询） */
    public static final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    public abstract ChatDao chatDao();

    public abstract CacheDao cacheDao();

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, "eye_monitor.db")
                            .addMigrations(MIGRATION_1_2)
                            // 没有可用 Migration 时（极端情况）才落到破坏性重建
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}