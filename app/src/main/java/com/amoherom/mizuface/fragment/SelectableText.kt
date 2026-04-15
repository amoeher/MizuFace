package com.amoherom.mizuface.fragment

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import android.graphics.Typeface
import com.amoherom.mizuface.R
class SelectableText(context: Context, attrs: AttributeSet? = null) : AppCompatTextView(context, attrs) {

    override fun setSelected(isSelected: Boolean) {
        super.setSelected(isSelected)
        setTextColor(
            context.getColor(
                if (isSelected) {
                    R.color.color_text_active
                } else {
                    R.color.color_text_dark
                }
            )
        )

        setTypeface(
            typeface,
            if (isSelected) {
                Typeface.BOLD
            } else {
                Typeface.NORMAL
            }
        )
    }
}