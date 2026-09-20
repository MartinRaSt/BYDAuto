package com.byd.charging.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.byd.charging.R
import com.byd.charging.data.ChargingSession
import com.byd.charging.databinding.FragmentSessionListBinding
import com.byd.charging.util.DateUtil
import com.byd.charging.util.ZoomHelper

class SessionListFragment : Fragment() {

    private var _binding: FragmentSessionListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: SessionAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSessionListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ZoomHelper.setupPinchToZoom(binding.root)

        adapter = SessionAdapter(
            onEdit = { session ->
                startActivity(
                    Intent(requireContext(), DetailActivity::class.java).apply {
                        putExtra(DetailActivity.EXTRA_SESSION_ID, session.id)
                    }
                )
            },
            onDelete = { session -> confirmDelete(session) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        // Nyní pozorujeme filtrovaná data, aby seznam odpovídal grafům
        viewModel.filteredSessions.observe(viewLifecycleOwner) { sessions ->
            adapter.submitList(sessions)
            binding.tvEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun confirmDelete(session: ChargingSession) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.confirm_delete_title)
            .setMessage(
                getString(R.string.confirm_delete_message, DateUtil.toDisplayDate(session.date))
            )
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.delete(session) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
