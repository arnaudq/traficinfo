/** Build text-only UI without interpreting published data as HTML. */
export function element<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  text?: string | null,
  className?: string,
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  if (text) node.textContent = text;
  if (className) node.className = className;
  return node;
}
