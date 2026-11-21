package com.openautolink.app.input

/** Scales touch coordinates from SurfaceView pixel space to video/touchscreen space. */
object TouchScaler {

    /**
     * Simple linear scale (for letterbox mode where video maps 1:1 to surface).
     */
    fun scalePoint(
        surfaceX: Float,
        surfaceY: Float,
        surfaceWidth: Int,
        surfaceHeight: Int,
        videoWidth: Int,
        videoHeight: Int
    ): Pair<Float, Float> {
        return Pair(
            scale(surfaceX, surfaceWidth, videoWidth),
            scale(surfaceY, surfaceHeight, videoHeight)
        )
    }

    fun scale(surfaceCoord: Float, surfaceDimension: Int, videoDimension: Int): Float {
        if (surfaceDimension <= 0 || videoDimension <= 0) return 0f
        val scaled = surfaceCoord * videoDimension / surfaceDimension
        return scaled.coerceIn(0f, videoDimension.toFloat())
    }
}
