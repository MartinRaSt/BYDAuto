package com.byd.charging.ui.charts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.byd.charging.R
import com.byd.charging.databinding.FragmentChartsHostBinding
import com.byd.charging.ui.main.CompareFragment
import com.byd.charging.ui.main.EnergyFragment
import com.google.android.material.tabs.TabLayoutMediator

/**
 * Obal zalozky Grafy. Uvnitr nabizi ctyri pohledy:
 *
 *  - Total     souhrnne grafy pres vsechny druhy energie
 *  - Elektrina cisla a spotreba jen za nabijeni
 *  - Benzin    cisla a spotreba jen za tankovani
 *  - Compare   porovnani dvou obdobi, energii, typu nabijeni nebo roku
 */
class ChartsHostFragment : Fragment() {

    private companion object {
        const val PAGE_TOTAL = 0
        const val PAGE_ELECTRIC = 1
        const val PAGE_GASOLINE = 2
        const val PAGE_COMPARE = 3
        const val PAGE_COUNT = 4
    }

    private var _binding: FragmentChartsHostBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChartsHostBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.innerViewPager.adapter = InnerAdapter(this)

        // Bez tohoto by vnoreny ViewPager2 pohltil vodorovna gesta a mezi
        // hlavnimi zalozkami by uz neslo prejizdet prstem
        binding.innerViewPager.isUserInputEnabled = false

        TabLayoutMediator(binding.innerTabLayout, binding.innerViewPager) { tab, pos ->
            tab.text = when (pos) {
                PAGE_TOTAL -> getString(R.string.chart_tab_total)
                PAGE_ELECTRIC -> getString(R.string.tab_electric)
                PAGE_GASOLINE -> getString(R.string.tab_gasoline)
                PAGE_COMPARE -> getString(R.string.chart_tab_compare)
                else -> ""
            }
        }.attach()
    }

    private inner class InnerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount() = PAGE_COUNT
        override fun createFragment(position: Int): Fragment = when (position) {
            PAGE_TOTAL -> ChartsFragment()
            PAGE_ELECTRIC -> EnergyFragment.newInstance(EnergyFragment.Kind.ELECTRIC)
            PAGE_GASOLINE -> EnergyFragment.newInstance(EnergyFragment.Kind.GASOLINE)
            PAGE_COMPARE -> CompareFragment()
            else -> throw IllegalStateException("Unknown inner page position: $position")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
