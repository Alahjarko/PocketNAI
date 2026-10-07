package net.pocketnai.ui.artistlab

import android.graphics.*
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import net.pocketnai.data.artistlab.ArtistLabStore
import net.pocketnai.data.files.GenerationFileStore
import net.pocketnai.data.local.*
import net.pocketnai.data.settings.GenerationDraftCodec
import net.pocketnai.domain.artistlab.*
import net.pocketnai.domain.model.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.random.Random

/** 仅按显式参数准备/移除布局样本；不访问账号、不调用 API，不混入日常测试。 */
class ArtistLabLayoutFixtureTest {
    @Test fun localLayoutFixture() = runBlocking {
        val mode = InstrumentationRegistry.getArguments().getString("artistLabFixture")
        assumeTrue(mode == "prepare" || mode == "cleanup")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = PocketNaiDatabase.build(context)
        val files = GenerationFileStore(context)
        val id = "artist-lab-layout-fixture-20261007"
        try {
            if (mode == "cleanup") {
                db.withTransaction {
                    db.openHelper.writableDatabase.execSQL("DELETE FROM artist_lab_runs WHERE id = ?", arrayOf(id))
                    db.openHelper.writableDatabase.execSQL("DELETE FROM artist_lab_mix_favorites WHERE createdAt = ?", arrayOf(-20261007L))
                    for (index in 0 until 100) db.generationDao().purgeGeneration("$id:$index")
                }
                for (index in 0 until 100) files.deleteGeneration("$id:$index")
                return@runBlocking
            }
            check(db.artistLabDao().run(id) == null) { "请先清理上次布局样本" }
            val store = ArtistLabStore(context, db)
            val catalog = store.catalog()
            val params = GenerationParams.defaultsFor(ModelCatalog.profileOf(ImageModel.V4_5_CURATED)).copy(
                prompt = "sfw, landscape, blue sky", seedMode = SeedMode.FIXED, baseSeed = 123456789)
            val config = ArtistLabConfig(GenerationDraftCodec.encode(GenerationDraft(params, params.prompt, params.negativePrompt)), 3, 10, 30, 100, catalog.second)
            val plan = ArtistLabPlanner.plan(catalog.first, config, Random(23))
            val now = System.currentTimeMillis()
            val rows = plan.mapIndexed { index, mix ->
                val generationId = "$id:$index"
                val relativePath = files.relativePathOf(generationId, 1)
                val bitmap = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                canvas.drawColor(Color.HSVToColor(floatArrayOf((index * 37 % 360).toFloat(), 0.35f, 0.45f)))
                paint.color = Color.rgb(242, 215, 170); canvas.drawCircle(175f, 80f, 28f, paint)
                paint.color = Color.rgb(30, 57, 59)
                val path = Path().apply { moveTo(0f, 245f); lineTo(80f, 140f); lineTo(240f, 280f); lineTo(240f, 320f); lineTo(0f, 320f); close() }
                canvas.drawPath(path, paint)
                paint.color = Color.WHITE; paint.textSize = 19f; canvas.drawText("LAYOUT SAMPLE ${index + 1}", 10f, 300f, paint)
                val file = files.resolve(relativePath); file.parentFile!!.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                db.generationDao().upsertGeneration(Mappers.toEntity(Generation(generationId, now, now, GenerationStatus.SUCCEEDED,
                    "离线布局样本", params.prompt, ArtistLabPlanner.params(params, mix), 1)))
                val imageId = "$generationId:image"
                db.generationDao().upsertImages(listOf(GeneratedImageEntity(imageId, generationId, 1, params.baseSeed,
                    relativePath, 240, 320, file.length(), net.pocketnai.core.Hashing.sha256(file), null, now, null)))
                if (index < 2) {
                    db.favoriteImageDao().add(FavoriteImageEntity(imageId, now))
                    db.artistLabDao().saveMix(ArtistMixFavoriteEntity(mix.prompt, -20261007L))
                }
                ArtistLabDrawEntity(generationId, id, index, store.json.encodeToString(mix), "SUCCEEDED", generationId, imageId)
            }
            db.artistLabDao().create(ArtistLabRunEntity(id, now, store.json.encodeToString(config), "COMPLETED", "离线布局样本，不是真实生成"), rows)
        } finally { db.close() }
    }
}
