package net.pocketnai.core

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.time.LocalDate

class ArtistCatalogTest {
    private val small = (1..12).joinToString("\n", postfix = "\n") { "artist: modern $it" }.toByteArray()

    @Test fun `精选小词库可加载且能抽十位不同画师`() {
        val tags = ArtistCatalog.validate(small, 12, Hashing.sha256(small))
        assertThat(tags).hasSize(12)
        assertThat(tags.take(10).distinct()).hasSize(10)
    }

    @Test fun `元数据数量不符或少于十位不能进入实验`() {
        assertThrows(IllegalStateException::class.java) { ArtistCatalog.validate(small, 1000, Hashing.sha256(small)) }
        assertThrows(IllegalStateException::class.java) { ArtistCatalog.validate(small, 9, Hashing.sha256(small)) }
    }

    @Test fun `重复标签权重注入或损坏文件不能加载`() {
        val duplicated = (1..12).joinToString("\n") { "artist: repeated" }.toByteArray()
        assertThrows(IllegalStateException::class.java) { ArtistCatalog.validate(duplicated, 12, Hashing.sha256(duplicated)) }
        val injected = small.toString(Charsets.UTF_8).replace("modern 1\n", "0.5::modern 1::\n").toByteArray()
        assertThrows(IllegalStateException::class.java) { ArtistCatalog.validate(injected, 12, Hashing.sha256(injected)) }
        assertThrows(IllegalStateException::class.java) { ArtistCatalog.validate(small, 12, "damaged") }
    }

    @Test fun `实际打包名单与审核清单一一对应且每项满足作品门槛`() {
        val assets = listOf(File("src/main/assets/artist-lab"), File("app/src/main/assets/artist-lab")).first { it.isDirectory }
        val source = Json.parseToJsonElement(File(assets, "source.json").readText()).jsonObject
        val reviewBytes = File(assets, "curated-artists.json").readBytes()
        assertThat(Hashing.sha256(reviewBytes)).isEqualTo(source.getValue("review_sha256").jsonPrimitive.content)
        val review = Json.parseToJsonElement(reviewBytes.toString(Charsets.UTF_8)).jsonObject
        val accepted = review.getValue("artists").jsonArray.map { it.jsonObject }
        val bytes = File(assets, "artists.txt").readBytes()
        val tags = ArtistCatalog.validate(bytes, source.getValue("count").jsonPrimitive.int, source.getValue("pool_sha256").jsonPrimitive.content)
        assertThat(tags.size).isAtLeast(100)
        assertThat(accepted.map { it.getValue("danbooru_tag").jsonPrimitive.content })
            .containsAtLeast("mika_pikazo", "modare", "torino_aqua")
        assertThat(tags).containsExactlyElementsIn(accepted.map {
            "artist: " + it.getValue("danbooru_tag").jsonPrimitive.content.replace('_', ' ')
        }).inOrder()
        val activeSince = LocalDate.parse(review.getValue("review_date").jsonPrimitive.content).minusDays(365)
        accepted.forEach { artist ->
            assertThat(artist.getValue("danbooru_category").jsonPrimitive.int).isEqualTo(1)
            assertThat(artist.getValue("danbooru_post_count").jsonPrimitive.int).isAtLeast(50)
            assertThat(LocalDate.parse(artist.getValue("latest_pixiv_work_date").jsonPrimitive.content).isBefore(activeSince)).isFalse()
            assertThat(artist.getValue("identity_evidence").jsonPrimitive.content).isNotEmpty()
            assertThat(artist.getValue("review_status").jsonPrimitive.content).isEqualTo("accepted")
            val sfw = artist.getValue("sfw_review").jsonObject
            assertThat(sfw.getValue("sample_count").jsonPrimitive.int).isGreaterThan(0)
            assertThat(sfw.getValue("all_x_restrict_zero").jsonPrimitive.boolean).isTrue()
            assertThat(sfw.getValue("adult_profile_marker").jsonPrimitive.boolean).isFalse()
            assertThat(artist.getValue("visual_review").jsonObject.getValue("sample_urls").jsonArray).isNotEmpty()
        }
    }
}
