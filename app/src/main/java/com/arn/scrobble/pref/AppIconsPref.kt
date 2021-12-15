package com.arn.scrobble.pref

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.res.TypedArrayUtils
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import coil.load
import coil.size.Scale
import com.arn.scrobble.R
import com.arn.scrobble.Stuff.dp
import com.arn.scrobble.databinding.PrefAppIconsBinding
import com.arn.scrobble.ui.PackageName
import com.google.android.material.imageview.ShapeableImageView
import java.text.NumberFormat


/**
 * Created by arn on 09/09/2017.
 */

class AppIconsPref(context: Context, attrs: AttributeSet?, defAttrs: Int, defStyle: Int) :
    Preference(context, attrs, defAttrs, defStyle) {
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : this(
        context,
        attrs,
        defStyle,
        0
    )

    constructor(context: Context, attrs: AttributeSet?) : this(
        context,
        attrs,
        TypedArrayUtils.getAttr(
            context, R.attr.preferenceStyle,
            android.R.attr.preferenceStyle
        )
    )

    constructor(context: Context) : this(context, null)

    init {
        layoutResource = R.layout.pref_app_icons
//        widgetLayoutResource = R.layout.pref_app_icons_widget
    }

    private val wPx = 48.dp

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        holder.isDividerAllowedAbove = false
        val binding = PrefAppIconsBinding.bind(holder.itemView)
        val packageNames = sharedPreferences!!.getStringSet(key, setOf())!!

        if (packageNames.isNotEmpty()) {
            binding.appIconsContainer.removeAllViews()
            binding.appListAdd.measure(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            val totalWidth = context.resources.displayMetrics.widthPixels -
                    binding.root.paddingLeft - binding.root.paddingRight - binding.appListAdd.measuredWidth

            val nIcons = (totalWidth / wPx) - 1
            for (i in 0 until minOf(nIcons, packageNames.size)) {
                val icon = ShapeableImageView(context)
                icon.scaleType = ImageView.ScaleType.FIT_CENTER
                icon.layoutParams = LinearLayout.LayoutParams(wPx, wPx)
                val padding = wPx / 8
                icon.setPadding(padding, padding, padding, padding)
                icon.load(PackageName(packageNames.elementAt(i))) {
                    allowHardware(false)
                    scale(Scale.FIT)
                }
                binding.appIconsContainer.addView(icon)
            }

            if (packageNames.size > nIcons) {
                binding.appListNMore.visibility = View.VISIBLE
                binding.appListNMore.text =
                    "+" + NumberFormat.getInstance().format(packageNames.size - nIcons)
            } else
                binding.appListNMore.visibility = View.GONE
        } else {
            binding.appListNMore.visibility = View.VISIBLE
            binding.appListNMore.text = context.getString(R.string.no_apps_enabled)
        }
    }
}