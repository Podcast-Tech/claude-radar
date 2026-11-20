-- Applied to the claude-radar Supabase project (dkfiyqacjdsvvbrcsabw).
create table public.radar_tokens (
  id uuid primary key default gen_random_uuid(),
  owner uuid not null references auth.users(id) on delete cascade default auth.uid(),
  token_hash text not null unique,
  label text not null default 'PC',
  created_at timestamptz not null default now(),
  last_used_at timestamptz
);

create table public.radar_sessions (
  owner uuid not null references auth.users(id) on delete cascade,
  session_id text not null,
  source text not null default 'code',
  machine text,
  title text,
  project text,
  status text not null check (status in ('working','needs_you','finished','failed','ended')),
  message text,
  url text,
  started_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  seen_at timestamptz,
  primary key (owner, session_id)
);
create index radar_sessions_owner_updated on public.radar_sessions(owner, updated_at desc);

alter table public.radar_tokens enable row level security;
alter table public.radar_sessions enable row level security;

create policy "own tokens select" on public.radar_tokens for select to authenticated using (owner = (select auth.uid()));
create policy "own tokens insert" on public.radar_tokens for insert to authenticated with check (owner = (select auth.uid()));
create policy "own tokens delete" on public.radar_tokens for delete to authenticated using (owner = (select auth.uid()));

create policy "own sessions select" on public.radar_sessions for select to authenticated using (owner = (select auth.uid()));
create policy "own sessions update" on public.radar_sessions for update to authenticated using (owner = (select auth.uid())) with check (owner = (select auth.uid()));
create policy "own sessions delete" on public.radar_sessions for delete to authenticated using (owner = (select auth.uid()));

grant select, insert, delete on public.radar_tokens to authenticated;
grant select, update, delete on public.radar_sessions to authenticated;
revoke all on public.radar_tokens from anon;
revoke all on public.radar_sessions from anon;

alter table public.radar_sessions replica identity full;
alter publication supabase_realtime add table public.radar_sessions;

create or replace function public.radar_cleanup() returns void language sql security definer set search_path = '' as $$
  delete from public.radar_sessions where updated_at < now() - interval '3 days';
$$;
revoke execute on function public.radar_cleanup() from public, anon, authenticated;
