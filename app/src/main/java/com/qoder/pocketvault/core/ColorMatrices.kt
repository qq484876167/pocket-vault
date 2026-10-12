package com.qoder.pocketvault.core

/**
 * 显示层用的颜色矩阵常量。纯数组，所以能在 JVM 探针里逐行断言（scratch/pstub/ColorProbe.kt）。
 *
 * 布局是 Android ColorMatrix 的那张 4×5：每行依次是 R、G、B、A 的系数，最后一列是**偏移，
 * 取值范围 0..255**（不是 0..1）。写成 -1 系数 + 255 偏移就是反色；
 * 如果误把偏移当 0..1 填 1f，效果会是"整页几乎全黑"而不是反色——这类错只能靠断言钉住。
 */
object ColorMatrices {

    /** 反色（夜间看文字版 PDF 不刺眼）。Alpha 那一行原样透传，别把透明度也反掉。 */
    val INVERT: FloatArray = floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** 矩阵必须是 4 行 × 5 列。 */
    const val LENGTH = 20

    /** 把某个颜色分量按这张矩阵变换后的值算出来（探针用它验证语义，也让"反色后白变黑"是可证的）。 */
    fun channel(matrix: FloatArray, row: Int, r: Float, g: Float, b: Float, a: Float): Float {
        require(matrix.size == LENGTH) { "颜色矩阵必须是 4x5" }
        require(row in 0..3)
        val source = floatArrayOf(r, g, b, a)
        var sum = matrix[row * 5 + 4]
        for (column in 0 until 4) sum += matrix[row * 5 + column] * source[column]
        return sum
    }
}
