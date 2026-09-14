package net.pocketnai.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 本地历史数据库。
 *
 * 只保存文本与文件索引，绝不保存 PNG 二进制，也不保存 Token（规划书 7 与第 10 节）。
 * schema 导出到 `app/schemas`，升级必须写显式迁移，不用破坏性迁移回避问题。
 */
@Database(
    entities = [
        GenerationEntity::class,
        GeneratedImageEntity::class,
        PromptFavoriteEntity::class,
        ReferenceImageEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class PocketNaiDatabase : RoomDatabase() {

    abstract fun generationDao(): GenerationDao

    abstract fun promptFavoriteDao(): PromptFavoriteDao

    companion object {
        private const val DATABASE_NAME = "pocketnai.db"

        /**
         * v1 → v2：新增质量标签档位列。
         *
         * 用 `ADD COLUMN` 而不是重建表。原因见 [GenerationEntity.qualityTags]：
         * `generated_images` 通过外键 ON DELETE CASCADE 引用 `generations`，
         * 一旦 DROP 或 RENAME 这张表，已生成的图片记录会被连带删除 —— 对用户来说
         * 就是历史凭空消失。增量加列没有这个风险。
         *
         * 回填规则：v1 只有布尔开关，打开时对应 Standard，关闭时对应 None。
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE generations ADD COLUMN qualityTags TEXT")
                db.execSQL(
                    """
                    UPDATE generations
                    SET qualityTags = CASE WHEN qualityTagsEnabled = 1 THEN 'STANDARD' ELSE 'NONE' END
                    """.trimIndent(),
                )
            }
        }

        /**
         * v2 → v3：新增收藏提示词的独立新表。
         *
         * 这次只 `CREATE TABLE`，不触碰 `generations` / `generated_images`，
         * 因此没有 v1→v2 那种"重建父表会级联删掉用户图片"的风险 ——
         * 但**新增表同样必须写出与 Room 期望完全一致的列定义**
         * （列名、类型、NOT NULL、主键），否则打开数据库时的 schema 校验会失败并抛出异常。
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `prompt_favorites` (
                        `id` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `target` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `lastUsedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * v3 → v4：新增参考图表，并给 `generations` 加一列生成模式。
         *
         * 两处都是纯加法：
         * - 新表 `reference_images` 用 `CREATE TABLE`，列定义必须与
         *   [ReferenceImageEntity] 完全一致（列名、类型、NOT NULL、主键），否则打开数据库时校验会失败；
         * - `generations` 只 `ADD COLUMN`，**不重建**（原因见 [MIGRATION_1_2]）。
         *   新列不带默认值、声明成可空，这是 Room 的默认值比对陷阱的规避方式；
         *   老记录靠一次 `UPDATE` 回填成 TXT2IMG，语义上它们确实都是纯文生图。
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE generations ADD COLUMN mode TEXT")
                db.execSQL("UPDATE generations SET mode = 'TXT2IMG' WHERE mode IS NULL")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reference_images` (
                        `id` TEXT NOT NULL,
                        `generationId` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `ordinal` INTEGER NOT NULL,
                        `relativePath` TEXT NOT NULL,
                        `width` INTEGER NOT NULL,
                        `height` INTEGER NOT NULL,
                        `byteSize` INTEGER NOT NULL,
                        `sha256` TEXT NOT NULL,
                        `strength` REAL,
                        `informationExtracted` REAL,
                        `secondaryStrength` REAL,
                        `directorKind` TEXT,
                        `vibeRelativePath` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`generationId`) REFERENCES `generations`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reference_images_generationId` " +
                        "ON `reference_images` (`generationId`)",
                )
            }
        }

        fun build(context: Context): PocketNaiDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                PocketNaiDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
