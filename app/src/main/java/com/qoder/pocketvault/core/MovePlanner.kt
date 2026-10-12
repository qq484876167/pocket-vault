package com.qoder.pocketvault.core

/**
 * 库内移动前的决策，刻意写成不依赖 Context / Room 的纯函数：
 * 祖先折叠与非法目标判断是"会丢数据"的那一类逻辑，必须能在 JVM 探针里直接跑（见 scratch/pstub）。
 *
 * 三件事在这里一次算完，调用方拿到结果之前**磁盘一点不动**：
 *
 *  1. **随父目录一起走**：同时勾了 `A` 和 `A/B` 时，`A` 搬走就等于把 `A/B` 搬走了。
 *     以前实现会照旧去老路径 rename `A/B`，必然失败抛异常；索引被事务回滚而 `A` 已经物理搬走，
 *     结果是"索引说它还在原位（点开显示文件不存在）、新位置那份索引外管不到"。
 *  2. **已经在目标目录里**：等于原地不动，既不该算成功，也不该占用同名改名逻辑。
 *     注意它**不能顺带把子孙也免掉**——祖先原地不动时，子孙的搬迁仍然要照常执行，
 *     所以"谁带着谁"只在会真正移动的项之间计算。
 *  3. **非法目标**：目标就是自己、或目标在自己子树里。两种判据都查——
 *     沿 parentId 链上溯（这是唯一的事实来源）以及比对 relativePath（它是缓存视图，
 *     一旦与链不一致就应该被察觉）。两条都不认才放行。
 */
object MovePlanner {

    /** 参与决策的一项：只需要 id / parentId / 相对路径 / 是不是文件夹，故意不带 VaultEntry。 */
    data class Node(
        val id: Long,
        val parentId: Long,
        val relativePath: String,
        val isFolder: Boolean,
    )

    data class Plan(
        /** 本次真正要逐个 rename 的项（已折叠掉随父走的子孙）。 */
        val moveNow: List<Node>,
        /** 随某个 moveNow 项一起走的子孙，只用于给用户说明。 */
        val carried: List<Node>,
        /** 已经在目标目录里的项，不搬也不报错。 */
        val alreadyThere: List<Node>,
        /** 非空即整批拒绝：每项一句人话原因。 */
        val illegal: List<String>,
    )

    /**
     * [ancestorIds] 返回某个节点从**父亲**开始向上到根（不含根、不含自己）的 id 链。
     * 由调用方查库给出，本对象不关心根怎么表示。
     *
     * [destId] 为目标目录的 id；目标目录还不存在时传 null（路径检查仍然会跑）。
     */
    fun plan(
        selected: List<Node>,
        destRelativePath: String,
        destId: Long?,
        ancestorIds: (Long) -> List<Long>,
    ): Plan {
        val destRel = destRelativePath.trim('/')
        val selectedById = LinkedHashMap<Long, Node>()
        selected.forEach { selectedById[it.id] = it }
        // 目标目录自己的祖先链：某个选中文件夹出现在这条链上，说明**目标在它肚子里**（禁止）。
        // 反方向（它自己在目标肚子里）是往外搬，合法，不能混为一谈。
        val destChain = destId?.let { ancestorIds(it) }

        // 已经在目标目录里 = 原地不动。先挑出来，它们不能当"带着别人走"的那个祖先。
        val stayers = selected.filter { destId != null && it.parentId == destId }.map { it.id }.toHashSet()

        val moveNow = ArrayList<Node>()
        val carried = ArrayList<Node>()
        val alreadyThere = ArrayList<Node>()
        val illegal = ArrayList<String>()

        for (node in selected) {
            if (node.id in stayers) {
                alreadyThere += node
                continue
            }
            val chain = ancestorIds(node.id)
            // 最近的"会真正移动"的选中祖先：存在就说明这个节点随它一起走。
            val carrier = chain.firstOrNull { ancestor ->
                ancestor in selectedById.keys && ancestor !in stayers
            }
            if (carrier != null) {
                carried += node
                continue
            }
            if (node.isFolder) {
                // 三种"目标在自己肚子里"的表现：就是自己、自己在目标的祖先链上、目标路径以它为前缀。
                // 注意方向：反过来（自己在目标肚子里）是往外搬，合法，不能误判。
                val intoItself = destId == node.id
                val destInsideByChain = destChain != null && node.id in destChain
                val destInsideByPath = destRel == node.relativePath.trim('/') ||
                    destRel.startsWith("${node.relativePath.trim('/')}/")
                if (intoItself || destInsideByChain || destInsideByPath) {
                    illegal += "不能把文件夹「${node.relativePath.substringAfterLast('/')}」移动到它自己里面"
                    continue
                }
            }
            moveNow += node
        }
        return Plan(moveNow, carried, alreadyThere, illegal)
    }

