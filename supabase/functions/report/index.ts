// Claude Radar: receives status updates from Claude Code hooks.
// Auth: x-radar-token header (a per-PC token whose SHA-256 is stored in radar_tokens).
import { createClient } from "npm:@supabase/supabase-js@2.45.4";

const admin = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  { auth: { persistSession: false } },
);

const STATUSES = new Set(["working", "needs_you", "finished", "failed", "ended"]);

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

async function sha256(text: string) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(buf)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

const clip = (v: unknown, n: number) =>
  typeof v === "string" && v.length > 0 ? v.slice(0, n) : null;

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "POST only" }, 405);
  const token = req.headers.get("x-radar-token") ?? "";
  if (token.length < 20) return json({ error: "missing token" }, 401);

  const { data: tok } = await admin
    .from("radar_tokens")
    .select("id, owner")
    .eq("token_hash", await sha256(token))
    .maybeSingle();
  if (!tok) return json({ error: "invalid token" }, 401);

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return json({ error: "bad json" }, 400);
  }

  // Uninstall: the PC revokes its own token.
  if (body.revoke === true) {
    await admin.from("radar_tokens").delete().eq("id", tok.id);
    return json({ ok: true, revoked: true });
  }

  const sessionId = clip(body.session_id, 200);
  const status = String(body.status ?? "");
  if (!sessionId || !STATUSES.has(status)) return json({ error: "session_id and valid status required" }, 400);

  // Hooks run in the background and can arrive out of order: use the sender's clock to order them.
  const serverNow = Date.now();
  const sentAt = typeof body.at === "number" && Math.abs(body.at - serverNow) < 86_400_000 ? body.at : serverNow;
  const at = new Date(sentAt).toISOString();

  const { data: existing } = await admin
    .from("radar_sessions")
    .select("status, updated_at")
    .eq("owner", tok.owner)
    .eq("session_id", sessionId)
    .maybeSingle();

  if (existing && new Date(existing.updated_at).getTime() > sentAt) {
    return json({ ok: true, ignored: "older than current status" });
  }
  // A session closing should not hide a result you haven't looked at yet...
  if (status === "ended" && existing && existing.status !== "working") {
    // ...unless you closed it yourself (/exit, /clear, logout): then you've seen it.
    const reason = String(body.reason ?? "");
    if (["prompt_input_exit", "clear", "logout"].includes(reason) && existing.status === "finished") {
      await admin.from("radar_sessions").update({ seen_at: new Date().toISOString() })
        .eq("owner", tok.owner).eq("session_id", sessionId);
      return json({ ok: true, seen: true });
    }
    return json({ ok: true, kept: existing.status });
  }

  const row: Record<string, unknown> = {
    owner: tok.owner,
    session_id: sessionId,
    status,
    updated_at: at,
    source: clip(body.source, 20) ?? "code",
  };
  for (const [k, n] of [["title", 120], ["project", 120], ["message", 300], ["machine", 60], ["url", 500]] as const) {
    const v = clip(body[k], n);
    if (v) row[k] = v;
  }
  if (status === "needs_you" || status === "finished" || status === "failed") row.seen_at = null;
  if (status === "working") row.message = clip(body.message, 300); // clear stale messages
  if (!existing) row.started_at = at;

  const { error } = await admin.from("radar_sessions").upsert(row, { onConflict: "owner,session_id" });
  if (error) return json({ error: error.message }, 500);

  await admin.from("radar_tokens").update({ last_used_at: new Date().toISOString() }).eq("id", tok.id);
  if (Math.random() < 0.02) await admin.rpc("radar_cleanup");
  return json({ ok: true });
});
