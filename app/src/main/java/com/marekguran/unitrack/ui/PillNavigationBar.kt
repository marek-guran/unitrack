package com.marekguran.unitrack.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import com.google.android.material.R as MaterialR
import androidx.appcompat.R as AppCompatR
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A floating pill-shaped navigation bar inspired by Apple's liquid glass style.
 *
 * Features:
 * - Icons on phone, text labels on tablet
 * - Glass translucency on the held pill with side distortion
 * - Pill clamped inside bar bounds with edge-squish effect
 * - Pill corners always match bar corners for consistent shape
 * - Adaptive width: bar shrinks when items are few, icons/text scale up
 * - Smooth decelerate animation (no bounce/wobble)
 */
class PillNavigationBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val PILL_ANIM_DURATION_MS = 350L
        private const val RELEASE_ANIM_DURATION_MS = 250L
        private const val BASE_TEXT_SP = 13f
        private const val ICON_SIZE_DP = 22f
        private const val MAX_ICON_SIZE_DP = 28f
        private const val MAGNIFY_RADIUS_DP = 90f
        private const val MAX_TEXT_SCALE = 1.35f
        private const val SQUISH_ZONE_DP = 20f // px from edge where squish starts
        private const val MAX_SQUISH = 0.55f   // min pill width ratio at edge
        private const val BOLD_THRESHOLD = 0.5f // overlap ratio above which text becomes bold
    }

    // ── Public API ───────────────────────────────────────────────────────────

    var onItemSelected: ((index: Int) -> Unit)? = null
    var onItemReselected: ((index: Int) -> Unit)? = null

    enum class Mode { TEXT, ICON }

    private var mode: Mode = Mode.TEXT
    private var textItems: List<String> = emptyList()
    private var iconItems: List<Drawable> = emptyList()
    private var selectedIndex: Int = 0

    // ── Dimensions (px) ─────────────────────────────────────────────────────

    private val density = resources.displayMetrics.density
    private val barHeight = (44 * density).toInt()
    private val cornerRadius = 22 * density        // same for bar AND pill
    private val pillVPad = 4 * density              // vertical inset for pill
    private val baseTextSize = BASE_TEXT_SP * resources.displayMetrics.scaledDensity
    private val iconSizePx = (ICON_SIZE_DP * density).toInt()
    private val maxIconSizePx = (MAX_ICON_SIZE_DP * density).toInt()
    private val magnifyRadius = MAGNIFY_RADIUS_DP * density
    private val squishZone = SQUISH_ZONE_DP * density

    // ── Theme colours (mutable for config-change refresh) ──────────────────

    private var colorSurface: Int = 0
    private var colorPrimary: Int = 0
    private var colorOnPrimary: Int = 0
    private var colorOnSurface: Int = 0
    private var navbarBgColor: Int = 0
    private var pillColor: Int = 0

    private fun resolveThemeColors() {
        val ta = context.obtainStyledAttributes(
            intArrayOf(
                MaterialR.attr.colorSurface,
                AppCompatR.attr.colorPrimary,
                MaterialR.attr.colorOnPrimary,
                MaterialR.attr.colorOnSurface
            )
        )
        colorSurface = ta.getColor(0, 0xFF1C1C1E.toInt())
        colorPrimary = ta.getColor(1, 0xFF2C5F8A.toInt())
        colorOnPrimary = ta.getColor(2, 0xFFFFFFFF.toInt())
        colorOnSurface = ta.getColor(3, 0xFF000000.toInt())
        ta.recycle()

        val isDark = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        navbarBgColor = if (isDark) {
            ColorUtils.blendARGB(colorSurface, 0xFF000000.toInt(), 0.40f)
        } else {
            ColorUtils.blendARGB(colorSurface, 0xFFFFFFFF.toInt(), 0.20f)
        }
        // Pill must stand out clearly against the navbar bg
        pillColor = if (isDark) {
            ColorUtils.blendARGB(colorSurface, 0xFFFFFFFF.toInt(), 0.12f)
        } else {
            ColorUtils.blendARGB(colorSurface, 0xFF000000.toInt(), 0.08f)
        }
    }

    // ── Paints (colours set via applyColorsToPaints) ────────────────────────

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val strokeHalf = 2.5f * density  // half of 5dp stroke for inset
    private val bgStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
    }

    private val bgShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pillDrawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pillGlassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pillGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val pillRefractionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val selectedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = baseTextSize
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val unselectedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = baseTextSize
        textAlign = Paint.Align.CENTER
    }

    /** Push resolved colours into all paint objects. */
    private fun applyColorsToPaints() {
        bgPaint.color = ColorUtils.setAlphaComponent(navbarBgColor, 200)
        bgStrokePaint.color = navbarBgColor
        bgShadowPaint.color = navbarBgColor
        bgShadowPaint.setShadowLayer(
            14f * density, 0f, 4f * density,
            ColorUtils.setAlphaComponent(0xFF000000.toInt(), 60)
        )
        pillPaint.color = pillColor
        pillGlassPaint.color = ColorUtils.setAlphaComponent(pillColor, 160)
        pillGlowPaint.color = ColorUtils.setAlphaComponent(pillColor, 60)
        // Refraction uses a subtle tint of the pill's own colour, not hardcoded white.
        // 0.3f = 30 % blend toward white; alpha 30 keeps it barely perceptible.
        pillRefractionPaint.color = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(pillColor, 0xFFFFFFFF.toInt(), 0.3f), 30
        )
        selectedTextPaint.color = colorPrimary
        unselectedTextPaint.color = ColorUtils.setAlphaComponent(colorOnSurface, 140)
    }

    init {
        resolveThemeColors()
        applyColorsToPaints()
        // Padding so the drop-shadow is not clipped (14dp blur + 4dp offset).
        // On tablets (sw600dp+) the bar is at the TOP so shadow projects downward → need full bottom pad.
        // On phones the bar is at the BOTTOM edge so 8dp suffices (shadow fades into screen edge).
        val isTablet = resources.configuration.smallestScreenWidthDp >= 600
        val shadowPad = (16 * density).toInt()
        val shadowBottomPad = if (isTablet) shadowPad else (8 * density).toInt()
        setPadding(shadowPad, shadowPad, shadowPad, shadowBottomPad)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    // ── Drawing helpers ──────────────────────────────────────────────────────

    private val bgRect = RectF()
    private val pillRect = RectF()
    private val glowRect = RectF()
    private val refractionRect = RectF()

    private var pillCenterX: Float = 0f
    private var pillWidth: Float = 0f

    // ── Animation ────────────────────────────────────────────────────────────

    private var pillAnimator: ValueAnimator? = null
    private var releaseAnimator: ValueAnimator? = null
    private val decelerate = DecelerateInterpolator(2.5f)

    /** 1.0 while touching/dragging, smoothly decays to 0.0 on release.
     *  Used for squish, glow, glass paint, and refraction so they all
     *  animate out elegantly instead of snapping off. */
    private var interactionFraction = 0f

    // ── Touch / drag state ───────────────────────────────────────────────────

    private var isDragging = false
    private var isTouching = false
    private var dragStartX = 0f
    private var dragPillStartX = 0f
    private var currentTouchX = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // ── Adaptive sizing ──────────────────────────────────────────────────────

    /** Computed content start/end (bar can be narrower than view). */
    private var barLeft = 0f
    private var barRight = 0f
    private var adaptiveIconSize = iconSizePx

    // ── Public methods ───────────────────────────────────────────────────────

    /** Set text labels (tablet mode). */
    fun setItems(labels: List<String>) {
        mode = Mode.TEXT
        textItems = labels
        iconItems = emptyList()
        selectedIndex = 0
        requestLayout()
        invalidate()
    }

    /** Set icon drawables (phone mode). */
    fun setIconItems(icons: List<Drawable>) {
        mode = Mode.ICON
        iconItems = icons
        textItems = emptyList()
        selectedIndex = 0
        requestLayout()
        invalidate()
    }

    /** Programmatically select a tab (does NOT fire callback). */
    fun setSelectedIndex(index: Int, animate: Boolean = true) {
        val count = itemCount()
        if (index < 0 || index >= count) return
        if (index == selectedIndex) return
        selectedIndex = index
        if (animate && width > 0) {
            animatePillTo(targetCenterXFor(index), pillWidthFor(index))
        } else {
            pillCenterX = targetCenterXFor(index)
            pillWidth = pillWidthFor(index)
            invalidate()
        }
    }

    private fun itemCount(): Int = if (mode == Mode.ICON) iconItems.size else textItems.size

    // ── Measurement ──────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = barHeight + paddingTop + paddingBottom
        val maxW = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        val count = itemCount()

        // Adaptive: compute ideal width based on content
        val idealContentWidth: Float
        if (mode == Mode.ICON) {
            val slotWidth = maxIconSizePx + 32 * density
            idealContentWidth = slotWidth * count + 16 * density
        } else {
            var totalText = 0f
            for (t in textItems) totalText += selectedTextPaint.measureText(t) + 28 * density
            idealContentWidth = totalText + 16 * density
        }

        // Use min of ideal and available, so bar shrinks on few items
        val resolvedW = min(maxW, (idealContentWidth + paddingStart + paddingEnd).toInt())
        val h = resolveSize(desiredH, heightMeasureSpec)
        setMeasuredDimension(resolvedW, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        barLeft = paddingStart.toFloat()
        barRight = (w - paddingEnd).toFloat()

        // Compute adaptive icon size: fill more space when bar is wide relative to items
        if (mode == Mode.ICON && iconItems.isNotEmpty()) {
            val barW = barRight - barLeft
            val slotW = barW / iconItems.size
            adaptiveIconSize = min(maxIconSizePx, max(iconSizePx, (slotW * 0.45f).toInt()))
        }

        val count = itemCount()
        if (count > 0) {
            pillCenterX = targetCenterXFor(selectedIndex)
            pillWidth = pillWidthFor(selectedIndex)
        }
    }

    // ── Theme refresh on attach + configuration change ──────────────────────

    private fun currentUiMode(): Int =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Re-resolve after the window (and its theme) is fully ready.
        // Fixes stale colours when activity recreates for a theme switch.
        lastUiMode = currentUiMode()
        resolveThemeColors()
        applyColorsToPaints()
        invalidate()
    }

    private var lastUiMode: Int = currentUiMode()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val newUiMode = currentUiMode()
        if (newUiMode != lastUiMode) {
            lastUiMode = newUiMode
            resolveThemeColors()
            applyColorsToPaints()
            invalidate()
        }
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val count = itemCount()
        if (count == 0) return

        val isRtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val barW = barRight - barLeft
        val itemWidth = barW / count

        // 1. Background pill — inset by half stroke so stroke stays inside bounds
        bgRect.set(
            barLeft + strokeHalf,
            paddingTop.toFloat() + strokeHalf,
            barRight - strokeHalf,
            height.toFloat() - paddingBottom - strokeHalf
        )

        // Shadow behind bar (uses SW layer set in init)
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgShadowPaint)

        // Frosted glass fill
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)
        // Stroke — same rect, so it sits perfectly on the edge
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgStrokePaint)

        // 2. Compute squish factor: compress pill when near edges (scaled by interactionFraction)
        val halfBase = pillWidth / 2f
        val leftEdge = barLeft + cornerRadius / 2f
        val rightEdge = barRight - cornerRadius / 2f
        val pillLeft = pillCenterX - halfBase
        val pillRight = pillCenterX + halfBase

        var squishLeft = 1f
        var squishRight = 1f
        if (interactionFraction > 0f) {
            if (pillLeft < leftEdge + squishZone) {
                val t = 1f - ((pillLeft - leftEdge) / squishZone).coerceIn(0f, 1f)
                squishLeft = 1f - t * (1f - MAX_SQUISH) * interactionFraction
            }
            if (pillRight > rightEdge - squishZone) {
                val t = 1f - ((rightEdge - pillRight) / squishZone).coerceIn(0f, 1f)
                squishRight = 1f - t * (1f - MAX_SQUISH) * interactionFraction
            }
        }

        val drawPillHalfW = halfBase * min(squishLeft, squishRight)
        val clampedCenter = pillCenterX.coerceIn(barLeft + drawPillHalfW + pillVPad,
                                                  barRight - drawPillHalfW - pillVPad)

        // 3. Glow halo (glass distortion) — fades with interactionFraction
        if (interactionFraction > 0f) {
            val glowExpand = 5 * density
            glowRect.set(
                clampedCenter - drawPillHalfW - glowExpand,
                paddingTop + pillVPad / 2f,
                clampedCenter + drawPillHalfW + glowExpand,
                height - paddingBottom - pillVPad / 2f
            )
            pillGlowPaint.alpha = (60 * interactionFraction).toInt()
            canvas.drawRoundRect(glowRect, cornerRadius + 2 * density, cornerRadius + 2 * density, pillGlowPaint)
        }

        // 4. Pill indicator — blend between solid and glass based on interactionFraction
        pillRect.set(
            clampedCenter - drawPillHalfW,
            paddingTop + pillVPad,
            clampedCenter + drawPillHalfW,
            height.toFloat() - paddingBottom - pillVPad
        )
        pillDrawPaint.color = ColorUtils.blendARGB(
            pillColor, pillGlassPaint.color, interactionFraction
        )
        canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, pillDrawPaint)

        // 5. Glass refraction highlights on sides — fade with interactionFraction
        if (interactionFraction > 0f) {
            val refrW = 3f * density
            pillRefractionPaint.alpha = (30 * interactionFraction).toInt()
            // Left refraction
            refractionRect.set(
                pillRect.left + 2 * density,
                pillRect.top + 4 * density,
                pillRect.left + 2 * density + refrW,
                pillRect.bottom - 4 * density
            )
            canvas.drawRoundRect(refractionRect, refrW, refrW, pillRefractionPaint)
            // Right refraction
            refractionRect.set(
                pillRect.right - 2 * density - refrW,
                pillRect.top + 4 * density,
                pillRect.right - 2 * density,
                pillRect.bottom - 4 * density
            )
            canvas.drawRoundRect(refractionRect, refrW, refrW, pillRefractionPaint)
        }

        // 6. Draw items (icons or text) — with real-time pill overlap colour blending
        for (i in 0 until count) {
            val logicalIdx = if (isRtl) count - 1 - i else i
            val cx = barLeft + itemWidth * i + itemWidth / 2f

            // Compute how much the pill overlaps this item's slot (0..1)
            val slotLeft = barLeft + itemWidth * i
            val slotRight = slotLeft + itemWidth
            val overlapLeft = max(pillRect.left, slotLeft)
            val overlapRight = min(pillRect.right, slotRight)
            val overlapRatio = ((overlapRight - overlapLeft) / itemWidth).coerceIn(0f, 1f)

            if (mode == Mode.ICON) {
                drawIcon(canvas, logicalIdx, cx, overlapRatio)
            } else {
                drawLabel(canvas, logicalIdx, cx, overlapRatio)
            }
        }
    }

    private fun drawIcon(canvas: Canvas, index: Int, cx: Float, overlapRatio: Float) {
        if (index !in iconItems.indices) return
        val drawable = iconItems[index]

        // Magnify on touch proximity — scales smoothly with interactionFraction
        val scale = if (interactionFraction > 0f) {
            val dist = abs(cx - currentTouchX)
            val t = (1f - (dist / magnifyRadius).coerceIn(0f, 1f))
            1f + (MAX_TEXT_SCALE - 1f) * t * t * interactionFraction
        } else 1f

        val size = (adaptiveIconSize * scale).toInt()
        val halfSize = size / 2
        // Center vertically within the padded content area, not full view height
        val cy = paddingTop + (height - paddingTop - paddingBottom) / 2

        drawable.setBounds(
            (cx - halfSize).toInt(),
            cy - halfSize,
            (cx + halfSize).toInt(),
            cy + halfSize
        )
        // Blend tint from unselected to selected based on pill overlap
        val unselectedColor = ColorUtils.setAlphaComponent(colorOnSurface, 140)
        val blendedColor = ColorUtils.blendARGB(unselectedColor, colorPrimary, overlapRatio)
        drawable.setTint(blendedColor)
        drawable.draw(canvas)
    }

    private fun drawLabel(canvas: Canvas, index: Int, cx: Float, overlapRatio: Float) {
        if (index !in textItems.indices) return

        val scale = if (interactionFraction > 0f) {
            val dist = abs(cx - currentTouchX)
            val t = (1f - (dist / magnifyRadius).coerceIn(0f, 1f))
            1f + (MAX_TEXT_SCALE - 1f) * t * t * interactionFraction
        } else 1f

        // Blend color in real-time based on pill overlap
        val unselectedColor = ColorUtils.setAlphaComponent(colorOnSurface, 140)
        val blendedColor = ColorUtils.blendARGB(unselectedColor, colorPrimary, overlapRatio)
        val isBold = overlapRatio > BOLD_THRESHOLD

        val paint = if (isBold) selectedTextPaint else unselectedTextPaint
        val saved = paint.textSize
        val savedColor = paint.color
        paint.textSize = baseTextSize * scale
        paint.color = blendedColor
        // Center vertically within the padded content area
        val contentCy = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val textY = contentCy - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(textItems[index], cx, textY, paint)
        paint.textSize = saved
        paint.color = savedColor
    }

    // ── Touch handling ───────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val count = itemCount()
        if (count == 0) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pillAnimator?.cancel()
                releaseAnimator?.cancel()
                isDragging = false
                isTouching = true
                interactionFraction = 1f
                dragStartX = event.x
                dragPillStartX = pillCenterX
                currentTouchX = event.x
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                currentTouchX = event.x
                val dx = event.x - dragStartX
                if (!isDragging && abs(dx) > touchSlop) {
                    isDragging = true
                }
                if (isDragging) {
                    // Clamp pill center inside bar bounds (pill can't escape)
                    val halfPill = pillWidth / 2f
                    val minCenter = barLeft + halfPill + pillVPad
                    val maxCenter = barRight - halfPill - pillVPad
                    pillCenterX = (dragPillStartX + dx).coerceIn(minCenter, maxCenter)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                isTouching = false
                val nearest = nearestIndex(if (isDragging) pillCenterX else event.x)
                val old = selectedIndex
                selectedIndex = nearest
                animatePillTo(targetCenterXFor(nearest), pillWidthFor(nearest))
                animateInteractionRelease()
                if (old != nearest) {
                    onItemSelected?.invoke(nearest)
                } else {
                    onItemReselected?.invoke(nearest)
                }
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private fun targetCenterXFor(index: Int): Float {
        val count = itemCount()
        val barW = barRight - barLeft
        val itemWidth = barW / count
        val visualIdx = if (layoutDirection == LAYOUT_DIRECTION_RTL) count - 1 - index else index
        return barLeft + itemWidth * visualIdx + itemWidth / 2f
    }

    private fun pillWidthFor(index: Int): Float {
        val count = itemCount()
        val barW = barRight - barLeft
        val itemWidth = barW / count
        // Pill fills most of the slot (uniform size, consistent with bar corners)
        return itemWidth - 6 * density
    }

    private fun nearestIndex(x: Float): Int {
        val count = itemCount()
        val barW = barRight - barLeft
        val itemWidth = barW / count
        val visual = ((x - barLeft) / itemWidth).toInt().coerceIn(0, count - 1)
        return if (layoutDirection == LAYOUT_DIRECTION_RTL) count - 1 - visual else visual
    }

    private fun animatePillTo(targetX: Float, targetW: Float) {
        pillAnimator?.cancel()
        val startX = pillCenterX
        val startW = pillWidth

        pillAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PILL_ANIM_DURATION_MS
            interpolator = decelerate
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                pillCenterX = startX + (targetX - startX) * f
                pillWidth = startW + (targetW - startW) * f
                invalidate()
            }
            start()
        }
    }

    /** Smoothly decay interactionFraction from 1→0 so squish, glow, magnify all fade elegantly. */
    private fun animateInteractionRelease() {
        releaseAnimator?.cancel()
        val startVal = interactionFraction

        releaseAnimator = ValueAnimator.ofFloat(startVal, 0f).apply {
            duration = RELEASE_ANIM_DURATION_MS
            interpolator = decelerate
            addUpdateListener { anim ->
                interactionFraction = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
}
