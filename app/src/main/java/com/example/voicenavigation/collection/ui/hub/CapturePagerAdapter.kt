package com.example.voicenavigation.collection.ui.hub

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.voicenavigation.collection.ui.dashboard.DashboardFragment
import com.example.voicenavigation.collection.ui.freemode.FreeCaptureFragment
import com.example.voicenavigation.collection.ui.gridmode.GridCaptureFragment

/**
 * 采样工具 ViewPager2 适配器。
 *
 * 三个页面：自由采集 · 八方向 · 后台管理
 */
class CapturePagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    companion object {
        val PAGE_TITLES = listOf("自由采集", "八方向", "后台管理")
    }

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> FreeCaptureFragment()
        1 -> GridCaptureFragment()
        else -> DashboardFragment()
    }

    fun getPageTitle(position: Int): String = PAGE_TITLES.getOrElse(position) { "" }
}
