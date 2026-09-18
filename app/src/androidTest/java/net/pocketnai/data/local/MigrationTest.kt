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

    /**
     * v5 → v6：新增收藏图片表。
     *
     * 这个迁移只 `CREATE TABLE`，风险比 v4→v5 小得多，但仍然要在这里证明三件事：
     * 1. 既有历史与图片原样还在（新表没碰到它们）；
     * 2. 新表结构能被 Room 的校验接受（外键的 `onDelete` 写错就会在这里抛出来）；
     * 3. `ON DELETE CASCADE` 真的生效 —— 图片被删时收藏行必须跟着走，
     *    否则会留下一批指向不存在图片的收藏，界面上表现为"收藏里有几张打不开"。
     */
    @Test
    fun migrate5To6AddsFavoriteImagesWithCascade() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL(
                """
                INSERT INTO generations (
                    id, createdAt, updatedAt, status, title, promptTemplate, mode,
                    prompt, negativePrompt, modelApiId, width, height, sampleCount,
                    steps, guidance, cfgRescale, sampler, noiseSchedule, seedMode,
                    baseSeed, qualityTags, qualityTagsEnabled, ucPresetIndex,
                    modelConfigVersion, requestSnapshotVersion,
                    outputWidth, outputHeight
                ) VALUES (
                    'gen-1', 1, 1, 'SUCCEEDED', 'title', '1girl', 'TXT2IMG',
                    '1girl', '', 'nai-diffusion-4-5-curated', 832, 1216, 1,
                    23, 7.0, 0.0, 'k_euler_ancestral', 'karras', 'FIXED',
                    42, 'STANDARD', 1, 0, 'v1', 3, NULL, NULL
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

        helper.runMigrationsAndValidate(TEST_DB, 6, true, PocketNaiDatabase.MIGRATION_5_6)
            .use { db ->
                db.query("SELECT COUNT(*) FROM generations").use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getInt(0)).isEqualTo(1)
                }
                db.query("SELECT COUNT(*) FROM generated_images").use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getInt(0)).isEqualTo(1)
                }

                db.execSQL("INSERT INTO favorite_images (imageId, createdAt) VALUES ('img-1', 1)")
                db.query("SELECT COUNT(*) FROM favorite_images").use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getInt(0)).isEqualTo(1)
                }

                // SQLite 默认**不**启用外键约束，而 `MigrationTestHelper` 给的是一个裸数据库。
                // 应用运行时由 Room 生成的实现在 `onOpen` 里执行 `PRAGMA foreign_keys = ON`
                // （已核对 `PocketNaiDatabase_Impl`），所以这里要自己打开，
                // 否则测的不是"级联没生效"而是"开关没开"，断言会误报。
                db.execSQL("PRAGMA foreign_keys = ON")

                // 删掉图片，收藏行必须被级联清掉 —— 这条断言真正验证的是
                // 迁移里写的外键动作是 CASCADE 而不是 NO ACTION。
                db.execSQL("DELETE FROM generated_images WHERE id = 'img-1'")
                db.query("SELECT COUNT(*) FROM favorite_images").use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getInt(0)).isEqualTo(0)
                }
            }
    }

    @Test
    fun migrate6To7AddsAnlasTransactionsTable() {
        helper.createDatabase(TEST_DB, 6).close()

        helper.runMigrationsAndValidate(TEST_DB, 7, true, PocketNaiDatabase.MIGRATION_6_7)
            .use { db ->
                db.execSQL(
                    """
                    INSERT INTO anlas_transactions (
                        id, accountFingerprint, generationId, actionType,
                        anlasSpent, v5AllowanceDelta, balanceAfter, description, createdAt
                    ) VALUES (
                        'tx-1', 'fp-1', 'gen-1', 'GENERATE',
                        0, NULL, 1000, 'Test Tx', 12345
                    )
                    """.trimIndent(),
                )
                db.query("SELECT COUNT(*) FROM anlas_transactions").use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getInt(0)).isEqualTo(1)
                }
            }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
