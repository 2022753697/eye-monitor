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
        MediaCacheEntity.class,
        AppNameCacheEntity.class,
        FolderCacheEntity.class}, version = 6, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    /** v5 -> v6：聊天发送状态（sent/pending，离线消息持久标记 + 自动补发） */
    public static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE `chat` ADD COLUMN `send_state` TEXT DEFAULT 'sent'");
        }
    };

    /** v4 -> v5：聊天消息 P2 四字段（已读/撤回/引用） */
    public static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE `chat` ADD COLUMN `peer_read` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `chat` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `chat` ADD COLUMN `ref_msg_id` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `chat` ADD COLUMN `ref_text` TEXT");
        }
    };

    /** v3 -> v4：图库文件夹（media_cache 加 folder_id 列 + folder_cache 表） */
    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE `media_cache` ADD COLUMN `folderId` INTEGER");
            db.execSQL("CREATE TABLE IF NOT EXISTS `folder_cache` (" +
                    "`id` INTEGER NOT NULL, `name` TEXT, `ts` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))");
        }
    };

    /** v2 -> v3：新增应用名映射缓存表（包名→应用名） */
    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `app_name_cache` (" +
                    "`package_name` TEXT NOT NULL, `app_name` TEXT NOT NULL, " +
                    "PRIMARY KEY(`package_name`))");
        }
    };

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
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                                    MIGRATION_4_5, MIGRATION_5_6)
                            // 没有可用 Migration 时（极端情况）才落到破坏性重建
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}