package net.pocketnai.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 数据库迁移测试（仪器化）。
 *
 * 迁移是**唯一**会毁掉用户历史代码的地方：`generations` 被 `generated_images` 级联引用，
 * 一旦有人图省事写"建新表 → 拷数据 → 删旧表"，删旧表那一步就会连图片一起清掉。
 * 因此每次加迁移都要在这里证明两件事：
 *
 * 1. 旧版本的数据还在；
 * 2. 迁移后的表结构与 Room 的期望完全一致（`runMigrationsAndValidate` 会校验，
 *    列名/类型/默认值差一点都会抛异常，不会等用户装机才发现）。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @Test fun migrate8To9AddsChatAndKeepsImageHistory() {
        helper.createDatabase("chat-migration-test", 8).use { db ->
            db.execSQL("""INSERT INTO generations (id,createdAt,updatedAt,status,title,promptTemplate,mode,prompt,negativePrompt,modelApiId,width,height,sampleCount,steps,guidance,cfgRescale,sampler,noiseSchedule,seedMode,baseSeed,qualityTags,qualityTagsEnabled,ucPresetIndex,modelConfigVersion,requestSnapshotVersion,charactersJson)
                VALUES ('old',1,1,'SUCCEEDED','old title','sfw','TXT2IMG','sfw','','nai-diffusion-4-5-curated',832,1216,1,23,7.0,0.0,'k_euler_ancestral','karras','FIXED',42,'NONE',0,0,'v1',3,'[]')""")
            db.execSQL("""INSERT INTO generated_images (id,generationId,ordinal,seed,relativePath,width,height,byteSize,sha256,metadataJson,createdAt,exportedUri)
                VALUES ('old-image','old',1,42,'generations/old/0001.png',832,1216,1024,'sha',NULL,1,NULL)""")
        }
        helper.runMigrationsAndValidate("chat-migration-test", 9, true, PocketNaiDatabase.MIGRATION_8_9).use { db ->
            db.query("SELECT title,charactersJson FROM generations WHERE id='old'").use {
                assertThat(it.moveToFirst()).isTrue(); assertThat(it.getString(0)).isEqualTo("old title"); assertThat(it.getString(1)).isEqualTo("[]")
            }
            db.query("SELECT COUNT(*) FROM generated_images").use { assertThat(it.moveToFirst()).isTrue(); assertThat(it.getInt(0)).isEqualTo(1) }
            db.execSQL("INSERT INTO chat_conversations VALUES ('chat','title',2,'[]')")
            db.query("SELECT entriesJson FROM chat_conversations").use { assertThat(it.moveToFirst()).isTrue(); assertThat(it.getString(0)).isEqualTo("[]") }
        }
    }

    @Test fun migrate9To10KeepsHistoryFavoritesChatAndAddsLab() {
        val name = "artist-lab-migration-test"
        helper.createDatabase(name, 9).use { db ->
            db.execSQL("""INSERT INTO generations (id,createdAt,updatedAt,status,title,promptTemplate,mode,prompt,negativePrompt,modelApiId,width,height,sampleCount,steps,guidance,cfgRescale,sampler,noiseSchedule,seedMode,baseSeed,qualityTags,qualityTagsEnabled,ucPresetIndex,modelConfigVersion,requestSnapshotVersion,charactersJson)
                VALUES ('old',1,1,'SUCCEEDED','old title','sfw','TXT2IMG','sfw','','nai-diffusion-4-5-curated',832,1216,1,23,7.0,0.0,'k_euler_ancestral','karras','FIXED',42,'NONE',0,0,'v1',3,'[]')""")
            db.execSQL("""INSERT INTO generated_images (id,generationId,ordinal,seed,relativePath,width,height,byteSize,sha256,metadataJson,createdAt,exportedUri)
                VALUES ('old-image','old',1,42,'generations/old/0001.png',832,1216,1024,'sha',NULL,1,NULL)""")
            db.execSQL("INSERT INTO favorite_images VALUES ('old-image', 1)")
            db.execSQL("INSERT INTO chat_conversations VALUES ('old-chat','original',2,'[]')")
        }
        helper.runMigrationsAndValidate(name, 10, true, PocketNaiDatabase.MIGRATION_9_10).use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")
            for (table in listOf("generations", "generated_images", "favorite_images", "chat_conversations")) {
                db.query("SELECT COUNT(*) FROM $table").use { assertThat(it.moveToFirst()).isTrue(); assertThat(it.getInt(0)).isEqualTo(1) }
            }
            db.execSQL("INSERT INTO artist_lab_runs VALUES ('run',3,'{}','PAUSED','')")
            db.execSQL("INSERT INTO artist_lab_draws VALUES ('draw','run',0,'{}','SUCCEEDED','old','old-image','')")
            db.execSQL("INSERT INTO artist_lab_mix_favorites VALUES ('1.0::artist: test::',3)")
            db.execSQL("DELETE FROM generated_images WHERE id = 'old-image'")
            db.query("SELECT imageId FROM artist_lab_draws").use { assertThat(it.moveToFirst()).isTrue(); assertThat(it.isNull(0)).isTrue() }
            db.query("SELECT COUNT(*) FROM artist_lab_mix_favorites").use { assertThat(it.moveToFirst()).isTrue(); assertThat(it.getInt(0)).isEqualTo(1) }
            db.query("SELECT COUNT(*) FROM generations").use { assertThat(it.moveToFirst()).isTrue(); assertThat(it.getInt(0)).isEqualTo(1) }
        }
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PocketNaiDatabase::class.java,
    )

    /**
     * v5 → v6：新增收藏图片表。
     *
     * 这个迁移只 `CREATE TABLE`，风险比 v4→v5 小得多，但仍然要在这里证明三件事：
     * 1. 既有历史与图片原样还在（新表没碰到它们）；
     * 2. 新表结构能被 Room 的校验接受（外键的 `onDelete` 写错就会在这里抛出来）；
     * 3. `ON DELETE CASCADE` 真的生效 —— 图片被删时收藏行必须跟着走，
     *    否则会留下一批指向不存在图片的收藏，界面上表现为"收藏里有几张打不开"。
     */

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
