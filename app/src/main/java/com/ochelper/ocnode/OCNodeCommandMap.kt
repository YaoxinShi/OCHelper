package com.ochelper.ocnode

/**
 * Bridges OCHelper's internal capability IDs to the OpenClaw gateway's canonical
 * node capability/command vocabulary.
 *
 * The gateway accepts a node command only when its name matches the per-platform
 * allowlist defined in the gateway's node-command-policy. Internal IDs such as
 * `camera.take_photo` or `screen.screenshot` are not canonical, so the gateway
 * silently drops them from the node's declared command set, leaving the node
 * unable to be invoked. We therefore advertise canonical names here and translate
 * incoming invokes back to our internal capability IDs.
 *
 * Canonical Android command vocabulary (subset we support):
 *   device.info, device.status        -> DeviceInfoCapability (battery, storage, ...)
 *   camera.snap                        -> CameraCapability (dangerous: operator must
 *                                         add "camera.snap" to gateway.nodes.allowCommands)
 *   location.get                       -> LocationCapability
 *   notifications.list                 -> NotificationCapability
 *   photos.latest                      -> GalleryCapability
 */
object OCNodeCommandMap {

    /** Internal capability id -> canonical OpenClaw capability short name. */
    private val capabilityName: Map<String, String> = mapOf(
        "device.info" to "device",
        "camera.take_photo" to "camera",
        "location.get" to "location",
        "notifications.list" to "notifications",
        "gallery.list" to "photos",
    )

    /** Internal capability id -> canonical OpenClaw command names it serves. */
    private val capabilityCommands: Map<String, List<String>> = mapOf(
        "device.info" to listOf("device.info", "device.status"),
        "camera.take_photo" to listOf("camera.snap"),
        "location.get" to listOf("location.get"),
        "notifications.list" to listOf("notifications.list"),
        "gallery.list" to listOf("photos.latest"),
    )

    /** Canonical command name -> internal capability id (for invoke dispatch). */
    private val commandToCapability: Map<String, String> =
        capabilityCommands.entries
            .flatMap { (capId, cmds) -> cmds.map { it to capId } }
            .toMap()

    /** Canonical caps to advertise for the given enabled internal capability ids. */
    fun caps(enabledCapabilityIds: List<String>): List<String> =
        enabledCapabilityIds.mapNotNull { capabilityName[it] }.distinct()

    /** Canonical commands to advertise for the given enabled internal capability ids. */
    fun commands(enabledCapabilityIds: List<String>): List<String> =
        enabledCapabilityIds.flatMap { capabilityCommands[it] ?: emptyList() }.distinct()

    /**
     * Resolve an incoming gateway command name to our internal capability id.
     * Falls back to the command itself so already-canonical ids (e.g. device.info)
     * still dispatch correctly.
     */
    fun resolveCapabilityId(command: String): String =
        commandToCapability[command] ?: command
}
