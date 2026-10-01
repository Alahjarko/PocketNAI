package net.pocketnai.ui.generate

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Outcome
import net.pocketnai.data.files.GenerationFileStore
import net.pocketnai.data.local.PocketNaiDatabase
import net.pocketnai.data.network.NovelAiApi
import net.pocketnai.data.repo.AccountBalanceRepository
import net.pocketnai.data.repo.GenerationRepository
import net.pocketnai.data.security.CredentialStore
import net.pocketnai.data.security.StoredCredential
import net.pocketnai.data.settings.GenerationDraftPreferences
import net.pocketnai.data.settings.SettingsStore
import net.pocketnai.domain.image.ImageTransform
import net.pocketnai.domain.image.LiveReferencePathsProvider
import net.pocketnai.domain.image.PreparedReference
import net.pocketnai.domain.image.ReferenceImageImporter
import net.pocketnai.domain.image.ReferenceSource
import net.pocketnai.domain.metadata.ImageMetadataInspector
import net.pocketnai.domain.metadata.MetadataProbeResult
import net.pocketnai.domain.metadata.NovelAiMetadataParser
import net.pocketnai.domain.metadata.PngTextChunks
import net.pocketnai.domain.model.GenerationMode
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.prompt.TagSuggestionSource
import net.pocketnai.ui.sharedImageUri
import net.pocketnai.ui.state.GenerationDraftStore
import net.pocketnai.ui.state.GenerationPreviewStore
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 独立命名的测试首选项/缓存目录、内存 Room、假 API：不接触用户草稿、凭据或网络。 */
@RunWith(AndroidJUnit4::class)
class GenerateFeedbackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = object : android.content.ContextWrapper(instrumentation.targetContext) {
        override fun getApplicationContext(): android.content.Context = this
        override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
            super.getSharedPreferences("feedback_test_$name", mode)
        override fun getFilesDir(): File = File(super.getCacheDir(), "feedback_test_files").also { it.mkdirs() }
    }
    private val database = Room.inMemoryDatabaseBuilder(context, PocketNaiDatabase::class.java).build()
    private val store = ViewModelStore()
    private var imports = 0
    private val api = object : NovelAiApi {
        override suspend fun fetchAccountStatus(token: String) = Outcome.Failure(AppError.of(ErrorCode.UNKNOWN))
        override suspend fun fetchSubscriptionBalance(token: String) = Outcome.Failure(AppError.of(ErrorCode.UNKNOWN))
        override suspend fun generateImage(token: String, payload: JsonObject, destinationZip: File): Outcome<Unit> =
            error("不得生成")
        override fun generateImageStream(token: String, payload: JsonObject) = emptyFlow<net.pocketnai.data.network.GenerationStreamEvent>()
        override suspend fun suggestTags(token: String, model: ImageModel, prompt: String) = Outcome.Success(emptyList<String>())
        override suspend fun encodeVibe(token: String, model: ImageModel, imageBase64: String, informationExtracted: Double): Outcome<ByteArray> = error("不得编码")
        override suspend fun upscaleImage(token: String, imageBase64: String, destinationFile: File): Outcome<Unit> = error("不得超分")
    }
    private val credentials = object : CredentialStore {
        override fun hasCredential() = false
        override fun load(): StoredCredential? = null
        override fun save(credential: StoredCredential) = Unit
        override fun clear() = Unit
        override fun hint(): net.pocketnai.data.security.CredentialHint? = null
    }
    private val metadata = requireNotNull(NovelAiMetadataParser.parse(listOf(
        PngTextChunks.TextChunk("Software", "NovelAI"),
        PngTextChunks.TextChunk("Comment", """{"prompt":"blue hair","uc":"hat","width":1024,"height":1024}"""),
    ), kotlinx.serialization.json.Json { ignoreUnknownKeys = true }))

    private fun viewModel(): GenerateViewModel {
        val prefs = GenerationDraftPreferences(context).also { it.clear() }
        lateinit var vm: GenerateViewModel
        instrumentation.runOnMainSync {
            vm = GenerateViewModel(
                GenerationRepository(api, credentials, database.generationDao(), GenerationFileStore(context),
                    liveReferencePaths = LiveReferencePathsProvider { emptySet() }),
                GenerationDraftStore(), GenerationPreviewStore(), prefs,
                TagSuggestionSource { query, _ -> delay(50); (1..30).map { "$query $it" } },
                object : ReferenceImageImporter {
                    override suspend fun import(source: ReferenceSource, transform: ImageTransform?): Outcome<PreparedReference> {
                        imports++
                        return Outcome.Success(PreparedReference("references/test.png", 1024, 1024, 10, "test"))
                    }
                },
                object : ImageMetadataInspector {
                    override suspend fun inspect(source: ReferenceSource) = MetadataProbeResult.Found(metadata)
                },
                AccountBalanceRepository(api, credentials), SettingsStore(context),
            )
            store.put("feedback", vm)
        }
        return vm
    }

    private fun waitUntil(check: () -> Boolean) = runBlocking {
        withTimeout(4000) { while (!check()) delay(10) }
    }

    @After fun cleanUp() {
        instrumentation.runOnMainSync { store.clear() }
        database.close()
    }

    @Test fun `读取参数不导入参考图且不会生成`() {
        val vm = viewModel()
        vm.onReferencePicked(ReferenceSource.PickedUri("content://test/image"))
        waitUntil { vm.state.value.metadataCandidate != null }
        assertThat(imports).isEqualTo(0)
        vm.confirmMetadataImport()
        assertThat(vm.state.value.promptTemplate).isEqualTo("blue hair")
        assertThat(vm.state.value.referenceSource).isNull()
        assertThat(vm.state.value.mode).isEqualTo(GenerationMode.TXT2IMG)
        assertThat(imports).isEqualTo(0)
        assertThat(vm.state.value.inFlight).isFalse()
    }

    @Test fun `选择仅作参考图才归一化图片且不覆盖提示词`() {
        val vm = viewModel()
        vm.onPromptChange("original")
        vm.onReferencePicked(ReferenceSource.PickedUri("content://test/image"))
        waitUntil { vm.state.value.metadataCandidate != null }
        vm.dismissMetadataImport()
        waitUntil { vm.state.value.referenceSource != null }
        assertThat(imports).isEqualTo(1)
        assertThat(vm.state.value.promptTemplate).isEqualTo("original")
    }

    @Test fun `取消参数对话框不挂参考图`() {
        val vm = viewModel()
        vm.onReferencePicked(ReferenceSource.PickedUri("content://test/image"))
        waitUntil { vm.state.value.metadataCandidate != null }
        vm.cancelMetadataImport()
        assertThat(vm.state.value.referenceSource).isNull()
        assertThat(imports).isEqualTo(0)
    }

    @Test fun `角色补全返回20条且过时输入框不能清掉当前查询`() {
        val vm = viewModel()
        vm.onSuggestionFragmentChange("blu", "base")
        vm.onSuggestionFragmentChange("red", "character:one")
        vm.onSuggestionFragmentChange("", "base")
        waitUntil { vm.state.value.suggestionTarget == "character:one" }
        assertThat(vm.state.value.suggestionQuery).isEqualTo("red")
        assertThat(vm.state.value.suggestions).hasSize(20)
        assertThat(vm.state.value.suggestions.first()).isEqualTo("red 1")
    }

    @Test fun `相册分享仅接收单张图片内容URI`() {
        val uri = Uri.parse("content://media/external/images/media/1")
        assertThat(sharedImageUri(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri))).isEqualTo(uri)
        assertThat(sharedImageUri(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri))).isNull()
        assertThat(sharedImageUri(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///private/file")))).isNull()
        assertThat(sharedImageUri(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, "wrong type"))).isNull()
    }
}
