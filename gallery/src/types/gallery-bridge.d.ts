/**
 * Re-export GalleryHistoryEntry as ambient type for gallery app use.
 * Canonical definition lives in sdk/internal-sdk-dx.d.ts (GalleryAPI.getHistory).
 */
declare interface GalleryHistoryEntry {
  projectId: string
  activityName: string
  manifestUrl: string
  thumbnailUrl: string | null
  launchCount: number
}
