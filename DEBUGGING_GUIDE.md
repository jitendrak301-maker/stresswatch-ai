# StressWatch AI - Debugging Guide

This document contains all identified issues, crashes, and solutions.

---

## 🔴 Critical Issues (Will Crash)

### Issue #1: Missing Error Handling in FaceAnalyzer
**Location:** `app/src/main/java/com/stresswatch/ai/ml/FaceAnalyzer.kt:47`

**Problem:**
```kotlin
detector.process(image)
    .addOnSuccessListener { faces -> if (faces.isNotEmpty()) updateScore(faces[0]) }
    .addOnFailureListener { /* ignore */ }  // ❌ Silently fails
```
ML Kit failures are ignored, causing stress scores to freeze at 0.

**Fix:** Add debug logging:
```kotlin
detector.process(image)
    .addOnSuccessListener { faces -> 
        if (faces.isNotEmpty()) {
            updateScore(faces[0])
            Log.d("FaceAnalyzer", "Face detected: smile=${faces[0].smilingProbability}")
        } else {
            Log.d("FaceAnalyzer", "No face detected in frame")
        }
    }
    .addOnFailureListener { e ->
        Log.e("FaceAnalyzer", "Face detection failed: ${e.message}", e)
        currentScore = 0f  // Reset on error
    }
```

---

### Issue #2: No Error Handling in VoiceAnalyzer
**Location:** `app/src/main/java/com/stresswatch/ai/ml/VoiceAnalyzer.kt:39-54`

**Problem:**
```kotlin
audioRecord = AudioRecord(...)  // Can return null or throw
audioRecord?.startRecording()   // Silent failure if null
```
If AudioRecord initialization fails (missing permissions, device limitation), the app silently continues with 0 voice scores.

**Fix:** Add permission + error checks:
```kotlin
fun start() {
    try {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(FRAME_SIZE * 2)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("VoiceAnalyzer", "AudioRecord failed to initialize")
            currentScore = 0f
            return
        }
        
        audioRecord?.startRecording()
        Log.d("VoiceAnalyzer", "Audio recording started")
        analyzerJob = scope.launch { analysisLoop() }
    } catch (e: Exception) {
        Log.e("VoiceAnalyzer", "Error starting audio: ${e.message}", e)
        currentScore = 0f
    }
}
```

---

### Issue #3: Missing Null Check in RPPGAnalyzer
**Location:** `app/src/main/java/com/stresswatch/ai/ml/RPPGAnalyzer.kt:151`

**Problem:**
```kotlin
val threshold = signal.max() * 0.55f  // Crashes if signal is empty
```
If bandpass filter returns empty array, `max()` returns null.

**Fix:**
```kotlin
private fun detectPeaks(signal: FloatArray): List<Int> {
    val peaks = mutableListOf<Int>()
    if (signal.isEmpty()) {
        Log.w("RPPGAnalyzer", "Empty signal, skipping peak detection")
        return peaks
    }
    val threshold = (signal.maxOrNull() ?: 0f) * 0.55f
    val minDist = (SAMPLE_RATE * 0.4f).toInt()
    var lastPeak = -minDist
    for (i in 1 until signal.size - 1) {
        if (signal[i] > threshold && signal[i] > signal[i-1] && signal[i] > signal[i+1]
            && i - lastPeak >= minDist) {
            peaks.add(i)
            lastPeak = i
        }
    }
    Log.d("RPPGAnalyzer", "Detected ${peaks.size} peaks")
    return peaks
}
```

---

### Issue #4: Database Transaction Leak in StressRepository
**Location:** `app/src/main/java/com/stresswatch/ai/data/StressRepository.kt:40-54`

**Problem:**
```kotlin
suspend fun endSession() {
    val avg = dao.getAvgScoreForSession(currentSessionId) ?: 0f
    val peak = dao.getPeakScoreForSession(currentSessionId) ?: 0
    val session = dao.getAllSessions()  // ❌ Fetches ALL sessions (wasteful)
    // Update session record with final stats
    dao.updateSession(Session(
        sessionId = currentSessionId,
        startTime = System.currentTimeMillis(),  // ❌ Wrong! Overwrites startTime
        endTime = System.currentTimeMillis(),
        avgScore = avg,
        peakScore = peak
    ))
}
```

