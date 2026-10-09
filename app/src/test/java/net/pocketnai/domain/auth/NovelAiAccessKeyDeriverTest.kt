package net.pocketnai.domain.auth

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Aedial / argon2-cffi 独立参考实现的固定向量，输入全部虚构。 */
class NovelAiAccessKeyDeriverTest {
    private val deriver = NovelAiAccessKeyDeriver()

    @Test fun `普通账号派生结果与独立参考实现一致`() {
        assertThat(deriver.derive("test@example.com", "correct horse battery staple"))
            .isEqualTo("-rRTA54TEApfxWczoIupaAfBQ7C4frlPnZ5roUe29THxdTHwDm-TZvHckOQObcHu")
    }

    @Test fun `含emoji的密码按Unicode码位派生并与参考实现一致`() {
        assertThat(deriver.derive("emoji@example.com", "\uD83D\uDD11secret123"))
            .isEqualTo("7cr6rBuBX9iGwxuSjBf5_DqV1_eEUISjpTZcmjJxYbvxrzc2uVbgi3zOVHMF1XZE")
    }
}
