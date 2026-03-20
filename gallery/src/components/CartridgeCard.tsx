import { useEffect, useState } from 'react'

interface CartridgeCardProps {
  activityName: string
  launchCount: number
  thumbnailUrl: string | null
  isSelected: boolean
}

export default function CartridgeCard({ activityName, launchCount, thumbnailUrl, isSelected }: CartridgeCardProps) {
  const [showBrackets, setShowBrackets] = useState(true)
  const [imgFailed, setImgFailed] = useState(false)

  useEffect(() => { setImgFailed(false) }, [thumbnailUrl])

  useEffect(() => {
    if (!isSelected) return
    const interval = setInterval(() => setShowBrackets(v => !v), 500)
    return () => clearInterval(interval)
  }, [isSelected])

  const borderColor = isSelected ? '#00FFFF' : '#33FF33'
  const scale = isSelected ? 'scale-100' : 'scale-90 opacity-60'

  return (
    <div
      className={`flex-shrink-0 w-[468px] h-[468px] flex flex-col items-center pt-8 p-5 rounded-full transition-all duration-200 ${scale}`}
      style={{ border: `2px solid ${borderColor}`, background: '#0A0A0A' }}
    >
      {/* Icon area */}
      <div className="w-[234px] h-[234px] bg-neutral-900 mt-2 flex items-center justify-center overflow-hidden rounded-xl">
        {thumbnailUrl && !imgFailed ? (
          <img
            src={thumbnailUrl}
            className="w-full h-full object-cover"
            alt={activityName}
            onError={() => setImgFailed(true)}
          />
        ) : (
          <span className="text-phosphor text-[62px] font-mono">{activityName.charAt(0)}</span>
        )}
      </div>

      {/* Cartridge body */}
      <div className="w-[234px] h-3 mt-2 bg-amber" />
      <div className="w-[234px] font-mono text-[13px] text-phosphor text-center tracking-widest">
        {'|'.repeat(20)}
      </div>

      {/* Title */}
      <div className="mt-3 font-mono text-[23px] text-center" style={{ color: isSelected ? '#00FFFF' : '#1A6B1A' }}>
        {isSelected && showBrackets ? `[\u25BA ${activityName} \u25C4]` : `   ${activityName}   `}
      </div>

      {/* Launch count */}
      <div className="mt-1 font-mono text-[18px] text-amber">
        {'\u25BA'} LAUNCHED {launchCount}x
      </div>
    </div>
  )
}
