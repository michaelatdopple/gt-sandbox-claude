/**
 * Remapped deadzone: values below threshold snap to zero,
 * values above are linearly rescaled so output reaches ±1 smoothly.
 */
export function applyDeadzone(value: number, deadzone: number, maxAngle: number): number {
  if (deadzone <= 0) return Math.max(-1, Math.min(1, value / maxAngle));

  const absValue = Math.abs(value);
  if (absValue <= deadzone) return 0;

  const range = maxAngle - deadzone;
  if (range <= 0) return 0;

  const normalized = (absValue - deadzone) / range;
  return Math.sign(value) * Math.min(1, normalized);
}
