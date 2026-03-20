import { useState, useEffect, useCallback, useRef } from 'react'
import CardCarousel from './components/CardCarousel'
import PowerBar from './components/PowerBar'
import ScanlineOverlay from './components/ScanlineOverlay'
import { useGalleryBridge } from './hooks/useGalleryBridge'
import { useHoldDetector } from './hooks/useHoldDetector'

export default function App() {
  const { getHistory, launchActivity, prepareScanner, openScanner, closeScanner } = useGalleryBridge()
  const [items, setItems] = useState<GalleryHistoryEntry[]>([])
  const [selectedIndex, setSelectedIndex] = useState(0)
  const [holdAProgress, setHoldAProgress] = useState(0)
  const [holdBProgress, setHoldBProgress] = useState(0)
  const [scannerOpen, setScannerOpen] = useState(false)
  const scannerPreparedRef = useRef(false)

  // Load history on mount
  useEffect(() => {
    setItems(getHistory())
  }, [getHistory])

  // Expose scrollToIndex for native to call after page load
  useEffect(() => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (window as any).__setScrollIndex = (index: number) => {
      setSelectedIndex(index)
    }
  }, [])

  const selectNext = useCallback(() => {
    setSelectedIndex(i => (items.length > 0 ? (i + 1) % items.length : 0))
  }, [items.length])

  const selectPrev = useCallback(() => {
    setSelectedIndex(i => (items.length > 0 ? (i - 1 + items.length) % items.length : 0))
  }, [items.length])

  // Hold A: open/close QR scanner overlay (toggle)
  // Tap A: navigate previous (no-op while scanner is open)
  const holdA = useHoldDetector({
    holdDurationMs: 800,
    onTap: () => {
      if (!scannerOpen) {
        selectPrev()
      }
    },
    onHoldProgress: (progress) => {
      setHoldAProgress(progress)
      // Prepare camera at 20% hold progress for zero-latency activation
      if (!scannerOpen && progress >= 0.2 && !scannerPreparedRef.current) {
        scannerPreparedRef.current = true
        prepareScanner()
      }
    },
    onHoldComplete: () => {
      setHoldAProgress(0)
      scannerPreparedRef.current = false
      if (scannerOpen) {
        closeScanner()
        setScannerOpen(false)
      } else {
        openScanner()
        setScannerOpen(true)
      }
    },
    onHoldCancelled: () => {
      setHoldAProgress(0)
      scannerPreparedRef.current = false
    },
  })

  // Hold B: launch selected game
  // Tap B: navigate next
  const holdB = useHoldDetector({
    holdDurationMs: 600,
    onTap: selectNext,
    onHoldProgress: setHoldBProgress,
    onHoldComplete: () => {
      setHoldBProgress(0)
      const item = items[selectedIndex]
      if (item && !item.manifestUrl.startsWith('demo://')) {
        launchActivity(item.manifestUrl)
      }
    },
    onHoldCancelled: () => { setHoldBProgress(0) },
  })

  // Listen for button events via Loop SDK
  useEffect(() => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent).detail as { button: string; state: string }
      const isDown = detail.state === 'down'

      switch (detail.button) {
        case 'A':
          if (isDown) holdA.onDown(); else holdA.onUp()
          break
        case 'B':
          if (isDown) holdB.onDown(); else holdB.onUp()
          break
        // C is handled natively (sleep/settings) — not forwarded to gallery
      }
    }

    window.addEventListener('loop:button', handler)
    return () => window.removeEventListener('loop:button', handler)
  }, [holdA, holdB])

  // 3DOF tilt navigation via Loop.motion
  const lastTiltRef = useRef(0)
  useEffect(() => {
    if (typeof window.Loop === 'undefined' || !window.Loop.motion.isSupported()) return

    let sub: { stop(): void } | null = null

    window.Loop.motion.start({ frequency: 30 }).then(s => {
      sub = s
      s.on('data', (data) => {
        const tiltX = data.gravity?.x ?? 0
        if (tiltX > 3 && lastTiltRef.current <= 3) selectNext()
        if (tiltX < -3 && lastTiltRef.current >= -3) selectPrev()
        lastTiltRef.current = tiltX
      })
    })

    return () => { sub?.stop() }
  }, [selectNext, selectPrev])

  return (
    <div className="w-[800px] h-[800px] bg-crt relative flex flex-col">
      <ScanlineOverlay />

      {/* Title */}
      <div className="text-center pt-6">
        <h1 className="font-mono text-cyan text-lg font-bold tracking-wider">CARTRIDGE LIBRARY</h1>
      </div>

      {/* Carousel */}
      <div className="flex-1">
        <CardCarousel items={items} selectedIndex={selectedIndex} />
      </div>

      {/* HUD */}
      <div className="flex justify-center gap-12 pb-16">
        <PowerBar label="A" action={scannerOpen ? "CLOSE" : "SCAN"} progress={holdAProgress} color="#33FF33" />
        <PowerBar label="B" action="GO" progress={holdBProgress} color="#00FFFF" />
      </div>
    </div>
  )
}
