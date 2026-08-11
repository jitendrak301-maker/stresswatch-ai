package com.stresswatch.ai.ui.dashboard

import android.graphics.Color
import android.os.Bundle
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.stresswatch.ai.R
import com.stresswatch.ai.data.StressLevel
import com.stresswatch.ai.data.StressState
import com.stresswatch.ai.data.Trend
import com.stresswatch.ai.databinding.FragmentDashboardBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashboardViewModel by viewModels()
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupButtons()
        observeStressState()
    }

    private fun setupButtons() {
        binding.btnStartStop.setOnClickListener {
            if (viewModel.isMonitoring.value) {
                viewModel.stopMonitoring()
                stopCamera()
                binding.btnStartStop.text = "Start Monitoring"
                binding.tvLiveBadge.visibility = View.GONE
                binding.tvStatus.text = "Monitoring stopped"
            } else {
                viewModel.startMonitoring()
                startCamera()
                binding.btnStartStop.text = "Stop Monitoring"
                binding.tvLiveBadge.visibility = View.VISIBLE
                startLiveBlink()
                binding.tvStatus.text = "Calibrating sensors…"
            }
        }
    }

    private fun observeStressState() {
        lifecycleScope.launch {
            viewModel.stressState.collectLatest { state ->
                updateUI(state)
            }
        }
    }

    private fun updateUI(state: StressState) {
        if (!viewModel.isMonitoring.value) return

        val levelColor = when (state.level) {
            StressLevel.LOW      -> 0xFF4ADE80.toInt()
            StressLevel.MODERATE -> 0xFFFACC15.toInt()
            StressLevel.HIGH     -> 0xFFFB923C.toInt()
            StressLevel.CRITICAL -> 0xFFEF4444.toInt()
        }

        // Score
        binding.tvScore.text = state.score.toString()
        binding.tvScore.setTextColor(levelColor)
        binding.scoreRing.progress = state.score
        binding.scoreRing.progressTintList = android.content.res.ColorStateList.valueOf(levelColor)

        // Level label
        binding.tvLevel.text = state.level.label
        binding.tvLevel.setTextColor(levelColor)

        // Trend
        binding.tvTrend.text = when (state.trend) {
            Trend.RISING  -> "\u2197 Rising"
            Trend.FALLING -> "\u2198 Falling"
            Trend.STABLE  -> "\u2192 Stable"
        }

        // Modality scores
        binding.tvFaceScore.text = state.faceScore.toInt().toString()
        binding.tvHrvScore.text = String.format("%.0f", state.hrv)
        binding.tvMotionScore.text = state.motionScore.toInt().toString()

        // Voice
        binding.tvVoiceScore.text = state.voiceScore.toInt().toString()
        binding.pbVoice.progress = state.voiceScore.toInt()

        // Confidence
        val confPct = (state.confidence * 100).toInt()
        binding.tvConfidence.text = "$confPct%"
        binding.pbConfidence.progress = confPct

        // HR badge
        binding.tvHrBadge.text = if (state.heartRate > 0) "${state.heartRate} BPM" else "-- BPM"

        // Face status
        binding.tvFaceStatus.text = if (state.faceScore > 5f) "Face detected" else "No face"

        // Status text
        binding.tvStatus.text = "Monitoring active \u00b7 ${state.level.label}"

        // Critical alert vibration
        if (state.level == StressLevel.CRITICAL) {
            val vibrator = requireContext().getSystemService(android.content.Context.VIBRATOR_SERVICE)
                    as android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    android.os.VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1)
                )
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(binding.cameraPreview.surfaceProvider) }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processFrame(imageProxy)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toBitmap()
        viewModel.stressEngine.onFrameAnalyzed(bitmap)
        imageProxy.close()
    }

    private fun stopCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProviderFuture.get().unbindAll()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun startLiveBlink() {
        val blink = AlphaAnimation(1f, 0.2f).apply {
            duration = 800
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        binding.tvLiveBadge.startAnimation(blink)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        _binding = null
    }
}