**Issue 1:** `startTime` is overwritten, losing original session start.
**Issue 2:** `durationSeconds` is never calculated.
**Issue 3:** Unused `session` variable fetches all records (performance hit).

**Fix:**
```kotlin
suspend fun endSession() {
    try {
        val avg = dao.getAvgScoreForSession(currentSessionId) ?: 0f
        val peak = dao.getPeakScoreForSession(currentSessionId) ?: 0
        
        // Calculate duration from existing session
        val sessions = dao.getAllSessions()  // Get all to find current
        val currentSession = sessions.filterWhere { it.sessionId == currentSessionId }.firstOrNull()
        val duration = if (currentSession != null) {
            (System.currentTimeMillis() - currentSession.startTime) / 1000  // in seconds
        } else {
            0L
        }
        
        dao.updateSession(
            Session(
                sessionId = currentSessionId,
                startTime = currentSession?.startTime ?: System.currentTimeMillis(),  // Keep original
                endTime = System.currentTimeMillis(),
                avgScore = avg,
                peakScore = peak.toInt(),
                durationSeconds = duration
            )
        )
        Log.d("StressRepository", "Session ended: avg=$avg, peak=$peak, duration=${duration}s")
    } catch (e: Exception) {
        Log.e("StressRepository", "Error ending session: ${e.message}", e)
    }
}
```

---

### Issue #5: Coroutine Scope Leak in DashboardViewModel
**Location:** `app/src/main/java/com/stresswatch/ai/ui/dashboard/DashboardViewModel.kt:30-35`

**Problem:**
```kotlin
fun startMonitoring() {
    if (_isMonitoring.value) return
    _isMonitoring.value = true
    stressEngine.start()
    viewModelScope.launch {
        repository.startSession()
        stressEngine.stressState.collect { state ->  // ❌ Never unsubscribed if stopMonitoring not called
            if (_isMonitoring.value) {
                repository.saveReading(state)
            }
        }
    }
}
```

Collection continues running even if `stopMonitoring()` isn't called (e.g., app crash).

**Fix:** Cancel collection properly:
```kotlin
private var monitoringJob: Job? = null

fun startMonitoring() {
    if (_isMonitoring.value) return
    _isMonitoring.value = true
    stressEngine.start()
    monitoringJob = viewModelScope.launch {
        try {
            repository.startSession()
            Log.d("DashboardViewModel", "Session started")
            stressEngine.stressState.collect { state ->
                if (_isMonitoring.value) {
                    repository.saveReading(state)
                    Log.d("DashboardViewModel", "Reading saved: score=${state.score}")
                }
            }
        } catch (e: Exception) {
            Log.e("DashboardViewModel", "Error in monitoring: ${e.message}", e)
            _isMonitoring.value = false
        }
    }
}

fun stopMonitoring() {
    _isMonitoring.value = false
    monitoringJob?.cancel()
    stressEngine.stop()
    viewModelScope.launch { 
        try {
            repository.endSession()
            Log.d("DashboardViewModel", "Session ended")
        } catch (e: Exception) {
            Log.e("DashboardViewModel", "Error ending session: ${e.message}", e)
        }
    }
}
```

---

### Issue #6: Missing Lifecycle Binding in HistoryFragment
**Location:** `app/src/main/java/com/stresswatch/ai/ui/history/HistoryFragment.kt:29-35`

**Problem:**
```kotlin
override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    binding.rvSessions.adapter = adapter
    // Show empty state for now - in production, observe from Room
    binding.tvEmpty.visibility = View.VISIBLE
    binding.rvSessions.visibility = View.GONE  // ❌ Never loads data
}
```

History data is never observed from Room database.

