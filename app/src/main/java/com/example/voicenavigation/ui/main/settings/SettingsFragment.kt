package com.example.voicenavigation.ui.main.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.voicenavigation.BuildConfig
import com.example.voicenavigation.R
import com.example.voicenavigation.collection.DataCollectionActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.page_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvAmapKey: TextView = view.findViewById(R.id.tv_amap_key)
        val etServerUrl: EditText = view.findViewById(R.id.et_server_url)
        val btnSaveUrl: Button = view.findViewById(R.id.btn_save_url)
        val btnResetUrl: Button = view.findViewById(R.id.btn_reset_url)
        val etDetectionServerUrl: EditText = view.findViewById(R.id.et_detection_server_url)
        val btnSaveDetectionUrl: Button = view.findViewById(R.id.btn_save_detection_url)
        val switchExternal: SwitchCompat = view.findViewById(R.id.switch_use_external_device)
        val etLlmBaseUrl: EditText = view.findViewById(R.id.et_llm_base_url)
        val etLlmApiKey: EditText = view.findViewById(R.id.et_llm_api_key)
        val etLlmModel: EditText = view.findViewById(R.id.et_llm_model)
        val btnSaveLlm: Button = view.findViewById(R.id.btn_save_llm)
        val tvLlmStatus: TextView = view.findViewById(R.id.tv_llm_status)
        val btnDataCollection: Button = view.findViewById(R.id.btn_data_collection)

        tvAmapKey.text = BuildConfig.AMAP_API_KEY

        // Observe ViewModel state
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.previewServerUrl.collect { etServerUrl.setText(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.detectionServerUrl.collect { etDetectionServerUrl.setText(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.llmBaseUrl.collect { etLlmBaseUrl.setText(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.llmApiKey.collect { etLlmApiKey.setText(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.llmModel.collect { etLlmModel.setText(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.llmStatus.collect { tvLlmStatus.text = it }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.useExternalDevice.collect { switchExternal.isChecked = it }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.toastMessage.collect {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
            }
        }

        btnSaveUrl.setOnClickListener {
            viewModel.savePreviewServer(etServerUrl.text.toString())
        }
        btnResetUrl.setOnClickListener {
            viewModel.resetPreviewServer()
        }
        btnSaveDetectionUrl.setOnClickListener {
            viewModel.saveDetectionServer(etDetectionServerUrl.text.toString())
        }
        switchExternal.setOnCheckedChangeListener { _, checked ->
            viewModel.setUseExternalDevice(checked)
        }
        btnSaveLlm.setOnClickListener {
            viewModel.saveLlmConfig(
                etLlmBaseUrl.text.toString(),
                etLlmApiKey.text.toString(),
                etLlmModel.text.toString()
            )
        }
        btnDataCollection.setOnClickListener {
            startActivity(Intent(requireContext(), DataCollectionActivity::class.java))
        }
    }
}
