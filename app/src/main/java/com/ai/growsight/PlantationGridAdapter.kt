package com.ai.growsight

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ai.growsight.data.ConversationEntity
import com.ai.growsight.data.PromptEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/**
 * PlantationGridAdapter
 * ─────────────────────────────────────────────────────────────────────────────
 * Drives the 2-column GridView on the home screen (main.xml → plantationGrid).
 * Each cell inflates item_plantation_card.xml.
 *
 * @param context       Activity context
 * @param plantations   List of ConversationEntity rows from the DB
 * @param promptMap     Map of conversationId → list of PromptEntity (scan history)
 * @param scope         CoroutineScope for async thumbnail loading (use lifecycleScope)
 */
class PlantationGridAdapter(
    private val context: Context,
    private val plantations: List<ConversationEntity>,
    private val promptMap: Map<Long, List<PromptEntity>>,
    private val scope: CoroutineScope
) : BaseAdapter() {

    override fun getCount(): Int = plantations.size
    override fun getItem(position: Int): ConversationEntity = plantations[position]
    override fun getItemId(position: Int): Long = plantations[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_plantation_card, parent, false)

        val plantation = plantations[position]
        val prompts    = promptMap[plantation.id] ?: emptyList()

        // ── View refs ─────────────────────────────────────────────────────────
        val thumbContainer   = view.findViewById<android.widget.FrameLayout>(R.id.cardThumbContainer)
        val thumbBackground  = view.findViewById<ImageView>(R.id.cardThumbBackground)
        val thumbImage       = view.findViewById<ImageView>(R.id.cardThumbImage)
        val stageBadge       = view.findViewById<TextView>(R.id.cardStageBadge)
        val progressBar      = view.findViewById<ProgressBar>(R.id.cardProgressBar)
        val progressLabel    = view.findViewById<TextView>(R.id.cardProgressLabel)
        val progressLabelEnd = view.findViewById<TextView>(R.id.cardProgressLabelEnd)
        val weekChipsRow     = view.findViewById<LinearLayout>(R.id.cardWeekChipsRow)
        val nameLabel        = view.findViewById<TextView>(R.id.cardPlantationName)
        val metaLabel        = view.findViewById<TextView>(R.id.cardPlantationMeta)

        // ── Name ──────────────────────────────────────────────────────────────
        nameLabel.text = plantation.name

        // ── Location label ────────────────────────────────────────────────────
        val locationLabel = view.findViewById<TextView>(R.id.cardPlantationLocation)
        if (!plantation.locationLabel.isNullOrBlank()) {
            locationLabel.text       = "📍 ${plantation.locationLabel}"
            locationLabel.visibility = View.VISIBLE
        } else {
            locationLabel.visibility = View.GONE
        }

        // ── Meta: week + scan count ───────────────────────────────────────────
        val scanCount   = prompts.size
        val currentWeek = estimateCurrentWeek(plantation)
        val weekLabel   = if (currentWeek != null) "Week $currentWeek" else "Week ?"
        metaLabel.text  = "$weekLabel · $scanCount scan${if (scanCount != 1) "s" else ""}"

        // ── Single source of truth ────────────────────────────────────────────
        // Everything card-level (badge, thumbnail, dimming, grayscale) derives
        // from the same prompt so they can never contradict each other.
        val effectivePrompt = prompts.maxByOrNull { it.id }
        val isAnomalous     = effectivePrompt == null ||
                isAnomalousResult(effectivePrompt.diagnostic)

        // Stage: derived directly from the effective prompt — no silent fallback
        // to an older scan that would make the badge contradict the visual state.
        val latestStage = effectivePrompt?.let { deriveStage(it.diagnostic) }

        // ── Progress bar ──────────────────────────────────────────────────────
        progressLabel.text = when {
            currentWeek == null -> "Wk ?"
            currentWeek >= 22   -> "Wk 22+"
            else                -> "Wk $currentWeek"
        }
        progressLabelEnd?.text = "Wk 22"

        bindStageColor(
            stageBadge  = stageBadge,
            progressBar = progressBar,
            stage       = latestStage,
            cropWeek    = currentWeek,
            context     = context
        )

        // ── Thumbnail — prefer effective prompt's images, fall back to any ────
        val latestUri: Uri? = effectivePrompt
            ?.imageUris?.firstOrNull()
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: prompts.lastOrNull { it.imageUris.isNotEmpty() }
                ?.imageUris?.firstOrNull()
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }

        thumbImage.visibility = View.GONE
        thumbImage.clearColorFilter()
        thumbImage.tag = latestUri
        thumbBackground.setImageResource(R.drawable.card_thumb_placeholder_bg)
        thumbBackground.clearColorFilter()

        // Apply grayscale to the entire thumbnail area at the container level.
        // This covers background + placeholder icon + loaded photo in one pass,
        // with no dependency on async coroutine timing.
        if (isAnomalous) {
            val grayPaint = android.graphics.Paint().apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }
            thumbContainer.setLayerType(View.LAYER_TYPE_SOFTWARE, grayPaint)
        } else {
            thumbContainer.setLayerType(View.LAYER_TYPE_NONE, null)
        }

        if (latestUri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val bmp = context.contentResolver.openInputStream(latestUri)?.use {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                        BitmapFactory.decodeStream(it, null, opts)
                    }
                    withContext(Dispatchers.Main) {
                        if (bmp != null && thumbImage.tag == latestUri) {  // ← guard
                            if (isAnomalous) {
                                val matrix = ColorMatrix().apply { setSaturation(0f) }
                                thumbImage.colorFilter = ColorMatrixColorFilter(matrix)
                            } else {
                                thumbImage.clearColorFilter()
                            }
                            thumbImage.setImageBitmap(bmp)
                            thumbImage.visibility = View.VISIBLE
                        }
                    }
                } catch (_: Exception) { /* keep gradient fallback */ }
            }
        }

        // ── Dim the whole card for anomalous results ──────────────────────────
        view.alpha = if (isAnomalous) 0.72f else 1f

        // ── Week chips ────────────────────────────────────────────────────────
        weekChipsRow.removeAllViews()
        buildWeekChips(weekChipsRow, prompts, currentWeek, plantation.plantingDate)

        return view
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Derives the display stage from a raw diagnostic string.
     * Returns null for no-detection/blank so the badge shows "No detection".
     * Returns a readable label for anomalous results instead of falling back
     * silently to an older scan's stage.
     */
    private fun deriveStage(diagnostic: String): String? {
        val normalized = diagnostic.lowercase().replace(" ", "_")
        return when {
            diagnostic.isBlank()                             -> null
            normalized.split("|").first() == "no_detection"  -> null
            diagnostic.startsWith("Stage Conflict|")         -> "Stage Conflict"
            diagnostic.startsWith("Insufficient Batch|")     -> "Insufficient"
            else -> diagnostic.split("|").firstOrNull()?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * Returns true for results that warrant grayscale + dimming:
     * - no_detection / blank
     * - Stage Conflict  (week/stage mismatch, flagged inconclusive)
     * - Insufficient Batch (too few valid images)
     */
    private fun isAnomalousResult(diagnostic: String): Boolean {
        if (diagnostic.isBlank()) return true
        val normalized = diagnostic.lowercase().replace(" ", "_")
        return when {
            normalized.split("|").first() == "no_detection" -> true
            diagnostic.startsWith("Stage Conflict|")     -> true
            diagnostic.startsWith("Insufficient Batch|") -> true
            else                                         -> false
        }
    }

    /**
     * Estimate the current crop week from planting date, or fall back to cropAgeWeeks.
     */
    private fun estimateCurrentWeek(plantation: ConversationEntity): Int? {
        val planted = plantation.plantingDate.takeIf { it > 0L }
        if (planted != null) {
            val diffMs = System.currentTimeMillis() - planted
            return ((diffMs / (1000L * 60 * 60 * 24 * 7)).toInt() + 1).coerceAtLeast(1)
        }
        return plantation.cropAgeWeeks
    }

    /**
     * Build week-chip dots.
     *
     * Chip colors:
     *   light-gray  = current week
     *   dark-green  = past week, scanned successfully
     *   orange      = past week, scanned but anomalous result
     *   gray        = past week, no scan at all (missed)
     */
    private fun buildWeekChips(
        row: LinearLayout,
        prompts: List<PromptEntity>,
        currentWeek: Int?,
        plantingDateMs: Long
    ) {
        row.orientation = LinearLayout.VERTICAL

        val total = currentWeek?.coerceAtLeast(1) ?: prompts.size.coerceAtLeast(1)

        // Map a prompt to its week number; fall back to index for old rows (timestampMs == 0)
        fun promptToWeek(index: Int, prompt: PromptEntity): Int {
            return if (plantingDateMs > 0L) {
                val ts = if (prompt.timestampMs > 0L) prompt.timestampMs
                else System.currentTimeMillis()
                val diffMs = ts - plantingDateMs
                ((diffMs / (1000L * 60 * 60 * 24 * 7)).toInt() + 1).coerceAtLeast(1)
            } else {
                index + 1
            }
        }

        val allScannedWeeks: Set<Int>  = prompts.mapIndexed { i, p -> promptToWeek(i, p) }.toSet()
        val anomalousWeeks:  Set<Int>  = prompts
            .mapIndexed { i, p -> i to p }
            .filter { (_, p) -> isAnomalousResult(p.diagnostic) }
            .map    { (i, p) -> promptToWeek(i, p) }
            .toSet()
        val healthyWeeks: Set<Int> = allScannedWeeks - anomalousWeeks

        val chipsPerRow = 10
        val chipSizeDp  = 14
        val textSizeSp  = 7f

        (1..total).chunked(chipsPerRow).forEach { weekChunk ->
            val subRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dpToPx(2)) }
            }

            weekChunk.forEach { week ->
                val isCurrent   = week == total
                val isHealthy   = week in healthyWeeks
                val isAnomalous = week in anomalousWeeks

                val chip = TextView(context).apply {
                    val sizePx = dpToPx(chipSizeDp)
                    layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                        setMargins(dpToPx(2), 0, dpToPx(2), 0)
                    }
                    text     = "$week"
                    gravity  = android.view.Gravity.CENTER
                    textSize = textSizeSp
                    setTextColor(ContextCompat.getColor(context,
                        if (isCurrent || isAnomalous) android.R.color.black else R.color.white))
                    typeface = androidx.core.content.res.ResourcesCompat
                        .getFont(context, R.font.nunito_extrabold)
                    setBackgroundResource(when {
                        isCurrent   -> R.drawable.circle_light_gray  // current week
                        isHealthy   -> R.drawable.circle_dark_green  // past, scanned OK
                        isAnomalous -> R.drawable.circle_inconclusive      // past, scanned anomalous
                        else        -> R.drawable.circle_gray        // past, missed
                    })
                }
                subRow.addView(chip)
            }

            row.addView(subRow)
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}

