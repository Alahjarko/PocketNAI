package net.pocketnai.ui.state

/**
 * 画廊当前列表的顺序快照：详情页左右滑动切换图片时，用它决定"下一张是谁"。
 *
 * 故意做成**点击时写入的快照**而不是实时流：详情页打开期间画廊可能因新生成、
 * 删除、改筛选而变化，滑动顺序若跟着变，用户会觉得"图自己跳走了"。
 * 它只是界面会话状态，不落盘、不进数据库。
 */
class GalleryOrderSnapshot {

    @Volatile
    private var ids: List<String> = emptyList()

    /** 画廊在用户点进详情页时调用，记录"这次浏览的顺序"。 */
    fun publish(imageIds: List<String>) {
        ids = imageIds
    }

    /**
     * 以 [currentId] 为当前页的顺序。
     *
     * [currentId] 不在快照里（例如快照为空、或这张图已被筛掉）时退化成只含它自己，
     * 详情页就回到"单张、不能滑"的旧行为，而不是滑到一堆不相干的图。
     */
    fun orderAround(currentId: String): List<String> {
        val snapshot = ids
        return if (currentId in snapshot) snapshot else listOf(currentId)
    }
}
