package dev.pantherale0.mc40.overlay

/**
 * When true, hardware scans fill an open form barcode field and must not
 * publish grocery webhook events.
 */
object FormScanGate {
    @Volatile
    var consumeScans: Boolean = false
}
