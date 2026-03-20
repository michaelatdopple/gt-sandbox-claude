import { useCallback } from 'react'

export function useGalleryBridge() {
  const getHistory = useCallback((): GalleryHistoryEntry[] => {
    if (typeof window.LoopInternal === 'undefined') return []
    return window.LoopInternal.gallery.getHistory()
  }, [])

  const launchActivity = useCallback((manifestUrl: string) => {
    if (typeof window.LoopInternal !== 'undefined') {
      window.LoopInternal.gallery.launchActivity(manifestUrl)
    }
  }, [])

  const prepareScanner = useCallback(() => {
    if (typeof window.LoopInternal !== 'undefined') {
      window.LoopInternal.gallery.prepareScanner()
    }
  }, [])

  const openScanner = useCallback(() => {
    if (typeof window.LoopInternal !== 'undefined') {
      window.LoopInternal.gallery.openScanner()
    }
  }, [])

  const closeScanner = useCallback(() => {
    if (typeof window.LoopInternal !== 'undefined') {
      window.LoopInternal.gallery.closeScanner()
    }
  }, [])

  return { getHistory, launchActivity, prepareScanner, openScanner, closeScanner }
}
