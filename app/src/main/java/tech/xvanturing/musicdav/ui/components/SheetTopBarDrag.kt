package tech.xvanturing.musicdav.ui.components

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker

/**
 * sheet 顶栏区域的下拉手势。
 *
 * 接通了手指跟随通道（[onSheetDrag]/[onSheetDragEnd] 非空）时：每帧竖直位移与松手速度
 * 直接喂给 MusicPlayerApp 的 sheetProgress，顶栏下拉与轮播上拉/列表下拉同源同手感——
 * sheet 实时跟着手指走，松手按位置+速度吸附，而不是憋 120px 无反馈后突然飞走。
 * 没接通道时退化为旧行为：累计下拉超过 120px 松手触发 [onFallbackClose] 关闭。
 */
fun Modifier.sheetTopBarDrag(
    onSheetDrag: ((Float) -> Unit)?,
    onSheetDragEnd: ((Float) -> Unit)?,
    onFallbackClose: () -> Unit,
): Modifier = pointerInput(onSheetDrag != null) {
    val velocityTracker = VelocityTracker()
    var dragY = 0f
    detectVerticalDragGestures(
        onDragStart = {
            velocityTracker.resetTracking()
            dragY = 0f
        },
        onDragEnd = {
            if (onSheetDrag != null) {
                onSheetDragEnd?.invoke(velocityTracker.calculateVelocity().y)
            } else if (dragY > 120f) {
                onFallbackClose()
            }
            dragY = 0f
        },
        onDragCancel = {
            if (onSheetDrag != null) onSheetDragEnd?.invoke(0f)
            dragY = 0f
        },
        onVerticalDrag = { change, amount ->
            velocityTracker.addPosition(change.uptimeMillis, change.position)
            if (onSheetDrag != null) onSheetDrag(amount) else dragY += amount
            change.consume()
        }
    )
}
