// GoGo for iPhone: a home-screen web app. Same GoGo as the Android app (gogo/index.html, in a frame),
// with an iPhone bridge, Web Push alerts and the phone's own voice.
(() => {
  const VERSION = 'iPhone 1.0.1';
  const SUPABASE_URL = 'https://dkfiyqacjdsvvbrcsabw.supabase.co';
  const SUPABASE_KEY = 'sb_publishable_QYuYaLDjC6862ClbKyhO7w_gDTdHEct'; // publishable: data is protected by login + row-level security
  const VAPID_PUBLIC = 'BMlyNmeNHHIGRYfAEdqMePZaaCbOgfcqYfrFnI5v992MgGG__jNsEoklBHLiyjri40vJeFfFkxJ7dqMvAr01Tkg';
  const COLUMNS = 'session_id,source,machine,title,project,status,message,url,started_at,updated_at,seen_at';

  const $ = id => document.getElementById(id);
  const sb = supabase.createClient(SUPABASE_URL, SUPABASE_KEY, { auth: { persistSession: true, autoRefreshToken: true } });
  const standalone = navigator.standalone === true || matchMedia('(display-mode: standalone)').matches;
  const pushCapable = 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;

  const store = {
    get(k, d) { try { const v = localStorage.getItem('gogo.' + k); return v == null ? d : JSON.parse(v); } catch (_) { return d; } },
    set(k, v) { try { localStorage.setItem('gogo.' + k, JSON.stringify(v)); } catch (_) {} },
  };
  const prefs = {
    get recording() { return store.get('recording', false); }, set recording(v) { store.set('recording', v); },
    get talk() { return store.get('talk', true); }, set talk(v) { store.set('talk', v); },
    get rest() { return store.get('rest', 10); }, set rest(v) { store.set('rest', v); },
    get awake() { return store.get('awake', false); }, set awake(v) { store.set('awake', v); },
  };

  let sessions = [], rtUp = false, lastOk = 0, loadedOnce = false, user = null, channel = null, reg = null;
  const frame = $('frame');
  // "Live" = data is arriving: either realtime is up, or the last check worked recently.
  const isLive = () => rtUp || Date.now() - lastOk < 45000;
  const G = () => { const g = frame.contentWindow && frame.contentWindow.gogo; return g && typeof g.update === 'function' ? g : null; }; // while the page loads, window.gogo is still the robot's SVG element

  function toast(html, ms = 3500) {
    const t = $('toast'); t.innerHTML = html; t.hidden = false;
    clearTimeout(toast.timer); toast.timer = setTimeout(() => { t.hidden = true; }, ms);
  }
  const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  // ---------- data ----------
  function push() {
    const g = G(); if (g) g.update({ sessions, connected: isLive(), recording: prefs.recording });
    const n = sessions.filter(s => !s.seen_at && ['needs_you', 'finished', 'failed'].includes(s.status)).length;
    if (navigator.setAppBadge) (n ? navigator.setAppBadge(n) : navigator.clearAppBadge()).catch(() => {});
  }

  async function refresh() {
    const { data, error } = await sb.from('radar_sessions').select(COLUMNS).order('updated_at', { ascending: false });
    if (error) { console.warn(error); return; }
    const fresh = data.filter(s => s.status !== 'ended');
    if (loadedOnce) {
      const before = Object.fromEntries(sessions.map(s => [s.session_id, s]));
      for (const s of fresh) {
        const old = before[s.session_id];
        const alerting = !s.seen_at && ['needs_you', 'finished', 'failed'].includes(s.status);
        const changed = !old || old.status !== s.status || (old.seen_at && !s.seen_at);
        if (alerting && changed && !prefs.recording) { Chirps.play(s.status); if (s.status === 'finished') setTimeout(() => G() && G().celebrate(), 50); }
      }
    }
    loadedOnce = true; lastOk = Date.now(); sessions = fresh; push();
  }

  async function markSeen(id) {
    const now = new Date().toISOString();
    sessions = sessions.map(s => s.session_id === id ? { ...s, seen_at: now } : s); push();
    await sb.from('radar_sessions').update({ seen_at: now }).eq('session_id', id);
  }
  async function markAllSeen() {
    const ids = sessions.filter(s => !s.seen_at && s.status !== 'working').map(s => s.session_id);
    if (!ids.length) return;
    const now = new Date().toISOString();
    sessions = sessions.map(s => ids.includes(s.session_id) ? { ...s, seen_at: now } : s); push();
    await sb.from('radar_sessions').update({ seen_at: now }).in('session_id', ids);
  }
  async function remove(id) {
    sessions = sessions.filter(s => s.session_id !== id); push();
    await sb.from('radar_sessions').delete().eq('session_id', id);
  }
  async function clearDone() {
    const ids = sessions.filter(s => s.seen_at && ['finished', 'failed'].includes(s.status)).map(s => s.session_id);
    if (!ids.length) { toast('Nothing to clear'); return; }
    sessions = sessions.filter(s => !ids.includes(s.session_id)); push();
    await sb.from('radar_sessions').delete().in('session_id', ids);
  }

  function connect() {
    if (channel) sb.removeChannel(channel);
    channel = sb.channel('radar-sessions')
      .on('postgres_changes', { event: '*', schema: 'public', table: 'radar_sessions' }, () => refresh())
      .subscribe(st => { const up = st === 'SUBSCRIBED'; if (up && !rtUp) refresh(); rtUp = up; push(); });
  }
  // Safety net: every minute normally, every 15 s whenever realtime is down.
  setInterval(() => {
    if (user && document.visibilityState === 'visible' && !(rtUp && Date.now() - lastOk < 55000)) refresh();
    push();
  }, 15000);
  // iPhone pauses web apps in the background: catch up and reconnect when GoGo comes back.
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState !== 'visible' || !user) return;
    refresh(); if (!rtUp) connect(); if (prefs.awake) keepAwake(true);
  });
  // GoGo rests: unseen "finished" results clear themselves after a while.
  setInterval(() => {
    if (!user || !prefs.rest) return;
    const cutoff = Date.now() - prefs.rest * 60000;
    sessions.filter(s => !s.seen_at && s.status === 'finished' && Date.parse(s.updated_at) < cutoff).forEach(s => markSeen(s.session_id));
  }, 30000);

  // ---------- sounds (same chirps as Android) ----------
  const Chirps = (() => {
    let ctx = null;
    const N = (f, d, slide = 1.25, wave = 'sine', vol = .5) => ({ f, d, slide, wave, vol });
    const S = {
      boop: [0, [N(620, .10, 1.6)]],
      needs_you: [.05, [N(880, .08, 1.15, 'triangle'), N(1320, .08, 1.15, 'triangle'), N(880, .08, 1.15, 'triangle'), N(1320, .08, 1.15, 'triangle')]],
      finished: [.015, [N(784, .09, 1.02, 'triangle'), N(988, .09, 1.02, 'triangle'), N(1175, .09, 1.02, 'triangle'), N(1568, .12, 1.02, 'triangle')]],
      failed: [.02, [N(520, .14, .8, 'square', .22), N(390, .14, .8, 'square', .22), N(260, .18, .8, 'square', .22)]],
    };
    function unlock() {
      try { ctx = ctx || new (window.AudioContext || window.webkitAudioContext)(); if (ctx.state !== 'running') ctx.resume(); } catch (_) {}
    }
    function play(name) {
      if (!ctx) return; unlock();
      const [gap, notes] = S[name] || S.boop; let t = ctx.currentTime + .02;
      for (const n of notes) {
        const o = ctx.createOscillator(), g = ctx.createGain();
        o.type = n.wave; o.frequency.setValueAtTime(n.f, t); o.frequency.exponentialRampToValueAtTime(n.f * n.slide, t + n.d);
        g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(n.vol * .6, t + .012); g.gain.exponentialRampToValueAtTime(0.0001, t + n.d);
        o.connect(g).connect(ctx.destination); o.start(t); o.stop(t + n.d + .02); t += n.d + gap;
      }
    }
    return { unlock, play };
  })();
  document.addEventListener('touchend', Chirps.unlock, { passive: true });
  document.addEventListener('click', Chirps.unlock);

  // ---------- voice ----------
  let voice = null;
  function pickVoice() {
    const vs = speechSynthesis.getVoices().filter(v => /^en/i.test(v.lang));
    voice = vs.find(v => /samantha|karen|moira|tessa|female/i.test(v.name)) || vs.find(v => /en-US/i.test(v.lang)) || vs[0] || null;
  }
  if ('speechSynthesis' in window) { pickVoice(); speechSynthesis.onvoiceschanged = pickVoice; }
  function speak(text) {
    if (!prefs.talk || prefs.recording || !('speechSynthesis' in window)) return;
    const clean = String(text).replace(/[^\p{L}\p{N}\p{P}\s]/gu, '').trim(); if (!clean) return;
    speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(clean); u.pitch = 1.6; u.rate = 1.08; if (voice) { u.voice = voice; u.lang = voice.lang; }
    speechSynthesis.speak(u);
  }
  const Rec = window.SpeechRecognition || window.webkitSpeechRecognition;
  function listen() {
    const g = G(); if (!g) return;
    if (prefs.recording) { g.say("Shh, we're recording! 🤫"); return; }
    if (!Rec) { g.say("My ears don't work on iPhone yet 🙉 Tap ⌨ and type to me!"); return; }
    try {
      const r = new Rec(); r.lang = 'en-US'; r.interimResults = false; r.maxAlternatives = 1;
      let got = false;
      r.onstart = () => g.voice('listening', 0);
      r.onresult = e => { got = true; g.voice('off', 0); g.heard(e.results[0][0].transcript); };
      r.onerror = e => { g.voice('off', 0); if (e.error === 'not-allowed') g.say("I need the microphone 🎤 Allow it in Settings, or tap ⌨ to type."); };
      r.onend = () => { g.voice('off', 0); if (!got) g.heard(''); };
      r.start();
    } catch (_) { g.voice('off', 0); g.say("My ears are sleepy 🙉 Tap ⌨ and type to me!"); }
  }

  // ---------- bridge used by GoGo ----------
  window.RadarBridge = {
    seen: id => markSeen(id),
    open: id => {
      const s = sessions.find(x => x.session_id === id); markSeen(id);
      if (s && s.url) window.open(s.url, '_blank');
      else toast('This chat runs on your computer, so there is no link to open.');
    },
    seenAll: () => markAllSeen(),
    remove: id => { if (navigator.vibrate) navigator.vibrate(20); remove(id); toast('Removed'); },
    speak: t => speak(t),
    listen: () => listen(),
    setRecording: v => setRecording(!!v),
    boop: () => Chirps.play('boop'),
    ready: () => { push(); applyFrameTweaks(); },
  };
  function applyFrameTweaks() {
    // Make room for the iPhone REC + menu buttons, same as on Android.
    try {
      const d = frame.contentDocument, st = d.createElement('style');
      st.textContent = '.topbar{padding-right:120px!important}';
      d.head.appendChild(st);
    } catch (_) {}
  }

  // ---------- recording mode ----------
  async function setRecording(v) {
    prefs.recording = v; $('recBtn').classList.toggle('on', v); $('recBtn').textContent = v ? '● REC' : 'REC';
    if (v && 'speechSynthesis' in window) speechSynthesis.cancel();
    push();
    const sub = reg && await reg.pushManager.getSubscription();
    if (sub) await sb.from('radar_push').update({ muted: v }).eq('endpoint', sub.endpoint);
  }
  $('recBtn').addEventListener('click', () => {
    setRecording(!prefs.recording);
    toast(prefs.recording ? 'Recording mode: GoGo stays silent and iPhone alerts pause' : 'Recording mode off');
  });

  // ---------- iPhone alerts (Web Push) ----------
  const b64 = s => { const t = s.replace(/-/g, '+').replace(/_/g, '/'); const bin = atob(t + '==='.slice((t.length + 3) % 4)); return Uint8Array.from(bin, c => c.charCodeAt(0)); };
  async function notifState() {
    if (!pushCapable) return 'unsupported';
    if (Notification.permission === 'denied') return 'blocked';
    const sub = reg && await reg.pushManager.getSubscription();
    return sub && Notification.permission === 'granted' ? 'on' : 'off';
  }
  async function saveSub(sub) {
    const j = sub.toJSON();
    const { error } = await sb.from('radar_push').upsert({ endpoint: j.endpoint, p256dh: j.keys.p256dh, auth: j.keys.auth, label: 'iPhone', muted: prefs.recording }, { onConflict: 'endpoint' });
    if (error) throw error;
  }
  async function turnOnAlerts() {
    try {
      if (!pushCapable) { toast(standalone ? 'This iPhone needs iOS 16.4 or newer for alerts.' : 'Add GoGo to your Home Screen first, then open it from there.', 5000); return; }
      const perm = await Notification.requestPermission();
      if (perm !== 'granted') { toast('Alerts are off. Turn them on in Settings › Notifications › GoGo.', 6000); return; }
      reg = reg || await navigator.serviceWorker.ready;
      let sub = await reg.pushManager.getSubscription();
      if (!sub) sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: b64(VAPID_PUBLIC) });
      await saveSub(sub);
      toast('iPhone alerts are on! 🎉 Sending a test…');
      await sendTest();
    } catch (e) { console.warn(e); toast('Could not turn on alerts: ' + esc(e.message || e), 6000); }
    updateNotifUi();
  }
  async function sendTest() {
    const { data: { session } } = await sb.auth.getSession();
    const r = await fetch(SUPABASE_URL + '/functions/v1/report', { method: 'POST', headers: { Authorization: 'Bearer ' + session.access_token, apikey: SUPABASE_KEY, 'Content-Type': 'application/json' }, body: '{"test_push":true}' });
    if (!r.ok) toast('Test failed (' + r.status + ')');
  }
  async function updateNotifUi() {
    const st = await notifState();
    $('mNotifState').textContent = { on: prefs.recording ? 'Paused (REC)' : 'On', off: 'Off', blocked: 'Blocked', unsupported: 'Not available' }[st];
    $('notifBanner').hidden = !(user && standalone && (st === 'off'));
    // Keep this phone's subscription saved (it can change after iOS updates).
    if (st === 'on') { const sub = await reg.pushManager.getSubscription(); saveSub(sub).catch(() => {}); }
  }
  $('notifOn').addEventListener('click', turnOnAlerts);

  // A tapped notification marks that chat as read (and offers the link).
  function handleOpened(id, url) {
    if (!id || id === 'radar-test') return;
    markSeen(id);
    if (url && /^https:\/\//.test(url)) toast(`Marked as read · <a href="${esc(url)}" target="_blank" rel="noopener">Open chat ↗</a>`, 8000);
  }
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.addEventListener('message', e => {
      const m = e.data || {};
      if (m.type === 'opened') handleOpened(m.session_id, m.url);
      if (m.type === 'push') refresh();
    });
  }

  // ---------- keep screen on ----------
  let lock = null;
  async function keepAwake(on) {
    try {
      if (on && 'wakeLock' in navigator) { lock = await navigator.wakeLock.request('screen'); return true; }
      if (!on && lock) { await lock.release(); lock = null; }
    } catch (_) {}
    return !on;
  }

  // ---------- menu ----------
  const openMenu = o => { $('menu').hidden = !o; $('veil').hidden = !o; if (o) updateMenu(); };
  function updateMenu() {
    $('mTalkState').textContent = prefs.talk ? 'On' : 'Off';
    $('mRestState').textContent = prefs.rest ? prefs.rest + ' min' : 'Never';
    $('mAwakeState').textContent = prefs.awake ? 'On' : 'Off';
    $('ver').textContent = VERSION + (user ? ' · ' + (user.email || '').replace(/(.{2}).*(@.*)/, '$1…$2') : '');
    updateNotifUi();
  }
  $('menuBtn').addEventListener('click', () => openMenu($('menu').hidden));
  $('veil').addEventListener('click', () => openMenu(false));
  $('mNotif').addEventListener('click', async () => {
    const st = await notifState();
    if (st === 'on') toast('Alerts are on. To turn them off: Settings › Notifications › GoGo.', 5000);
    else if (st === 'blocked') toast('Alerts are blocked. Turn them on in Settings › Notifications › GoGo.', 6000);
    else turnOnAlerts();
    openMenu(false);
  });
  $('mTest').addEventListener('click', async () => {
    openMenu(false);
    if (await notifState() !== 'on') { toast('Turn on iPhone alerts first.'); return; }
    if (prefs.recording) { toast('Alerts are paused while recording.'); return; }
    await sendTest(); toast('Test sent. Lock your phone to see it 📲');
  });
  $('mTalk').addEventListener('click', () => { prefs.talk = !prefs.talk; updateMenu(); });
  $('mRest').addEventListener('click', () => { const o = [5, 10, 30, 0]; prefs.rest = o[(o.indexOf(prefs.rest) + 1) % o.length]; updateMenu(); });
  $('mAwake').addEventListener('click', async () => {
    prefs.awake = !prefs.awake; updateMenu();
    if (prefs.awake) { const ok = await keepAwake(true); if (!ok || !lock) toast('iPhone may still dim. For a desk display: Settings › Display & Brightness › Auto-Lock › Never.', 7000); }
    else keepAwake(false);
  });
  $('mAll').addEventListener('click', () => { markAllSeen(); openMenu(false); });
  $('mClear').addEventListener('click', () => { clearDone(); openMenu(false); });
  $('mOut').addEventListener('click', async () => {
    openMenu(false);
    try { const sub = reg && await reg.pushManager.getSubscription(); if (sub) { await sb.from('radar_push').delete().eq('endpoint', sub.endpoint); await sub.unsubscribe(); } } catch (_) {}
    await sb.auth.signOut(); location.reload();
  });

  // ---------- sign in ----------
  let creating = false;
  $('signupBtn').addEventListener('click', () => {
    creating = !creating;
    $('loginBtn').textContent = creating ? 'Create account' : 'Sign in';
    $('signupBtn').textContent = creating ? 'I already have an account' : 'New here? Create an account';
  });
  $('loginForm').addEventListener('submit', async e => {
    e.preventDefault(); $('loginErr').textContent = ''; $('loginBtn').disabled = true;
    const email = $('email').value.trim(), password = $('password').value;
    const { data, error } = creating ? await sb.auth.signUp({ email, password }) : await sb.auth.signInWithPassword({ email, password });
    $('loginBtn').disabled = false;
    if (error) { $('loginErr').textContent = error.message; return; }
    if (!data.session) { $('loginErr').textContent = 'Check your email to confirm, then sign in.'; return; }
    start(data.session.user);
  });

  // ---------- start ----------
  function start(u) {
    user = u; $('login').hidden = true; $('install').hidden = true;
    $('controls').hidden = false; frame.hidden = false;
    if (!frame.src) frame.src = 'gogo/index.html';
    setRecording(prefs.recording);
    connect(); refresh(); updateNotifUi();
    if (prefs.awake) keepAwake(true);
    const q = new URLSearchParams(location.search);
    if (q.get('seen')) { setTimeout(() => handleOpened(q.get('seen'), q.get('url')), 800); history.replaceState(null, '', location.pathname); }
  }

  async function boot() {
    if ('serviceWorker' in navigator) {
      try { reg = await navigator.serviceWorker.register('sw.js'); } catch (e) { console.warn('sw', e); }
    }
    const { data: { session } } = await sb.auth.getSession();
    if (!standalone && !store.get('skipInstall', false) && /iPhone|iPad|iPod/.test(navigator.userAgent)) {
      $('install').hidden = false;
      $('notSafari').hidden = !/CriOS|FxiOS|EdgiOS/.test(navigator.userAgent);
      $('skipInstall').onclick = () => { store.set('skipInstall', true); $('install').hidden = true; session ? start(session.user) : ($('login').hidden = false); };
      return;
    }
    if (session) start(session.user); else $('login').hidden = false;
  }
  sb.auth.onAuthStateChange((ev, session) => { if (ev === 'SIGNED_OUT') user = null; else if (session) user = session.user; });
  boot();
})();