    /** 选择器里一条禁用记录：[canEnter] 区分"能穿过去选子目录"与"连进都不给"。 */
    data class PickerBlock(val path: String, val reason: String, val canEnter: Boolean)

    /**
     * 目录选择器的完整禁用表（纯函数）：
     *  - [sources] 里那些文件夹自己 + 它们的后代 → 深灰，不能进也不能选；
     *  - [ownDirs]（选中项真正所在的目录）→ 浅灰，能进、只是不能就地确认。
     *
     * **两边的合并顺序是修过的 bug**：以前是 `forbidden + ownDirs`，于是"选中了文件夹 A
     * 又选中 A 里的某个文件"时，A 既是硬禁层（它自己要被移动）又是 ownDirs（它是那个文件的父目录），
     * 右值胜出 → A 变成可以进去 → 在选择器里建个子文件夹点确认，最后被 MovePlanner 整批拒绝，
     * 什么也没动、还多出一个空文件夹。这里让硬禁优先。
     */
    fun pickerBlocks(
        sources: List<String>,
        allFolderPaths: List<String>,
        ownDirs: List<String>,
    ): List<PickerBlock> {
        val out = LinkedHashMap<String, PickerBlock>()
        ownDirs.map { it.trim('/') }.distinct().forEach { path ->
            out[path] = PickerBlock(path, "内容已经在这个目录里", canEnter = true)
        }
        // 顺序有讲究：硬禁层最后写入，覆盖掉同一层的"能穿过去"。
        subtreeOf(sources, allFolderPaths).forEach { rule ->
            out[rule.path] = PickerBlock(
                rule.path,
                if (rule.isSourceItself) "这正是要移动的文件夹" else "它在要移动的文件夹里面",
                canEnter = false,
            )
        }
        return out.values.toList()
    }

    /** 目录选择器里的一条禁用记录：[isSourceItself] 区分"就是要移动的那个文件夹"和"它肚子里的子目录"。 */
    data class PickerRule(val path: String, val isSourceItself: Boolean)

    /**
     * 移动这些文件夹时，目录选择器里**连进都不该进**的层：它们自己 + 它们的所有后代。
     * 纯函数，路径表由调用方一次查好（避免逐层递归查库）。
     *
     * 只负责"哪些层是非法目标"；浅灰可穿的那一类（"内容已经在这个目录里"）不在这里，
     * 由界面单独加——把两类混成一种是本次那个"新建的子文件夹永远够不到"的根因。
     */
    fun subtreeOf(sources: List<String>, allFolderPaths: List<String>): List<PickerRule> {
        val out = LinkedHashMap<String, PickerRule>()
        sources.map { it.trim('/') }.filter { it.isNotEmpty() }.distinct().forEach { source ->
            allFolderPaths.map { it.trim('/') }.forEach { candidate ->
                when {
                    candidate == source -> out[candidate] = PickerRule(candidate, isSourceItself = true)
                    candidate.startsWith("$source/") ->
                        if (candidate !in out) out[candidate] = PickerRule(candidate, isSourceItself = false)
                }
            }
        }
        return out.values.toList()
    }
}
