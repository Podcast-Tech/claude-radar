-- iPhone (home-screen web app) push subscriptions + server-only config. Applied to dkfiyqacjdsvvbrcsabw.
create table public.radar_push (
  endpoint text primary key,
  owner uuid not null references auth.users(id) on delete cascade default auth.uid(),
  p256dh text not null,
  auth text not null,
  label text not null default 'iPhone',
  muted boolean not null default false,
  created_at timestamptz not null default now(),
  last_ok_at timestamptz
);
create index radar_push_owner on public.radar_push(owner);
alter table public.radar_push enable row level security;
create policy "own push select" on public.radar_push for select to authenticated using (owner = (select auth.uid()));
create policy "own push insert" on public.radar_push for insert to authenticated with check (owner = (select auth.uid()));
create policy "own push update" on public.radar_push for update to authenticated using (owner = (select auth.uid())) with check (owner = (select auth.uid()));
create policy "own push delete" on public.radar_push for delete to authenticated using (owner = (select auth.uid()));
grant select, insert, update, delete on public.radar_push to authenticated;
revoke all on public.radar_push from anon;

-- Server-only settings (the VAPID signing key, inserted separately, never committed).
create table public.radar_server_config (key text primary key, value text not null);
alter table public.radar_server_config enable row level security;
revoke all on public.radar_server_config from anon, authenticated;
