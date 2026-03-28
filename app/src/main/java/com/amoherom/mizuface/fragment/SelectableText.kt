package com.amoherom.mizuface.fragment

import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import android.util.AttributeSet
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import android.graphics.Typeface
import com.amoherom.mizuface.R
import com.amoherom.mizuface.databinding.FragmentSelectableTextBinding
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