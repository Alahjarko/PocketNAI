package net.pocketnai.domain.auth

/**
 * Access Key 派生的抽象。
 *
 * 存在的意义是让上层（`ConnectViewModel`）可以被测试：单元测试注入一个返回固定值的
 * 假实现，就不必在每次登录流程测试里真的跑一遍 Argon2。
 * 真实算法本身由 [NovelAiAccessKeyDeriver] 与它的固定向量测试负责。
 */
fun interface AccessKeyDeriver {
    /**
     * 由邮箱与密码派生 64 字符的 Access Key。
     *
     * 实现必须离开主线程执行：Argon2 会占用可感知的 CPU 与内存。
     * 调用方负责在失败时清空密码与返回值的引用。
     */
    suspend fun deriveAccessKey(email: String, password: String): String
}
