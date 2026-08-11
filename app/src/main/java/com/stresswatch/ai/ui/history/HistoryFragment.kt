package com.stresswatch.ai.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.stresswatch.ai.databinding.FragmentHistoryBinding
import com.stresswatch.ai.databinding.ItemSessionBinding
import com.stresswatch.ai.data.Session
import com.stresswatch.ai.data.StressLevel
import java.text.SimpleDateFormat
import java.util.*

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private val adapter = SessionAdapter()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvSessions.adapter = adapter
        // Show empty state for now - in production, observe from Room
        binding.tvEmpty.visibility = View.VISIBLE
        binding.rvSessions.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class SessionAdapter : RecyclerView.Adapter<SessionAdapter.ViewHolder>() {

    private val sessions = mutableListOf<Session>()

    fun updateSessions(newSessions: List<Session>) {
        sessions.clear()
        sessions.addAll(newSessions)
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemSessionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount() = sessions.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val session = sessions[position]
        val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
        val level = StressLevel.fromScore(session.avgScore.toInt())
        val emoji = when (level) {
            StressLevel.LOW      -> "\uD83D\uDE0C"
            StressLevel.MODERATE -> "\uD83D\uDE10"
            StressLevel.HIGH     -> "\uD83D\uDE24"
            StressLevel.CRITICAL -> "\uD83D\uDEA8"
        }
        val durationMin = session.durationSeconds / 60
        with(holder.binding) {
            tvSessionEmoji.text = emoji
            tvSessionDate.text = sdf.format(Date(session.startTime))
            tvSessionDuration.text = "${durationMin}m \u00b7 Peak: ${session.peakScore}"
            tvSessionAvg.text = session.avgScore.toInt().toString()
            tvSessionLevel.text = level.label
        }
    }
}
