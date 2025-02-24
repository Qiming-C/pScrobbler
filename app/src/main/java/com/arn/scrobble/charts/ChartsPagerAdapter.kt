package com.arn.scrobble.charts


import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter

class ChartsPagerAdapter(fm: FragmentManager) :
    FragmentStatePagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
    private val tabCount = 3

    override fun getItem(position: Int): Fragment {
        return when (position) {
            0 -> ArtistChartsFragment()
            1 -> AlbumChartsFragment()
            2 -> TrackChartsFragment()
            else -> throw IllegalArgumentException("Invalid position")
        }
    }

    override fun getCount() = tabCount
}