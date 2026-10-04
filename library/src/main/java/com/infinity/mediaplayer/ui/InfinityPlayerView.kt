package com.infinity.mediaplayer.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.infinity.mediaplayer.core.InfinityPlayer

@OptIn(UnstableApi::class)
class InfinityPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    val playerView: PlayerView = PlayerView(context).apply {
        useController = false
    }

    init {
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun attachPlayer(infinityPlayer: InfinityPlayer) {
        playerView.player = infinityPlayer.exoPlayer
    }

    fun detachPlayer() {
        playerView.player = null
    }
}
