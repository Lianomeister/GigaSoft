-- Clockwork Marketplace Admin / Submission schema
-- Run in Supabase SQL editor.

create extension if not exists pgcrypto;

create table if not exists public.marketplace_admins (
  user_id uuid primary key references auth.users (id) on delete cascade,
  created_at timestamptz not null default now()
);

create table if not exists public.plugin_submissions (
  id uuid primary key default gen_random_uuid(),
  created_at timestamptz not null default now(),
  created_by uuid not null references auth.users (id) on delete cascade default auth.uid(),
  plugin_id text not null,
  name text not null,
  version text not null,
  minecraft_versions text[] not null default '{}',
  categories text[] not null default '{}',
  summary text not null,
  logs text not null,
  source_url text,
  download_url text not null,
  image_urls text[] not null default '{}',
  artifact_urls text[] not null default '{}',
  status text not null default 'review'
);

alter table public.plugin_submissions
  add column if not exists rejection_reason text;

alter table public.marketplace_admins enable row level security;
alter table public.plugin_submissions enable row level security;

create or replace function public.is_marketplace_admin()
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (
    select 1
    from public.marketplace_admins
    where user_id = auth.uid()
  );
$$;

grant execute on function public.is_marketplace_admin() to anon, authenticated;

create or replace function public.delete_my_account()
returns boolean
language plpgsql
security definer
set search_path = public, auth
as $$
declare
  _uid uuid := auth.uid();
begin
  if _uid is null then
    raise exception 'Not authenticated';
  end if;

  delete from auth.users where id = _uid;
  return true;
end;
$$;

grant execute on function public.delete_my_account() to authenticated;
revoke execute on function public.delete_my_account() from anon;

drop policy if exists "marketplace_admins_select_self" on public.marketplace_admins;
create policy "marketplace_admins_select_self"
on public.marketplace_admins
for select
to authenticated
using (user_id = auth.uid());

drop policy if exists "plugin_submissions_admin_read" on public.plugin_submissions;
create policy "plugin_submissions_admin_read"
on public.plugin_submissions
for select
to authenticated
using (public.is_marketplace_admin());

drop policy if exists "plugin_submissions_admin_insert" on public.plugin_submissions;
create policy "plugin_submissions_admin_insert"
on public.plugin_submissions
for insert
to authenticated
with check (public.is_marketplace_admin());

drop policy if exists "plugin_submissions_admin_update" on public.plugin_submissions;
create policy "plugin_submissions_admin_update"
on public.plugin_submissions
for update
to authenticated
using (public.is_marketplace_admin())
with check (public.is_marketplace_admin());

create index if not exists idx_plugin_submissions_created_at
  on public.plugin_submissions (created_at desc);

create index if not exists idx_plugin_submissions_plugin_id
  on public.plugin_submissions (plugin_id);

-- Storage buckets (public read for previews/download links).
insert into storage.buckets (id, name, public)
values ('marketplace-images', 'marketplace-images', true)
on conflict (id) do nothing;

insert into storage.buckets (id, name, public)
values ('marketplace-files', 'marketplace-files', true)
on conflict (id) do nothing;

drop policy if exists "marketplace_images_public_read" on storage.objects;
create policy "marketplace_images_public_read"
on storage.objects
for select
to public
using (bucket_id = 'marketplace-images');

drop policy if exists "marketplace_images_admin_insert" on storage.objects;
create policy "marketplace_images_admin_insert"
on storage.objects
for insert
to authenticated
with check (bucket_id = 'marketplace-images' and public.is_marketplace_admin());

drop policy if exists "marketplace_files_public_read" on storage.objects;
create policy "marketplace_files_public_read"
on storage.objects
for select
to public
using (bucket_id = 'marketplace-files');

drop policy if exists "marketplace_files_admin_insert" on storage.objects;
create policy "marketplace_files_admin_insert"
on storage.objects
for insert
to authenticated
with check (bucket_id = 'marketplace-files' and public.is_marketplace_admin());

-- After creating your first auth user, promote to admin:
-- insert into public.marketplace_admins (user_id) values ('<auth-user-uuid>');
