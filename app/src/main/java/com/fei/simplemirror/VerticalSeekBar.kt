package com.fei.simplemirror

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatSeekBar

/**
 * 竖直方向的 SeekBar：绘制和触摸都做 x/y 轴交换，进度向上增大。
 * SDK 没有原生竖向进度条，这是经典的旋转实现。
 */
class VerticalSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatSeekBar(context, attrs) {

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(h, w, oldh, oldw)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(heightMeasureSpec, widthMeasureSpec)
        setMeasuredDimension(measuredHeight, measuredWidth)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.rotate(-90f)
        canvas.translate(-height.toFloat(), 0f)
        super.onDraw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP -> {
                val progress = (max * (height - event.y) / height).toInt()
                setProgress(progress.coerceIn(0, max))
                onSizeChanged(width, height, 0, 0)
            }
        }
        return true
    }
}
