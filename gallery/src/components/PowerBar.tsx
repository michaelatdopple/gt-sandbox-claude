interface PowerBarProps {
  label: string
  action: string
  progress: number
  color: string
}

export default function PowerBar({ label, action, progress, color }: PowerBarProps) {
  const segments = 5
  const filled = Math.round(progress * segments)

  const bar = Array.from({ length: segments }, (_, i) =>
    i < filled ? '\u25A0' : '\u25A1'
  ).join(' ')

  return (
    <span className="font-mono text-xs" style={{ color: progress > 0 ? color : '#006666' }}>
      {label}:[{bar}] {action}
    </span>
  )
}