/**
 * Sets the stage badge text + background tint and progress bar color + fill.
 * Handles the "Stage Conflict" and "Insufficient" labels from deriveStage().
 */
fun bindStageColor(
    stageBadge: TextView,
    progressBar: ProgressBar,
    stage: String?,
    cropWeek: Int?,
    context: Context
) {
    val (badgeColor, barColor) = when (stage) {
        "Harvest Ready"   -> Pair(R.color.harvest_ready,        R.color.harvest_ready)
        "Near Harvest"    -> Pair(R.color.near_harvest,        R.color.near_harvest)
        "Not Ready"       -> Pair(R.color.not_ready,        R.color.not_ready)
        "Stage Conflict",
        "Insufficient"    -> Pair(R.color.inconclusive,  R.color.inconclusive)
        else              -> Pair(R.color.greenGray,      R.color.inconclusive)
    }

    stageBadge.setTextColor(
        ContextCompat.getColor(
            context,
            if (stage == "Stage Conflict" || stage == "Insufficient") android.R.color.black
            else R.color.white
        )
    )

    val badgeDrawable = stageBadge.background?.mutate()
    badgeDrawable?.setTint(ContextCompat.getColor(context, badgeColor))
    stageBadge.background = badgeDrawable
    stageBadge.text = when (stage) {
        null           -> "No detection"
        "Stage Conflict" -> "⚠️ Inconclusive"
        "Insufficient"   -> "📊 Insufficient"
        else             -> stage
    }

    progressBar.progressTintList =
        android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(context, barColor)
        )
    progressBar.max      = 22
    progressBar.progress = cropWeek?.coerceIn(0, 22) ?: 0
}