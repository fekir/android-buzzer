package info.fekir.buzzer

import android.app.Activity
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.view.accessibility.AccessibilityManager
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

private class HoldButton(
  context: Context,
  private val start: () -> Unit,
  private val stop: () -> Unit
) : Button(context) {
  override fun setPressed(pressed: Boolean) {
    val changed = pressed != isPressed
    super.setPressed(pressed)
    if (changed) {
      if (pressed && isEnabled) start() else stop()
    }
  }
}

class MainActivity : Activity() {
  private var customBeep: AudioTrack? = null
  private var selectedSoundStream = AudioManager.STREAM_ALARM
  private lateinit var sampleRateInput: EditText
  private lateinit var periodInput: EditText
  private lateinit var amplitudeInput: EditText
  private lateinit var accessibilityPermissionButton: Button
  private val holdButtons = mutableListOf<Button>()
  private lateinit var audioManager: AudioManager
  private val volumeSliders = mutableMapOf<Int, SeekBar>()
  private val volumeLabels = mutableMapOf<Int, Pair<TextView, Int>>()
  private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
    override fun onChange(selfChange: Boolean) {
      updateVolumes()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    volumeControlStream = AudioManager.STREAM_MUSIC
    audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    sampleRateInput = createSoundInput(R.id.sample_rate_input, "44100")
    periodInput = createSoundInput(R.id.period_input, "30")
    amplitudeInput = createSoundInput(R.id.amplitude_input, "25", decimal = true)

    val vibrator =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
      } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
      }

    val vibrateButton = HoldButton(this, start = {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 1000L), 0))
      } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(longArrayOf(0L, 1000L), 0)
      }
    }, stop = { vibrator.cancel() }).apply {
      id = R.id.buzz_button
      setText(R.string.buzz_button)
      isEnabled = vibrator.hasVibrator()
    }

    val customSoundButton = HoldButton(this, start = {
      volumeControlStream = selectedSoundStream
      startCustomBeep()
    }, stop = { customBeep?.pause() }).apply {
      id = R.id.custom_beep_button
      setText(R.string.custom_beep_button)
    }
    holdButtons.addAll(listOf( customSoundButton, vibrateButton))

    val soundStreamGroup = RadioGroup(this).apply {
      id = R.id.sound_stream_group
      orientation = RadioGroup.VERTICAL
    }
    val soundStreams = mutableListOf(
      Triple(AudioManager.STREAM_MUSIC, R.id.stream_media, R.string.media_volume),
      Triple(AudioManager.STREAM_ALARM, R.id.stream_alarm, R.string.alarm_volume),
      Triple(AudioManager.STREAM_RING, R.id.stream_ring, R.string.ringtone_volume),
      Triple(AudioManager.STREAM_NOTIFICATION, R.id.stream_notification, R.string.notification_volume),
      Triple(AudioManager.STREAM_VOICE_CALL, R.id.stream_call, R.string.call_volume),
      Triple(AudioManager.STREAM_SYSTEM, R.id.stream_system, R.string.system_volume),
      Triple(AudioManager.STREAM_DTMF, R.id.stream_dtmf, R.string.dtmf_volume)
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      soundStreams.add(Triple(
        AudioManager.STREAM_ACCESSIBILITY, R.id.stream_accessibility, R.string.accessibility_volume
      ))
    }
    soundStreams.forEach { (_, buttonId, label) ->
      soundStreamGroup.addView(RadioButton(this).apply {
        id = buttonId
        setText(label)
      })
    }
    soundStreamGroup.setOnCheckedChangeListener { _, checkedId ->
      selectedSoundStream = soundStreams.firstOrNull { it.second == checkedId }?.first ?: AudioManager.STREAM_ALARM
      volumeControlStream = selectedSoundStream
      updateVolumeVisibility()
    }
    soundStreamGroup.check(R.id.stream_alarm)

    val mediaVolumeLabel = createVolumeLabel(
      AudioManager.STREAM_MUSIC, R.id.media_volume_slider, R.string.media_volume
    )
    val mediaVolumeSlider = createVolumeSlider(
      AudioManager.STREAM_MUSIC, R.id.media_volume_slider, R.string.media_volume
    )
    val volumeLabel = createVolumeLabel(
      AudioManager.STREAM_ALARM, R.id.alarm_volume_slider, R.string.alarm_volume
    )
    val alarmVolumeSlider = createVolumeSlider(
      AudioManager.STREAM_ALARM, R.id.alarm_volume_slider, R.string.alarm_volume
    )
    val ringtoneVolumeLabel = createVolumeLabel(
      AudioManager.STREAM_RING, R.id.ringtone_volume_slider, R.string.ringtone_volume
    )
    val ringtoneVolumeSlider = createVolumeSlider(
      AudioManager.STREAM_RING, R.id.ringtone_volume_slider, R.string.ringtone_volume
    )
    accessibilityPermissionButton = Button(this).apply {
      id = R.id.accessibility_permission_button
      setText(R.string.enable_accessibility_volume)
      setOnClickListener {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
      }
    }

    val layout = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      val sideMargin = (24 * resources.displayMetrics.density).toInt()
      val buttonGap = (12 * resources.displayMetrics.density).toInt()
      setPadding(0, sideMargin, 0, sideMargin)
      val controls = mutableListOf<View>(

        vibrateButton,        TextView(this@MainActivity).apply { setText(R.string.sound_stream) },soundStreamGroup,
          mediaVolumeLabel, mediaVolumeSlider, volumeLabel, alarmVolumeSlider,
        ringtoneVolumeLabel, ringtoneVolumeSlider,
        createSoundField(R.string.sample_rate, sampleRateInput),
        createSoundField(R.string.wave_period, periodInput),
        createSoundField(R.string.wave_amplitude, amplitudeInput),
        customSoundButton

      )
      val extraVolumes = mutableListOf(
        Triple(AudioManager.STREAM_NOTIFICATION, R.id.notification_volume_slider, R.string.notification_volume),
        Triple(AudioManager.STREAM_VOICE_CALL, R.id.call_volume_slider, R.string.call_volume),
        Triple(AudioManager.STREAM_SYSTEM, R.id.system_volume_slider, R.string.system_volume),
        Triple(AudioManager.STREAM_DTMF, R.id.dtmf_volume_slider, R.string.dtmf_volume)
      )
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        extraVolumes.add(Triple(
          AudioManager.STREAM_ACCESSIBILITY, R.id.accessibility_volume_slider, R.string.accessibility_volume
        ))
      }
      extraVolumes.forEach { (stream, viewId, label) ->
        controls.add(createVolumeLabel(stream, viewId, label))
        controls.add(createVolumeSlider(stream, viewId, label))
      }
      controls.add(accessibilityPermissionButton)
      controls.forEachIndexed { index, control ->
        addView(control, LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
          setMargins(sideMargin, 0, sideMargin, if (index < controls.lastIndex) buttonGap else 0)
        })
      }
    }
    setContentView(ScrollView(this).apply {
      isFillViewport = true
      fitsSystemWindows = true
      addView(layout)
    })
    updateVolumeVisibility()
  }

  override fun onStart() {
    super.onStart()
    contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
    updateVolumes()
  }

  override fun onResume() {
    super.onResume()
    updateVolumes()
    updateAccessibilityPermissionButton()
  }

  private fun isAccessibilityVolumeServiceEnabled(): Boolean {
    val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val service = ComponentName(this, AccessibilityVolumeService::class.java)
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
      .any { it.resolveInfo.serviceInfo.let { info ->
        info.packageName == service.packageName && info.name == service.className
      } }
  }

  private fun updateAccessibilityPermissionButton() {
    accessibilityPermissionButton.setText(
      if (isAccessibilityVolumeServiceEnabled()) {
        R.string.disable_accessibility_volume
      } else {
        R.string.enable_accessibility_volume
      }
    )
  }

  private fun createSoundInput(viewId: Int, defaultValue: String, decimal: Boolean = false): EditText =
    EditText(this).apply {
      id = viewId
      inputType = InputType.TYPE_CLASS_NUMBER or if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0
      isSingleLine = true
      setText(defaultValue)
    }

  private fun createSoundField(label: Int, input: EditText): LinearLayout =
    LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      addView(TextView(this@MainActivity).apply {
        setText(label)
        labelFor = input.id
      }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
      input.contentDescription = getString(label)
      addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

  private fun startCustomBeep() {
    sampleRateInput.error = null
    periodInput.error = null
    amplitudeInput.error = null
    val sampleRate = sampleRateInput.text.toString().toIntOrNull()
    if (sampleRate == null || sampleRate !in 4000..192000) {
      sampleRateInput.error = getString(R.string.invalid_sample_rate)
      return
    }
    val period = periodInput.text.toString().toIntOrNull()
    if (period == null || period !in 16..65536 || period % 2 != 0) {
      periodInput.error = getString(R.string.invalid_period)
      return
    }
    val amplitude = amplitudeInput.text.toString().toDoubleOrNull()
    if (amplitude == null || !amplitude.isFinite() || amplitude !in 0.0..100.0) {
      amplitudeInput.error = getString(R.string.invalid_amplitude)
      return
    }
    customBeep?.release()
    customBeep = null
    try {
      customBeep = createCustomBeep(sampleRate, period, amplitude, selectedSoundStream)
      customBeep?.apply {
        check(setLoopPoints(0, period, -1) == AudioTrack.SUCCESS)
        play()
      }
    } catch (error: RuntimeException) {
      customBeep?.release()
      customBeep = null
      if (error !is IllegalArgumentException && error !is IllegalStateException) throw error
      Toast.makeText(this, R.string.sound_unavailable, Toast.LENGTH_SHORT).show()
    }
  }

  private fun createCustomBeep(
    sampleRate: Int,
    period: Int,
    amplitudePercent: Double,
    stream: Int
  ): AudioTrack {
    val amplitude = (Short.MAX_VALUE * amplitudePercent / 100).roundToInt()
    val samples = ShortArray(period) { sampleIndex ->
      (if (sampleIndex < period / 2) amplitude else -amplitude).toShort()
    }
    val attributes = AudioAttributes.Builder()
      .setLegacyStreamType(stream)
      .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
      .build()
    val format = AudioFormat.Builder()
      .setSampleRate(sampleRate)
      .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
      .build()
    val track = AudioTrack(
      attributes,
      format,
      samples.size * 2,
      AudioTrack.MODE_STATIC,
      AudioManager.AUDIO_SESSION_ID_GENERATE
    )
    try {
      check(track.write(samples, 0, samples.size) == samples.size)
      check(track.state == AudioTrack.STATE_INITIALIZED)
      return track
    } catch (error: RuntimeException) {
      track.release()
      throw error
    }
  }

  private fun minimumVolume(stream: Int): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audioManager.getStreamMinVolume(stream) else 0

  private fun createVolumeLabel(stream: Int, viewId: Int, label: Int): TextView =
    TextView(this).apply {
      setText(label)
      labelFor = viewId
      volumeLabels[stream] = this to label
    }

  private fun createVolumeSlider(stream: Int, viewId: Int, label: Int): SeekBar =
    SeekBar(this).apply {
      id = viewId
      contentDescription = getString(label)
      volumeSliders[stream] = this
      setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
          if (fromUser) {
            try {
              audioManager.setStreamVolume(stream, minimumVolume(stream) + progress, 0)
            } catch (_: SecurityException) {
              Toast.makeText(this@MainActivity, R.string.volume_restricted, Toast.LENGTH_SHORT).show()
            }
            updateVolumes()
          }
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {
          volumeControlStream = stream
          updateVolumes()
        }

        override fun onStopTrackingTouch(seekBar: SeekBar) {}
      })
    }

  private fun updateVolumes() {
    volumeSliders.forEach { (stream, slider) ->
      val minimum = minimumVolume(stream)
      val maximum = audioManager.getStreamMaxVolume(stream)
      val current = audioManager.getStreamVolume(stream)
      slider.max = maximum - minimum
      slider.progress = current - minimum
      val percent = if (maximum > 0) (100.0 * current / maximum).roundToInt().coerceIn(0, 100) else 0
      volumeLabels[stream]?.let { (labelView, label) ->
        val text = getString(R.string.volume_percentage, getString(label), percent)
        labelView.text = text
        slider.contentDescription = text
      }
      val accessibilityAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        stream != AudioManager.STREAM_ACCESSIBILITY || isAccessibilityVolumeServiceEnabled()
      slider.isEnabled = !audioManager.isVolumeFixed && slider.max > 0 && accessibilityAllowed
    }
  }

  private fun updateVolumeVisibility() {
    volumeLabels.forEach { entry ->
      val stream = entry.key
      val label = entry.value
      val visibility = if (stream == selectedSoundStream) View.VISIBLE else View.GONE
      label.first.visibility = visibility
      volumeSliders[stream]?.visibility = visibility
    }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) {
      updateVolumes()
      updateAccessibilityPermissionButton()
    } else {
      holdButtons.forEach { it.isPressed = false }
    }
  }

  override fun onPause() {
    holdButtons.forEach { it.isPressed = false }
    customBeep?.release()
    customBeep = null
    super.onPause()
  }

  override fun onStop() {
    contentResolver.unregisterContentObserver(volumeObserver)
    super.onStop()
  }
}
