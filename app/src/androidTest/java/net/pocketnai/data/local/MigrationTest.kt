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

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PocketNaiDatabase::class.java,
    )

    @Test
    fun migrate4To5KeepsHistoryAndAddsOutputColumns() {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL(
                """
                INSERT INTO generations (
                    id, createdAt, updatedAt, status, title, promptTemplate, mode,
                    prompt, negativePrompt, modelApiId, width, height, sampleCount,
                    steps, guidance, cfgRescale, sampler, noiseSchedule, seedMode,
                    baseSeed, qualityTags, qualityTagsEnabled, ucPresetIndex,
                    modelConfigVersion, requestSnapshotVersion
                ) VALUES (
                    'gen-1', 1, 1, 'SUCCEEDED', 'title', '1girl', 'TXT2IMG',
                    '1girl', '', 'nai-diffusion-4-5-curated', 832, 1216, 1,
                    23, 7.0, 0.0, 'k_euler_ancestral', 'karras', 'FIXED',
                    42, 'STANDARD', 1, 0, 'v1', 3
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO generated_images (
                    id, generationId, ordinal, seed, relativePath, width, height,
                    byteSize, sha256, metadataJson, createdAt, exportedUri
                ) VALUES (
                    'img-1', 'gen-1', 1, 42, 'generations/gen-1/0001.png', 832, 1216,
                    1024, 'sha', NULL, 1, NULL
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 5, true, PocketNaiDatabase.MIGRATION_4_5)
            .use { db ->
                // 历史必须原样还在。
                db.query("SELECT width, height, outputWidth, outputHeight FROM generations").use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getInt(0)).isEqualTo(832)
                    assertThat(cursor.getInt(1)).isEqualTo(1216)
                    // 新列对老记录是 NULL —— 语义就是"没裁切过"，不是 0。
                    assertThat(cursor.isNull(2)).isTrue()
                    assertThat(cursor.isNull(3)).isTrue()
                }
                db.query("SELECT COUNT(*) FROM generated_images").use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getInt(0)).isEqualTo(1)
                }
            }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
