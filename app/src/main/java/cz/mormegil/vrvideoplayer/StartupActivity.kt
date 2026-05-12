package cz.mormegil.vrvideoplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

class StartupActivity : ComponentActivity() {
    companion object {
        private const val TAG = "VRVideoPlayerS"
    }

    private val videoGalleryChooser =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia(), ::initWithVideo)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "onCreate()")

        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val viewUri = intent.data
                if (viewUri != null) {
                    initWithVideo(viewUri)
                } else {
                    startMulticastPlayer()
                }
            }

            // Main launcher mode: connect directly to RTP multicast LOW stream.
            else -> {
                startMulticastPlayer()
            }
        }
    }

    @Suppress("unused")
    private fun chooseVideoFromGallery() {
        videoGalleryChooser.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }

    private fun initWithVideo(videoUri: Uri?) {
        if (videoUri == null) {
            Log.d(TAG, "No video chosen, starting multicast mode")
            startMulticastPlayer()
            return
        }
        Log.d(TAG, "initWithVideo: $videoUri")

        val intent = Intent(this, MainActivity::class.java)
        intent.data = videoUri
        startActivity(intent)
        finish()
    }

    private fun startMulticastPlayer() {
        Log.d(TAG, "Starting RTP multicast mode")
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra(MainActivity.EXTRA_RTP_MULTICAST, true)
        intent.putExtra(MainActivity.EXTRA_RTP_GROUP, "239.0.0.2")
        intent.putExtra(MainActivity.EXTRA_RTP_PORT, 5006)
        intent.putExtra(MainActivity.EXTRA_RTP_PAYLOAD_TYPE, 97)
        intent.putExtra(MainActivity.EXTRA_RTP_WIDTH, 768)
        intent.putExtra(MainActivity.EXTRA_RTP_HEIGHT, 384)
        startActivity(intent)
        finish()
    }
}
