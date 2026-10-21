"use client";

import { useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import { Globe, Lock, Loader2 } from "lucide-react";
import { createClient } from "@/lib/supabase/client";
import { WaveMark } from "@/components/brand/Logo";

export type AccountType = "public" | "private";

const OPTIONS = [
  {
    value: "public",
    Icon: Globe,
    title: "Public",
    text: "Anyone on Dheu can see your posts, stories and followers, and can follow you without asking.",
  },
  {
    value: "private",
    Icon: Lock,
    title: "Private",
    text: "Only people you approve can see your posts and stories. Following you takes a request.",
  },
] as const;

type PickerProps = {
  name?: string;
  required?: boolean;
  // Uncontrolled (inside a form): defaultValue. Controlled: value + onChange.
  defaultValue?: AccountType;
  value?: AccountType | null;
  onChange?: (v: AccountType) => void;
};

// Two radio cards: public or private.
export function AccountTypePicker({ name = "accountType", required, defaultValue, value, onChange }: PickerProps) {
  const controlled = onChange !== undefined;
  return (
    <div role="radiogroup" className="space-y-2">
      {OPTIONS.map(({ value: v, Icon, title, text }) => (
        <label
          key={v}
          className="flex cursor-pointer items-start gap-3 rounded-xl border border-line p-3.5 transition has-checked:border-brand has-checked:bg-brand-soft/40"
        >
          <input
            type="radio"
            name={name}
            value={v}
            required={required}
            {...(controlled ? { checked: value === v, onChange: () => onChange(v) } : { defaultChecked: defaultValue === v })}
            className="mt-1 accent-brand"
          />
          <span className="min-w-0">
            <span className="flex items-center gap-1.5 font-semibold">
              <Icon className="size-4" /> {title}
            </span>
            <span className="mt-0.5 block text-sm text-ink-muted">{text}</span>
          </span>
        </label>
      ))}
    </div>
  );
}

async function savePrivacy(type: AccountType): Promise<string | null> {
  const { error } = await createClient().rpc("set_account_privacy", { p_private: type === "private" });
  return error ? "Couldn't save that. Please try again." : null;
}

// Shown instead of the app to members who joined before accounts could be
// public, until they pick one (privacy_chosen_at is null).
export function ChoosePrivacy() {
  const [choice, setChoice] = useState<AccountType | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const router = useRouter();

  async function save() {
    if (!choice) return;
    setBusy(true);
    setError(null);
    const err = await savePrivacy(choice);
    if (err) {
      setBusy(false);
      return setError(err);
    }
    router.refresh();
  }

  return (
    <div className="flex min-h-dvh items-center justify-center px-4 py-10">
      <div className="card w-full max-w-md p-6">
        <WaveMark size={36} />
        <h1 className="mt-4 font-display text-xl font-semibold">Public or private?</h1>
        <p className="mt-1.5 text-sm text-ink-muted">
          Accounts on Dheu can now be public. Until today every account was private. Choose what suits you; you can
          change it any time in Settings.
        </p>
        <div className="mt-5">
          <AccountTypePicker value={choice} onChange={setChoice} />
        </div>
        {error && <p className="mt-3 text-sm text-danger">{error}</p>}
        <button type="button" onClick={save} disabled={!choice || busy} className="btn-primary mt-5 w-full">
          {busy && <Loader2 className="size-4 animate-spin" />} Continue
        </button>
      </div>
    </div>
  );
}

// Settings → Account privacy.
export function PrivacySettings({ isPrivate }: { isPrivate: boolean }) {
  const saved: AccountType = isPrivate ? "private" : "public";
  const [choice, setChoice] = useState<AccountType>(saved);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [, startTransition] = useTransition();
  const router = useRouter();

  async function save() {
    const question =
      choice === "public"
        ? "Make your account public? Anyone on Dheu will see your posts and stories, and pending follow requests will be approved."
        : "Make your account private? New followers will need your approval. People who already follow you will stay.";
    if (!confirm(question)) return;
    setBusy(true);
    setError(null);
    const err = await savePrivacy(choice);
    setBusy(false);
    if (err) return setError(err);
    startTransition(() => router.refresh());
  }

  return (
    <div>
      <AccountTypePicker value={choice} onChange={setChoice} />
      {error && <p className="mt-3 text-sm text-danger">{error}</p>}
      {choice !== saved && (
        <button type="button" onClick={save} disabled={busy} className="btn-primary mt-3">
          {busy && <Loader2 className="size-4 animate-spin" />} Save
        </button>
      )}
    </div>
  );
}
