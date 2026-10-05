package com.qoder.pocketvault.ui.viewer

import androidx.compose.ui.graphics.Color

/** 阅读配色：纸白 / 羊皮 / 护眼绿 / 夜间。文字与背景对比度都按长读不刺眼调。 */
enum class ReaderTheme(
    val label: String,
    val background: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val bar: Color,
) {
    PAPER("纸白", Color(0xFFFAFAF7), Color(0xFF1E1E1E), Color(0xFF8A8A8A), Color(0xFF1F6E52), Color(0xFFF0F0EA)),
    PARCHMENT("羊皮", Color(0xFFF2EFE6), Color(0xFF24211C), Color(0xFF8A8375), Color(0xFF1F6E52), Color(0xFFE7E2D5)),
    EYE("护眼", Color(0xFFC7E5C8), Color(0xFF1F2A20), Color(0xFF5E7A62), Color(0xFF18603F), Color(0xFFB9DCBB)),
    NIGHT("夜间", Color(0xFF14161A), Color(0xFFB8BDC6), Color(0xFF6E7480), Color(0xFF7FD8BC), Color(0xFF1D2026)),
}
