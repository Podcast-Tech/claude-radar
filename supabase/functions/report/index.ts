// Claude Radar: receives status updates from Claude Code hooks.
// Auth: x-radar-token header (a per-PC token whose SHA-256 is stored in radar_tokens).
import { createClient } from "npm:@supabase/supabase-js@2.45.4";
import { sendPush } from "./webpush.ts";

const admin = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  { auth: { persistSession: false } },
);

const STATUSES = new Set(["working", "needs_you", "finished", "failed", "ended"]);

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-radar-token",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...CORS },
  });
}

const ALERTS: Record<string, string> = { needs_you: "Needs you", finished: "Finished", failed: "Failed" };
let vapidJwk: JsonWebKey | null = null;

// iPhone (home-screen web app) notifications via Web Push.
async function pushToPhones(owner: string, row: Record<string, unknown>) {
  const { data: subs } = await admin.from("radar_push").select("endpoint, p256dh, auth").eq("owner", owner).eq("muted", false);
  if (!subs?.length) return;
  if (!vapidJwk) {
    const { data } = await admin.from("radar_server_config").select("value").eq("key", "vapid_jwk").maybeSingle();
    if (!data) return;
    vapidJwk = JSON.parse(data.value);
  }
  const title = String(row.title ?? row.project ?? "Claude chat");
  const payload = {
    title: `${ALERTS[String(row.status)]} · ${title}`,
    body: String(row.message ?? (row.status === "needs_you" ? "Claude is waiting for you" : "Tap to see it")).slice(0, 180),
    session_id: row.session_id, status: row.status, url: row.url ?? null,
  };
  const vapid = { jwk: vapidJwk!, subject: "https://github.com/Podcast-Tech/claude-radar" };
  await Promise.all(subs.map(async (s) => {
    try {
      const code = await sendPush(s, payload, vapid);
      if (code === 404 || code === 410) await admin.from("radar_push").delete().eq("endpoint", s.endpoint);
      else if (code < 300) await admin.from("radar_push").update({ last_ok_at: new Date().toISOString() }).eq("endpoint", s.endpoint);
      else console.warn("push failed", code);
    } catch (e) { console.warn("push error", e); }
  }));
}

async function sha256(text: string) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(buf)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

const clip = (v: unknown, n: number) =>
  typeof v === "string" && v.length > 0 ? v.slice(0, n) : null;

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
  if (req.method !== "POST") return json({ error: "POST only" }, 405);
  const token = req.headers.get("x-radar-token") ?? "";

  // Test notification, requested by the signed-in iPhone app.
  if (!token && req.headers.get("authorization")?.startsWith("Bearer ")) {
    const jwt = req.headers.get("authorization")!.slice(7);
    const { data: u } = await admin.auth.getUser(jwt);
    if (!u?.user) return json({ error: "not signed in" }, 401);
    await pushToPhones(u.user.id, { session_id: "radar-test", status: "needs_you", title: "Test from GoGo", message: "If you see this, iPhone alerts work! 💙" });
    return json({ ok: true });
  }
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
    .select("status, updated_at, title, project, url, message")
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

  // Notify phones on a new alert (not on repeats of the same state).
  const isNew = !existing || existing.status !== status;
  if (ALERTS[status] && isNew) {
    const merged = { ...(existing ?? {}), ...row };
    const job = pushToPhones(tok.owner, merged);
    // deno-lint-ignore no-explicit-any
    const rt = (globalThis as any).EdgeRuntime;
    if (rt?.waitUntil) rt.waitUntil(job); else await job;
  }

  await admin.from("radar_tokens").update({ last_used_at: new Date().toISOString() }).eq("id", tok.id);
  if (Math.random() < 0.02) await admin.rpc("radar_cleanup");
  return json({ ok: true });
});
