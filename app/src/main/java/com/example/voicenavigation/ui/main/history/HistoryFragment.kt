package com.example.voicenavigation.ui.main.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.voicenavigation.R
import com.example.voicenavigation.data.VoiceRecordAdapter
import com.example.voicenavigation.stt.BaiduTtsManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class HistoryFragment : Fragment() {

    private val viewModel: HistoryViewModel by viewModels()

    @Inject lateinit var baiduTts: BaiduTtsManager

    private var historyAdapter: VoiceRecordAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.page_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rvHistory: RecyclerView = view.findViewById(R.id.rv_history)
        val layoutEmpty: View = view.findViewById(R.id.layout_history_empty)
        val tvCount: TextView = view.findViewById(R.id.tv_history_count)
        val tvDestCount: TextView = view.findViewById(R.id.tv_history_dest_count)

        rvHistory.layoutManager = LinearLayoutManager(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.records.collect { records ->
                if (historyAdapter == null) {
                    historyAdapter = VoiceRecordAdapter(records.toMutableList())
                    setupAdapterListener(historyAdapter!!)
                    rvHistory.adapter = historyAdapter
                } else {
                    historyAdapter!!.updateData(records)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.totalCount.collect { tvCount.text = it.toString() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.destCount.collect { tvDestCount.text = it.toString() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isEmpty.collect { empty ->
                layoutEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                rvHistory.visibility = if (empty) View.GONE else View.VISIBLE
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.toastMessage.collect { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun setupAdapterListener(adapter: VoiceRecordAdapter) {
        adapter.setOnItemActionListener(object : VoiceRecordAdapter.OnItemActionListener {
            override fun onPlay(record: com.example.voicenavigation.data.VoiceRecord, position: Int) {
                viewModel.playRecord(record, baiduTts)
            }
            override fun onDelete(record: com.example.voicenavigation.data.VoiceRecord, position: Int) {
                AlertDialog.Builder(requireContext())
                    .setTitle("删除记录")
                    .setMessage("确定要删除这条历史记录吗？")
                    .setPositiveButton("删除") { _, _ -> viewModel.deleteRecord(record, position) }
                    .setNegativeButton("取消", null)
                    .show()
            }
        })
    }
}
