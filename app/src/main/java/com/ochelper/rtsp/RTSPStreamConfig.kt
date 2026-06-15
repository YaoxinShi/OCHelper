package com.ochelper.rtsp

data class RTSPStreamConfig(
    val port: Int = 8554,
    val streamPath: String = "/live",
    val cameraFacing: String = "back",   // "back" | "front"
    val width: Int = 1280,
    val height: Int = 720,
    val frameRate: Int = 30,
    val bitrateBps: Int = 2_000_000,
    val audioEnabled: Boolean = false,
)
