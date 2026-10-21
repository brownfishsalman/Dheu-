"use server";

import { redirect } from "next/navigation";
import { z } from "zod";
import { createClient } from "@/lib/supabase/server";
import { createAccount, signupSchema } from "@/lib/auth/create-account";

export type AuthState = {
  error?: string;
  fields?: Record<string, string>;
};

const loginSchema = z.object({
  email: z.email("Enter a valid email address"),
  password: z.string().min(1, "Enter your password"),
  next: z.string().optional(),
});

function fieldsOf(formData: FormData): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [k, v] of formData.entries()) {
    if (typeof v === "string" && k !== "password") out[k] = v;
  }
  return out;
}

function safeNext(next: string | undefined): string {
  // Only allow same-site relative paths to prevent open redirects.
  if (!next || !next.startsWith("/") || next.startsWith("//")) return "/";
  return next;
}

export async function login(_prev: AuthState, formData: FormData): Promise<AuthState> {
  const parsed = loginSchema.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { error: parsed.error.issues[0].message, fields: fieldsOf(formData) };
  }

  const supabase = await createClient();
  const { error } = await supabase.auth.signInWithPassword({
    email: parsed.data.email,
    password: parsed.data.password,
  });

  if (error) {
    const msg = /banned/i.test(error.message)
      ? "This account has been suspended."
      : "Wrong email or password.";
    return { error: msg, fields: fieldsOf(formData) };
  }

  redirect(safeNext(parsed.data.next));
}

export async function signup(_prev: AuthState, formData: FormData): Promise<AuthState> {
  const parsed = signupSchema.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { error: parsed.error.issues[0].message, fields: fieldsOf(formData) };
  }

  const created = await createAccount(parsed.data);
  if (!created.ok) return { error: created.error, fields: fieldsOf(formData) };

  const supabase = await createClient();
  const { error: signInError } = await supabase.auth.signInWithPassword({
    email: parsed.data.email,
    password: parsed.data.password,
  });
  if (signInError) {
    console.error("[signup] sign-in after create failed:", signInError);
    // Account exists now; send them to log in manually.
    redirect("/login?created=1");
  }

  redirect("/");
}
