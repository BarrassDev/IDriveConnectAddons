package io.bimmergestalt.idriveconnectaddons.speedcams.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import io.bimmergestalt.idriveconnectaddons.speedcams.TAG
import java.util.Locale

/**
 * Plays a chime and speaks the alert as navigation guidance audio,
 * which ducks music and follows the phone's audio route (Bluetooth to the car when connected)
 */
class AlertSounder(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var ttsReady = false
    private val focusRequest: AudioFocusRequest? = if (Build.VERSION.SDK_INT >= 26) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
    } else null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> onTtsInit(status) }

    private fun onTtsInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts.setAudioAttributes(attributes)
            val uk = Locale.UK
            if (tts.isLanguageAvailable(uk) >= TextToSpeech.LANG_AVAILABLE) tts.language = uk
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { abandonFocus() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { abandonFocus() }
            })
        } else {
            Log.w(TAG, "Text to speech is unavailable, alerts will only chime")
        }
    }

    fun alert(spoken: String?, urgent: Boolean = false) {
        requestFocus()
        chime(urgent)
        if (spoken != null && ttsReady) {
            // let the chime finish before speaking
            handler.postDelayed({
                tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "alert-${System.currentTimeMillis()}")
            }, 450)
        } else {
            handler.postDelayed({ abandonFocus() }, 800)
        }
    }

    private fun chime(urgent: Boolean) {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            tone.startTone(if (urgent) ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD else ToneGenerator.TONE_PROP_BEEP2, 350)
            handler.postDelayed({ tone.release() }, 600)
        } catch (e: Exception) {
            Log.w(TAG, "Could not play the alert chime", e)
        }
    }

    private fun requestFocus() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                audioManager.requestAudioFocus(focusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        } catch (e: Exception) {}
    }

    private fun abandonFocus() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                audioManager.abandonAudioFocusRequest(focusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {}
    }

    fun shutdown() {
        tts.shutdown()
    }
}