**Fix:** Add proper observation:
```kotlin
override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    binding.rvSessions.adapter = adapter
    
    // Observe sessions from repository
    lifecycleScope.launch {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                repository.getAllSessions().collect { sessions ->
                    if (sessions.isEmpty()) {
                        binding.tvEmpty.visibility = View.VISIBLE
                        binding.rvSessions.visibility = View.GONE
                        Log.d("HistoryFragment", "No sessions found")
                    } else {
                        binding.tvEmpty.visibility = View.GONE
                        binding.rvSessions.visibility = View.VISIBLE
                        adapter.updateSessions(sessions)
                        Log.d("HistoryFragment", "Loaded ${sessions.size} sessions")
                    }
                }
            } catch (e: Exception) {
                Log.e("HistoryFragment", "Error loading sessions: ${e.message}", e)
                binding.tvEmpty.visibility = View.VISIBLE
                binding.tvEmpty.text = "Error loading sessions"
            }
        }
    }
}
```

Add `repository` to fragment:
```kotlin
private val repository by lazy { StressRepository(requireContext()) }
```

---

## 🟠 Medium Severity Issues

### Issue #7: Session Time Bug
**Location:** `app/src/main/java/com/stresswatch/ai/data/StressRepository.kt:31-38`

**Problem:**
```kotlin
suspend fun startSession() {
    dao.insertSession(
        Session(
            sessionId = currentSessionId,
            startTime = System.currentTimeMillis()
            // endTime defaults to 0L
        )
    )
}
```

When querying sessions, `endTime = 0L` appears as 1970-01-01 (Unix epoch) in history.

**Fix:** Add proper null handling in display:
```kotlin
// In HistoryFragment's SessionAdapter.onBindViewHolder
val startDate = Date(session.startTime)
val endDate = if (session.endTime > 0) Date(session.endTime) else null
val durationMin = if (endDate != null) (session.durationSeconds / 60) else {
    (System.currentTimeMillis() - session.startTime) / 60000  // ongoing
}
tvSessionDuration.text = "${durationMin}m"
```

---

### Issue #8: No Permission Check in MotionAnalyzer
**Location:** `app/src/main/java/com/stresswatch/ai/ml/MotionAnalyzer.kt:28-30`

**Problem:**
```kotlin
private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
private val gyroSensor  = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
```

If device lacks accelerometer/gyroscope, these return null → crashes on `start()`.

**Fix:**
```kotlin
fun start() {
    try {
        accelSensor?.let { 
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.d("MotionAnalyzer", "Accelerometer registered")
        } ?: run {
            Log.w("MotionAnalyzer", "Accelerometer not available")
        }
        
        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.d("MotionAnalyzer", "Gyroscope registered")
        } ?: run {
            Log.w("MotionAnalyzer", "Gyroscope not available")
        }
    } catch (e: Exception) {
        Log.e("MotionAnalyzer", "Error registering sensors: ${e.message}", e)
    }
}
```

---

### Issue #9: Settings Clear Data Does Nothing
**Location:** `app/src/main/java/com/stresswatch/ai/ui/settings/SettingsFragment.kt:35-37`

**Problem:**
```kotlin
binding.btnClearData.setOnClickListener {
    Toast.makeText(requireContext(), "All session data cleared.", Toast.LENGTH_SHORT).show()
}  // ❌ No actual deletion
```

**Fix:**
```kotlin
binding.btnClearData.setOnClickListener {
    lifecycleScope.launch {
        try {
            val repository = StressRepository(requireContext())
            repository.cleanOldData()
            Toast.makeText(requireContext(), "All session data cleared.", Toast.LENGTH_SHORT).show()
            Log.d("SettingsFragment", "Data cleared")
        } catch (e: Exception) {
            Log.e("SettingsFragment", "Error clearing data: ${e.message}", e)
            Toast.makeText(requireContext(), "Error clearing data", Toast.LENGTH_SHORT).show()
        }
    }
}
```

---

### Issue #10: DashboardFragment Camera Error Not Handled
**Location:** `app/src/main/java/com/stresswatch/ai/ui/dashboard/DashboardFragment.kt:154-164`

**Problem:**
```kotlin
try {
    cameraProvider.unbindAll()
    cameraProvider.bindToLifecycle(
        viewLifecycleOwner,
        CameraSelector.DEFAULT_FRONT_CAMERA,
        preview,
        imageAnalysis
    )
} catch (e: Exception) {
    e.printStackTrace()  // ❌ Only prints, no user feedback
}
```

