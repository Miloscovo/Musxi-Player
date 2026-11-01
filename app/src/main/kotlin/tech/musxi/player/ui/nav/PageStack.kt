package tech.musxi.player.ui.nav

/**
 * The pages of one tab, bottom to top, as plain string keys. The first key is the tab's root page
 * and is never removed. Which composable a key stands for is the caller's business, so the model
 * has no Compose or Android types and can be tested on the JVM.
 *
 * Values are immutable: every operation returns a new stack, or this same instance when the
 * request breaks a rule (see [canPush]). That keeps a refused push from needing an error path.
 *
 * Keys are unique inside one stack. A key stands for one page instance and its saved state (scroll
 * position), so a second page with the same key could not be told apart from the first.
 *
 * @property maxDepth The most pages the stack may hold, root included. A tab whose design has no
 * second level can pass 1 and never push.
 */
class PageStack private constructor(val keys: List<String>, val maxDepth: Int) {
    init {
        require(maxDepth >= 1) { "maxDepth must be at least 1, was $maxDepth" }
    }

    /** Number of pages, root included. At least 1. */
    val depth: Int get() = keys.size

    val root: String get() = keys.first()

    val top: String get() = keys.last()

    /** The page directly under [top], or null at the root. It is the page a back gesture uncovers. */
    val below: String? get() = if (keys.size > 1) keys[keys.lastIndex - 1] else null

    val isRoot: Boolean get() = keys.size == 1

    /** True when [key] is not on the stack yet and there is room for it. */
    fun canPush(key: String): Boolean = keys.size < maxDepth && key !in keys

    /** The stack with [key] on top, or this stack when [canPush] says no. */
    fun push(key: String): PageStack = if (canPush(key)) PageStack(keys + key, maxDepth) else this

    /** The stack without its top page, or this stack at the root. */
    fun pop(): PageStack = if (keys.size > 1) PageStack(keys.dropLast(1), maxDepth) else this

    /** Only the root page. */
    fun popToRoot(): PageStack = if (keys.size > 1) PageStack(listOf(root), maxDepth) else this

    /**
     * Root and top page only. Back to the root plays one transition, the top page leaving to reveal
     * the root, so the pages in between must already be gone when that transition starts.
     */
    fun withoutMiddle(): PageStack = if (keys.size > 2) PageStack(listOf(root, top), maxDepth) else this

    /** The keys in the order [restore] reads them back; a plain list fits `rememberSaveable`. */
    fun toSaved(): List<String> = keys.toList()

    override fun equals(other: Any?): Boolean =
        other is PageStack && other.maxDepth == maxDepth && other.keys == keys

    override fun hashCode(): Int = 31 * maxDepth + keys.hashCode()

    override fun toString(): String = "PageStack(${keys.joinToString(" > ")}, max=$maxDepth)"

    companion object {
        /** A stack holding only [root]. */
        fun of(root: String, maxDepth: Int = DefaultMaxDepth): PageStack = PageStack(listOf(root), maxDepth)

        /**
         * Reads back what [toSaved] wrote. Saved data that does not start with [root] (the tab's
         * root changed between versions) yields a fresh stack; repeated keys are dropped and
         * anything above [maxDepth] is cut, so a stale save can never break the invariants.
         */
        fun restore(saved: List<String>?, root: String, maxDepth: Int = DefaultMaxDepth): PageStack {
            if (saved.isNullOrEmpty() || saved.first() != root) return of(root, maxDepth)
            return PageStack(saved.distinct().take(maxDepth), maxDepth)
        }

        /** Root, a detail page, and one page deeper (library: playlists, a playlist, an artist). */
        const val DefaultMaxDepth = 3
    }
}
