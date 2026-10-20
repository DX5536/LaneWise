package de.lanewise.offline

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Downloads vector map tiles (OpenFreeMap, zoom 8–14; OpenMapTiles vector data over-zooms beyond 14)
 * for a region so the map renders without internet. Both light and dark styles share the tiles.
 *
 * MapLibre's offline download fetches the style over HTTP and cannot read our `asset://` styles ("Unable to parse
 * resourceUrl", the download stayed at 0 % - owner, 2026-10-20). So the region is defined with OpenFreeMap's own
 * [DOWNLOAD_STYLE]: it uses the same vector source (`https://tiles.openfreemap.org/planet`) and the same Noto Sans
 * glyphs as our styles, and the tiles land in the shared cache by URL, where our styles find them offline.
 */
class OfflineTiles(private val context: Context) {

    suspend fun download(region: OfflineGraphBuilder.Region, progress: (Int) -> Unit) {
        val bounds = LatLngBounds.from(region.north, region.east, region.south, region.west)
        val def = OfflineTilePyramidRegionDefinition(
            DOWNLOAD_STYLE, bounds, 8.0, 14.0, context.resources.displayMetrics.density,
        )
        val manager = OfflineManager.getInstance(context)
        val created = suspendCancellableCoroutine { cont ->
            manager.createOfflineRegion(def, region.id.toByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) { cont.resume(offlineRegion) }
                override fun onError(error: String) { cont.resumeWithException(IllegalStateException(error)) }
            })
        }
        suspendCancellableCoroutine { cont ->
            created.setObserver(object : OfflineRegion.OfflineRegionObserver {
                override fun onStatusChanged(status: OfflineRegionStatus) {
                    val pct = if (status.requiredResourceCount > 0)
                        (100.0 * status.completedResourceCount / status.requiredResourceCount).toInt() else 0
                    progress(pct)
                    android.util.Log.i("LaneWise", "offline tiles ${status.completedResourceCount}/${status.requiredResourceCount}")
                    if (status.isComplete) {
                        created.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        created.setObserver(null)
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
                override fun onError(error: OfflineRegionError) {
                    // Transient errors are retried by MapLibre; only report.
                    android.util.Log.w("LaneWise", "offline tiles: ${error.reason} ${error.message}")
                    progress(-1)
                }
                override fun mapboxTileCountLimitExceeded(limit: Long) {
                    created.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Tile limit $limit exceeded"))
                }
            })
            created.setDownloadState(OfflineRegion.STATE_ACTIVE)
            cont.invokeOnCancellation { created.setDownloadState(OfflineRegion.STATE_INACTIVE) }
        }
    }

    companion object {
        /** Style the region is downloaded with (same tile source and glyphs as `assets/styles/lanewise_*.json`). */
        const val DOWNLOAD_STYLE = "https://tiles.openfreemap.org/styles/liberty"
    }
}
