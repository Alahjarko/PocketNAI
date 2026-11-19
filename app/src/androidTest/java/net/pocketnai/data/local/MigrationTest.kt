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

    @Test fun migrate9To15KeepsHistoryFavoritesLabReferencesAndDrafts() {
        val name = "full-migration-test"
        helper.createDatabase(name, 9).use { db ->
            db.execSQL("""INSERT INTO generations (id,createdAt,updatedAt,status,title,promptTemplate,mode,prompt,negativePrompt,modelApiId,width,height,sampleCount,steps,guidance,cfgRescale,sampler,noiseSchedule,seedMode,baseSeed,qualityTags,qualityTagsEnabled,ucPresetIndex,modelConfigVersion,requestSnapshotVersion,charactersJson)
                VALUES ('old',1,1,'SUCCEEDED','old title','sfw','TXT2IMG','sfw','','nai-diffusion-4-5-curated',832,1216,1,23,7.0,0.0,'k_euler_ancestral','karras','FIXED',42,'NONE',0,0,'v1',3,'[]')""")
            db.execSQL("""INSERT INTO generated_images (id,generationId,ordinal,seed,relativePath,width,height,byteSize,sha256,metadataJson,createdAt,exportedUri)
                VALUES ('old-image','old',1,42,'generations/old/0001.png',832,1216,1024,'sha',NULL,1,NULL)""")
            db.execSQL("INSERT INTO favorite_images VALUES ('old-image', 1)")
            db.execSQL("INSERT INTO chat_conversations VALUES ('old-chat','original',2,'[]')")
            db.execSQL("INSERT INTO reference_images (id,generationId,role,ordinal,relativePath,width,height,byteSize,sha256,createdAt) VALUES ('ref','old','IMG2IMG',0,'references/old.png',832,1216,100,'ref-sha',1)")
        }
        helper.runMigrationsAndValidate(name, 10, true, PocketNaiDatabase.MIGRATION_9_10).use { db ->
            for (table in listOf("generations", "generated_images", "favorite_images", "chat_conversations")) {
                db.query("SELECT COUNT(*) FROM $table").use { assertThat(it.moveToFirst()).isTrue(); assertThat(it.getInt(0)).isEqualTo(1) }
            }
            db.execSQL("INSERT INTO artist_lab_runs VALUES ('run',3,'{}','PAUSED','')")
            db.execSQL("INSERT INTO artist_lab_draws VALUES ('draw','run',0,'{}','SUCCEEDED','old','old-image','')")
            db.execSQL("INSERT INTO artist_lab_mix_favorites VALUES ('1.0::artist: test::',3)")
        }
        helper.runMigrationsAndValidate(name, 13, true,
            PocketNaiDatabase.MIGRATION_10_11, PocketNaiDatabase.MIGRATION_11_12, PocketNaiDatabase.MIGRATION_12_13).use { db ->
            db.execSQL("UPDATE generations SET openAiParamsJson=?,modelApiId='gpt-image-2.6-flare' WHERE id='old'",
                arrayOf("""{"params":{"prompt":"fixture","model":"gpt-image-2.6-flare"},"usage":{"textInput":10,"imageInput":0,"imageOutput":100}}"""))
        }
        helper.runMigrationsAndValidate(name, 15, true, PocketNaiDatabase.MIGRATION_13_14, PocketNaiDatabase.MIGRATION_14_15).use { db ->
            db.query("SELECT searchPrompt FROM generations WHERE id='old'").use {
                assertThat(it.moveToFirst()).isTrue(); assertThat(it.getString(0)).isEqualTo("sfw")
            }
            db.query(GalleryQueries.build(net.pocketnai.domain.model.GalleryFilter(query = "SFW", modelApiIds = setOf("gpt-image-2.6-flare")), 1)).use {
                assertThat(it.count).isEqualTo(1)
            }
            db.query(GalleryQueries.build(net.pocketnai.domain.model.GalleryFilter(query = "%' OR 1=1--"), 1)).use {
                assertThat(it.count).isEqualTo(0)
            }
            db.execSQL("INSERT INTO chat_drafts VALUES ('draft','未发送',1,2,'[]')")
            db.query("SELECT text,selectionEnd FROM chat_drafts WHERE conversationId='draft'").use {
                assertThat(it.moveToFirst()).isTrue(); assertThat(it.getString(0)).isEqualTo("未发送"); assertThat(it.getInt(1)).isEqualTo(2)
            }
            db.query("SELECT title,charactersJson,useCharacterCoordinates,openAiParamsJson,generationDurationMillis,effort FROM generations WHERE id='old'").use {
                assertThat(it.moveToFirst()).isTrue(); assertThat(it.getString(0)).isEqualTo("old title"); assertThat(it.getString(1)).isEqualTo("[]"); assertThat(it.isNull(2)).isTrue()
                assertThat(it.getString(3)).contains("imageOutput")
                assertThat(it.isNull(5)).isTrue()
                assertThat(it.isNull(4)).isTrue()
            }
            db.query("SELECT sourceImageId,relativePath FROM reference_images WHERE id='ref'").use {
                assertThat(it.moveToFirst()).isTrue(); assertThat(it.isNull(0)).isTrue(); assertThat(it.getString(1)).isEqualTo("references/old.png")
            }
            db.execSQL("UPDATE reference_images SET sourceImageId='old-image' WHERE id='ref'")
            db.query("SELECT COUNT(*) FROM generated_images").use { assertThat(it.moveToFirst()).isTrue(); assertThat(it.getInt(0)).isEqualTo(1) }
            db.execSQL("UPDATE generations SET generationDurationMillis=12345 WHERE id='old'")
            db.query("SELECT generationDurationMillis FROM generations WHERE id='old'").use {
                assertThat(it.moveToFirst()).isTrue(); assertThat(it.getLong(0)).isEqualTo(12345)
            }
            db.query("SELECT snapshotJson FROM openai_spending WHERE generationId='old'").use {
                assertThat(it.moveToFirst()).isTrue(); assertThat(it.getString(0)).contains("imageOutput")
            }
            repeat(480) { index -> db.execSQL("""INSERT INTO generated_images
                (id,generationId,ordinal,seed,relativePath,width,height,byteSize,sha256,metadataJson,createdAt,exportedUri)
                VALUES (?,'old',?,42,'offline.png',832,1216,1,'offline',NULL,1,NULL)""", arrayOf("page-$index", index + 2)) }
            val all = net.pocketnai.domain.model.GalleryFilter(modelApiIds = setOf("gpt-image-2.6-flare", "gpt-image-2.5-sunburst"))
            db.query(GalleryQueries.build(all, 120)).use { assertThat(it.count).isEqualTo(120) }
            db.query(GalleryQueries.build(all, Int.MAX_VALUE, idsOnly = true)).use { assertThat(it.count).isEqualTo(481) }
            db.query(GalleryQueries.build(all.copy(favoritesOnly = true), 120)).use { assertThat(it.count).isEqualTo(1) }
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL("DELETE FROM generated_images WHERE id = 'old-image'")
            db.query("SELECT imageId FROM artist_lab_draws").use { assertThat(it.moveToFirst()).isTrue(); assertThat(it.isNull(0)).isTrue() }
            db.query("SELECT COUNT(*) FROM artist_lab_mix_favorites").use { assertThat(it.moveToFirst()).isTrue(); assertThat(it.getInt(0)).isEqualTo(1) }
            db.execSQL("DELETE FROM generations WHERE id='old'")
            db.query("SELECT COUNT(*) FROM openai_spending WHERE generationId='old'").use {
                assertThat(it.moveToFirst()).isTrue(); assertThat(it.getInt(0)).isEqualTo(1)
            }
        }
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PocketNaiDatabase::class.java,
    )
}