**Fix:**
```kotlin
try {
    cameraProvider.unbindAll()
    cameraProvider.bindToLifecycle(
        viewLifecycleOwner,
        CameraSelector.DEFAULT_FRONT_CAMERA,
        preview,
        imageAnalysis
    )
    Log.d("DashboardFragment", "Camera bound successfully")
    binding.tvStatus.text = "Camera ready"
} catch (e: CameraInfoUnavailableException) {
    Log.e("DashboardFragment", "Camera unavailable: ${e.message}", e)
    binding.tvStatus.text = "Camera unavailable"
} catch (e: Exception) {
    Log.e("DashboardFragment", "Camera error: ${e.message}", e)
    binding.tvStatus.text = "Camera error: ${e.localizedMessage}"
    Toast.makeText(
        requireContext(),
        "Camera access failed. Check permissions.",
        Toast.LENGTH_LONG
    ).show()
}
```

---

## 🟡 Low Severity Issues (Performance)

### Issue #11: FFT Performance in Voice Analyzer
**Location:** `app/src/main/java/com/stresswatch/ai/ml/VoiceAnalyzer.kt:147-170`

Running Cooley-Tukey FFT every 50ms on 2048 samples is CPU-intensive.

**Optimization:**
```kotlin
// Use FFT caching or pre-computed values
private val fftCache = mutableMapOf<Int, FloatArray>()

private fun fft(input: FloatArray): FloatArray {
    val cacheKey = input.contentHashCode()
    fftCache[cacheKey]?.let { 
        Log.d("VoiceAnalyzer", "FFT cache hit")
        return it
    }
    
    // Compute FFT...
    val result = FloatArray(...)
    
    // Keep cache limited to 5 entries
    if (fftCache.size > 5) fftCache.remove(fftCache.keys.first())
    fftCache[cacheKey] = result
    return result
}
```

Or use **ExoPlayer's FFT library** (faster, optimized).

---

### Issue #12: Unbounded ArrayDeque Growth
**Locations:**
- `FaceAnalyzer.kt:39` - `blinkHistory`
- `VoiceAnalyzer.kt:37` - `scoreHistory`
- `MotionAnalyzer.kt:32-33` - `accelHistory`, `gyroHistory`
- `RPPGAnalyzer.kt:37-40` - `greenChannel`, `redChannel`, etc.

**Problem:** ArrayDeques grow unbounded in long monitoring sessions.

**Fix:** Ensure all have size limits:
```kotlin
// Add to each analyzer
private val MAX_HISTORY_SIZE = 150  // ~5 seconds

// In loops:
private fun <T> ArrayDeque<T>.addWithLimit(e: T) {
    if (size >= MAX_HISTORY_SIZE) removeFirst()
    addLast(e)
}

// Use:
accelHistory.addWithLimit(newAccelData)
```

All analyzers should already have this, but double-check.

---

## 🔵 Testing Checklist

Run these tests to verify fixes:

- [ ] **Start monitoring** → Logcat should show:
  - `FaceAnalyzer: Face detected`
  - `VoiceAnalyzer: Audio recording started`
  - `MotionAnalyzer: Accelerometer registered`
  - `RPPGAnalyzer: Detected X peaks`

- [ ] **Stop monitoring** → Logcat should show:
  - `DashboardViewModel: Session ended`
  - No orphaned coroutines (use Android Profiler)

- [ ] **Low light** → Logcat should show:
  - `FaceAnalyzer: No face detected` (not crash)

- [ ] **No microphone** → Logcat should show:
  - `VoiceAnalyzer: Error starting audio` (not crash)

- [ ] **History view** → Should load sessions or show "No sessions"

- [ ] **Clear data** → DB should delete old data, Logcat shows:
  - `SettingsFragment: Data cleared`

---

## 📊 Debug Logging Tags

Add to your Logcat filter for focused debugging:

```
FaceAnalyzer
VoiceAnalyzer
MotionAnalyzer
RPPGAnalyzer
StressEngine
DashboardViewModel
StressRepository
DashboardFragment
HistoryFragment
```

---

## 🚀 Next Steps

1. **Add logging** to all analyzers
2. **Test on real device** with varied conditions (low light, no mic, no accel)
3. **Profile with Android Profiler** to catch memory leaks
4. **Run unit tests** on `StressEngine.fuseModalities()`
5. **Monitor crashes** with Firebase Crashlytics

