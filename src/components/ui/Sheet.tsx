"use client";

import { useEffect } from "react";
import { X } from "lucide-react";

type Props = { title: string; onClose: () => void; children: React.ReactNode };

// Modal panel: slides up from the bottom on phones, centred on wider screens.
// Closes on the backdrop, the X, or Escape.
export function Sheet({ title, onClose, children }: Props) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={title}
      className="fixed inset-0 z-50 flex flex-col justify-end md:items-center md:justify-center"
      onClick={onClose}
    >
      <div className="absolute inset-0 bg-black/40" />
      <div
        className="relative w-full rounded-t-2xl bg-surface text-ink shadow-card md:max-w-sm md:rounded-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between px-4 py-3">
          <h2 className="font-display font-semibold">{title}</h2>
          <button type="button" onClick={onClose} aria-label="Close" className="rounded-full p-1 hover:bg-surface-2">
            <X className="size-5" />
          </button>
        </div>
        <div className="px-4 pb-4 pb-safe">{children}</div>
      </div>
    </div>
  );
}
