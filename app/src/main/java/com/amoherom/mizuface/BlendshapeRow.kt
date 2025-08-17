package com.amoherom.mizuface

import android.widget.EditText
import android.widget.ProgressBar

data class BlendshapeRow(
    val blendshapeName: String,
    val blendshapeProgress: ProgressBar,
    var blendshapeWeight: EditText,
    var blendshapeValue: Float = 0.0f
)
