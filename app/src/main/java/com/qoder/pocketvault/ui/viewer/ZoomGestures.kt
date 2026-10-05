package com.qoder.pocketvault.ui.viewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 只接管两指捏合：单指横向滑动完整交给 HorizontalPager，所以翻页和缩放互不打架。
 * 等待第二指期间刻意不消费事件——否则就会复现“翻页失灵”。
 * 放大后不再提高解码分辨率：跟系统图库一样，放大就是对已解码位图做缩放。
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pinchToZoom(state: PinchZoomState): Modifier = this.pointerInput(state) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        var previousDistance = 0f
        var previousCentroid = Offset.Zero
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val distance = (pressed[0].position - pressed[1].position).getDistance()
                val centroid = (pressed[0].position + pressed[1].position) / 2f
                if (previousDistance > 0f && distance > 0f) {
                    state.apply(distance / previousDistance, centroid - previousCentroid)
                }
                previousDistance = distance
                previousCentroid = centroid
                event.changes.forEach { it.consume() }
            } else {
                val only = event.changes.firstOrNull() ?: break
                if (!only.pressed) break
                if ((only.position - only.previousPosition).getDistance() > slop) break
            }
        }
    }
}
