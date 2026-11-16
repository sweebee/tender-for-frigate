package nl.wiebenieuwenhuis.tender.api

/** Deletes a camera with its recordings and tracked objects (Frigate 0.18: DELETE /api/cameras/<name>). */
suspend fun FrigateClient.deleteCamera(name: String, deleteExports: Boolean) {
    send("DELETE", "api/cameras/$name", null, mapOf("delete_exports" to deleteExports.toString()))
}

/** The lines of a service's log ("frigate", "go2rtc" or "nginx"), oldest first. Admin only. */
suspend fun FrigateClient.logLines(service: String): List<String> =
    json("api/logs/$service")["lines"].strings() ?: emptyList()
