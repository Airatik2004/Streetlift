package ru.streetlift.tracker.timer

import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import ru.streetlift.tracker.R

/**
 * Проигрывание сигналов с выбором вывода: Bluetooth-гарнитура и/или динамик телефона
 * (независимо), плюс вибрация. Каждому устройству — свой плеер с preferredDevice.
 */
object SignalPlayer {
    private const val TAG = "SignalPlayer"
    private val active = mutableListOf<MediaPlayer>()

    private fun rawUri(ctx: Context, res: Int): Uri =
        Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${ctx.packageName}/$res")

    private val btTypes: Set<Int> by lazy {
        buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= 31) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
        }
    }

    /** Устройства вывода по настройкам. null в списке — «как решит система» (до Android 9). */
    private fun targets(ctx: Context, p: TimerPrefs.Prefs): List<AudioDeviceInfo?> {
        if (!p.soundEnabled) return emptyList()
        if (Build.VERSION.SDK_INT < 28) return if (p.outSpeaker || p.outBt) listOf(null) else emptyList()
        val am = ctx.getSystemService(AudioManager::class.java)
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val list = mutableListOf<AudioDeviceInfo?>()
        if (p.outBt) {
            // Для звука нужен «музыкальный» вывод наушников (A2DP / LE Audio). SCO — гарнитура для звонков,
            // вне звонка она молчит, поэтому берём её только если другого нет.
            val order = buildList {
                add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
                if (Build.VERSION.SDK_INT >= 31) {
                    add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                    add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
                }
                add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            }
            order.firstNotNullOfOrNull { t -> outs.firstOrNull { it.type == t } }?.let { list += it }
        }
        // Динамик: если включён, или если гарнитуры сейчас нет и включено «Без наушников — в динамик»
        val toSpeaker = p.outSpeaker || (p.outBt && p.speakerFallback && list.isEmpty())
        if (toSpeaker) outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { list += it }
        return list
    }

    /** Когда последний раз что-то звучало (elapsedRealtime). */
    @Volatile
    private var lastSoundAt = 0L

    /** После такой тишины вывод (динамик / наушники) «засыпает» и глотает начало звука. */
    private const val SLEEP_AFTER_MS = 2_500L

    /** Сколько ждать после «будящей» тишины. */
    private const val WAKE_MS = 350L

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Разбудить вывод заранее (перед отсчётом): неслышная тишина ~0,8 с. */
    fun warmUp(ctx: Context) {
        lastSoundAt = android.os.SystemClock.elapsedRealtime()
        playNow(ctx, rawUri(ctx, R.raw.timer_silence), 900)
    }

    /**
     * Сыграть звук. Если перед этим долго было тихо — сначала тишина, чтобы вывод проснулся,
     * и сам звук через [WAKE_MS]: иначе первый пик пропадает целиком.
     */
    private fun play(ctx: Context, uri: Uri, maxMs: Long?) {
        val now = android.os.SystemClock.elapsedRealtime()
        val asleep = now - lastSoundAt > SLEEP_AFTER_MS
        lastSoundAt = now
        if (asleep) {
            playNow(ctx, rawUri(ctx, R.raw.timer_silence), 900)
            main.postDelayed({ playNow(ctx, uri, maxMs) }, WAKE_MS)
        } else {
            playNow(ctx, uri, maxMs)
        }
    }

    private fun playNow(ctx: Context, uri: Uri, maxMs: Long?) {
        val p = TimerPrefs.load(ctx)
        val app = ctx.applicationContext
        for (dev in targets(app, p)) {
            try {
                val mp = MediaPlayer()
                // Динамик — поток будильника (слышно в беззвучном режиме и при нулевой громкости медиа),
                // гарнитура — мультимедиа (громкость как у музыки в наушниках)
                val toBt = dev != null && dev.type in btTypes
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(if (toBt) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                mp.setDataSource(app, uri)
                if (dev != null && Build.VERSION.SDK_INT >= 28) mp.setPreferredDevice(dev)
                val v = p.volume.coerceIn(0.1f, 1f)
                mp.setVolume(v, v)
                mp.setOnCompletionListener { done -> release(done) }
                mp.setOnErrorListener { bad, _, _ -> release(bad); true }
                mp.prepare()
                mp.start()
                synchronized(active) { active += mp }
                if (maxMs != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ release(mp) }, maxMs)
                }
            } catch (e: Exception) {
                Log.w(TAG, "play failed", e)
            }
        }
    }

    private fun release(mp: MediaPlayer) {
        synchronized(active) { if (!active.remove(mp)) return }
        try {
            if (mp.isPlaying) mp.stop()
        } catch (_: Exception) {
        }
        mp.release()
    }

    fun pip(ctx: Context) = play(ctx, rawUri(ctx, R.raw.timer_pip), null)

    /** Сигнал окончания: встроенный свисток или выбранная мелодия (не дольше 3 с). */
    fun signal(ctx: Context) {
        val p = TimerPrefs.load(ctx)
        val uri = p.soundUri?.let { Uri.parse(it) } ?: rawUri(ctx, R.raw.timer_whistle)
        play(ctx, uri, 3_000)
        vibrate(ctx, TimerPrefs.VIBRATION_MS)
    }

    fun vibrate(ctx: Context, ms: Long) {
        if (!TimerPrefs.load(ctx).vibration) return
        try {
            val vib: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Vibrator::class.java)
            }
            vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Log.w(TAG, "vibrate failed", e)
        }
    }

    fun stopAll() {
        val list = synchronized(active) { active.toList() }
        list.forEach { release(it) }
    }
}
