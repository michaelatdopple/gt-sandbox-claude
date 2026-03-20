import { useRef, useCallback, useEffect } from 'react'

interface HoldDetectorOptions {
  holdDurationMs: number
  tapThresholdMs?: number
  onTap: () => void
  onHoldProgress: (progress: number) => void
  onHoldComplete: () => void
  onHoldCancelled: () => void
}

export function useHoldDetector({
  holdDurationMs,
  tapThresholdMs = 150,
  onTap,
  onHoldProgress,
  onHoldComplete,
  onHoldCancelled,
}: HoldDetectorOptions) {
  const downTime = useRef(0)
  const rafId = useRef(0)
  const completed = useRef(false)

  const onDown = useCallback(() => {
    downTime.current = performance.now()
    completed.current = false

    const tick = () => {
      const elapsed = performance.now() - downTime.current
      const progress = Math.min(elapsed / holdDurationMs, 1)
      onHoldProgress(progress)

      if (progress >= 1 && !completed.current) {
        completed.current = true
        onHoldComplete()
      } else if (!completed.current) {
        rafId.current = requestAnimationFrame(tick)
      }
    }
    rafId.current = requestAnimationFrame(tick)
  }, [holdDurationMs, onHoldProgress, onHoldComplete])

  const onUp = useCallback(() => {
    cancelAnimationFrame(rafId.current)
    const elapsed = performance.now() - downTime.current

    if (completed.current) {
      onHoldProgress(0)
    } else if (elapsed < tapThresholdMs) {
      onHoldProgress(0)
      onTap()
    } else {
      onHoldProgress(0)
      onHoldCancelled()
    }
  }, [tapThresholdMs, onTap, onHoldProgress, onHoldCancelled])

  useEffect(() => () => cancelAnimationFrame(rafId.current), [])

  return { onDown, onUp }
}
