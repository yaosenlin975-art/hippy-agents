package com.lin.hippyagent.core.accessibility.yolo

import kotlinx.serialization.Serializable

@Serializable
data class UiDetection(
    val label: String,
    val classId: Int,
    val confidence: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
)

val LABELS: List<String> = listOf(
    "ImageView",
    "TextView",
    "ProgressBar",
    "SeekBar",
    "EditText",
    "Icon",
    "CheckBox",
    "Switch",
    "Spinner",
    "RadioButton",
    "ScrollView",
    "RecyclerView",
    "ViewPager",
    "WebView",
    "Toolbar",
    "NavigationTab",
    "CardView",
    "TextButton",
    "ImageButton",
    "BottomNav",
    "Fab"
)

val INTERACTIVE_CLASSES: Set<Int> = setOf(5, 6, 17)

const val DEFAULT_CONFIDENCE = 0.15f
const val DEFAULT_IOU = 0.45f
const val MAX_DETECTIONS = 50
