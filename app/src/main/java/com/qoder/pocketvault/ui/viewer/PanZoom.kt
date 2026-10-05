package com.qoder.pocketvault.ui.viewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.TransformableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 手写的最小双击/捏合缩放状态：scale + 平移量，1x 时自动收起偏移。
 * 不引入 PhotoView 之类的库，体积优先。
 */
class PinchZoomState {

    var scale by mutableFloatStateOf(1f)
        private set

    var offset by mutableStateOf(Offset.Zero)
        private set

    val zoomed: Boolean get() = scale > ZOOM_THRESHOLD

    fun apply(zoomChange: Float, panChange: Offset) {
        val next = (scale * zoomChange).coerceIn(1f, MAX_SCALE)
        scale = next
        offset = if (next <= 1.001f) Offset.Zero else offset + panChange
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    private companion object {
        const val MAX_SCALE = 6f
        const val ZOOM_THRESHOLD = 1.02f
    }
}

@Composable
fun rememberPinchZoom(): PinchZoomState = remember { PinchZoomState() }

/** 把缩放/平移/旋转应用到内容上。 */
fun Modifier.zoomTransform(state: PinchZoomState, rotation: Int): Modifier = graphicsLayer {
    scaleX = state.scale
    scaleY = state.scale
    translationX = state.offset.x
    translationY = state.offset.y
    rotationZ = rotation.toFloat()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberTransformableFor(state: PinchZoomState): TransformableState =
    rememberTransformableState { zoomChange, panChange, _ -> state.apply(zoomChange, panChange) }
