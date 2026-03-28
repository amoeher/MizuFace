package com.amoherom.mizuface

import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar

data class BlendshapeRow(
    val blendshapeName: String,
    val blendshapeProgress: ProgressBar,
    var blendshapeWeight: EditText,
    var blendshapeValue: Float = 0.0f,
    var settings: LinearLayout,
    @Volatile var cachedMultiplier: Float = 1.0f
)
