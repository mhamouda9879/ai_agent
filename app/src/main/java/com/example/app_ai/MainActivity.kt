package com.example.app_ai

import android.Manifest
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val SERVER_URL = "wss://s2s.coralcell.com/media"
    private val SAMPLE_RATE = 8000
    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    private lateinit var webSocket: WebSocket
    private lateinit var audioRecord: AudioRecord
    private lateinit var audioTrack: AudioTrack
    private val playbackQueue = LinkedBlockingQueue<ByteArray>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isStreaming by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AudioStreamerUI(
                isStreaming = isStreaming,
                onStart = { checkPermissionsAndStart() },
                onStop = { stopStreaming() }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
    }

    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startStreaming()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                1
            )
        }
    }

    private fun startStreaming() {
        isStreaming = true
        setupAudioComponents()
        connectToWebSocket()

        scope.launch { receiveAndPlayAudio() }
        scope.launch { recordAndSendAudio() }
    }

    private fun stopStreaming() {
        isStreaming = false
        playbackQueue.clear()
        scope.coroutineContext.cancelChildren()

        if (::webSocket.isInitialized) webSocket.close(1000, "Stopping")
        if (::audioRecord.isInitialized) {
            audioRecord.stop()
            audioRecord.release()
        }
        if (::audioTrack.isInitialized) {
            audioTrack.stop()
            audioTrack.release()
        }
    }

    private fun setupAudioComponents() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            BUFFER_SIZE
        )
        audioRecord.startRecording()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(BUFFER_SIZE)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack.play()
    }

    private fun connectToWebSocket() {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(SERVER_URL).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val startEvent = JSONObject()
                startEvent.put("event", "start")
                startEvent.put("streamSid", "12345")
                webSocket.send(startEvent.toString())
                Log.d("WebSocket", "Connected to $SERVER_URL")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.getString("event") == "media") {
                        val payload = json.getJSONObject("media").getString("payload")
                        val ulawData = Base64.decode(payload, Base64.NO_WRAP)
                        val pcmData = decodeULaw(ulawData)
                        playbackQueue.offer(pcmData)
                    }
                } catch (e: Exception) {
                    Log.e("WebSocket", "Error parsing message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebSocket", "WebSocket error: ${t.message}")
                showErrorDialog("WebSocket Error", t.message ?: "Unknown error")
            }
        })
    }

    private suspend fun recordAndSendAudio() {
        val buffer = ByteArray(BUFFER_SIZE)
        while (isStreaming) {
            val bytesRead = audioRecord.read(buffer, 0, buffer.size)
            if (bytesRead > 0) {
                val amplifiedBuffer = amplifyPCM(buffer, 2.0f )
                val ulawData = encodePCMToULaw(amplifiedBuffer)
                val payload = Base64.encodeToString(ulawData, Base64.NO_WRAP)
                val json = JSONObject().apply {
                    put("event", "media")
                    put("media", JSONObject().put("payload", payload))
                }
                webSocket.send(json.toString())
                Log.d("WebSocket", "Sent audio to server")
            }
        }
    }

    private fun amplifyPCM(pcmData: ByteArray, gain: Float): ByteArray {
        val amplifiedData = ByteArray(pcmData.size)
        for (i in pcmData.indices step 2) {
            val sample = ((pcmData[i].toInt() and 0xFF) or (pcmData[i + 1].toInt() shl 8))
            val amplifiedSample = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            amplifiedData[i] = (amplifiedSample and 0xFF).toByte()
            amplifiedData[i + 1] = ((amplifiedSample shr 8) and 0xFF).toByte()
        }
        return amplifiedData
    }
    private suspend fun receiveAndPlayAudio() {
        while (isStreaming) {
            val audioData = playbackQueue.poll() ?: continue
            audioTrack.write(audioData, 0, audioData.size)
        }
    }

    private fun encodePCMToULaw(pcmData: ByteArray): ByteArray {
        val ulawData = ByteArray(pcmData.size / 2)
        for (i in ulawData.indices) {
            ulawData[i] = pcmToULaw(
                (pcmData[i * 2].toInt() and 0xFF) or (pcmData[i * 2 + 1].toInt() shl 8)
            )
        }
        return ulawData
    }

    private fun decodeULaw(ulawData: ByteArray): ByteArray {
        val pcmData = ByteArray(ulawData.size * 2)
        for (i in ulawData.indices) {
            val pcmValue = ulawToPcm(ulawData[i].toInt())
            pcmData[i * 2] = pcmValue.toByte()
            pcmData[i * 2 + 1] = (pcmValue shr 8).toByte()
        }
        return pcmData
    }

    private fun pcmToULaw(pcmValue: Int): Byte {
        val sign = if (pcmValue < 0) 0x80 else 0
        val magnitude = Math.min(0x7FFF, Math.abs(pcmValue))
        val exponent = (15 - Integer.numberOfLeadingZeros(magnitude shr 7)).coerceAtMost(7)
        val mantissa = (magnitude shr (exponent + 3)) and 0x0F
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

    private fun ulawToPcm(ulawByte: Int): Int {
        val ulaw = ulawByte xor 0xFF
        val sign = ulaw and 0x80
        val exponent = (ulaw and 0x70) shr 4
        val mantissa = ulaw and 0x0F
        var sample = ((mantissa shl 4) + 8) shl (exponent + 2)
        if (sign != 0) sample = -sample
        return sample
    }

    private fun showErrorDialog(title: String, message: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }
}

@Composable
fun AudioStreamerUI(
    isStreaming: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = if (isStreaming) "Streaming Audio..." else "Press Start to Stream")
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = { if (isStreaming) onStop() else onStart() }) {
            Text(text = if (isStreaming) "Stop Streaming" else "Start Streaming")
        }
    }
}
