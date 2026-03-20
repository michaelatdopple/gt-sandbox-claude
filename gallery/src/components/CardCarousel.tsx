import { useRef, useEffect } from 'react'
import CartridgeCard from './CartridgeCard'

interface CardCarouselProps {
  items: GalleryHistoryEntry[]
  selectedIndex: number
}

export default function CardCarousel({ items, selectedIndex }: CardCarouselProps) {
  const containerRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const container = containerRef.current
    if (!container) return
    const cardWidth = 468 + 24 // card + gap
    const offset = selectedIndex * cardWidth - (800 - cardWidth) / 2
    container.scrollTo({ left: Math.max(0, offset), behavior: 'smooth' })
  }, [selectedIndex])

  if (items.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center h-full">
        <p className="font-mono text-phosphor text-lg">{'>'} NO CARTRIDGES_</p>
        <p className="font-mono text-phosphor-dim text-sm mt-3">SCAN QR TO ADD</p>
      </div>
    )
  }

  return (
    <div
      ref={containerRef}
      className="flex items-center gap-6 overflow-x-hidden px-[168px] h-full scroll-smooth"
    >
      {items.map((item, i) => (
        <CartridgeCard
          key={item.projectId}
          activityName={item.activityName}
          launchCount={item.launchCount}
          thumbnailUrl={item.thumbnailUrl}
          isSelected={i === selectedIndex}
        />
      ))}
    </div>
  )
}
