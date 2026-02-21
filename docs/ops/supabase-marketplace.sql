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
  updated_at timestamptz not null default now(),
  created_by uuid not null references auth.users (id) on delete cascade default auth.uid(),
  created_by_email text,
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
  status text not null default 'draft',
  validation_state text not null default 'pending',
  validation_errors jsonb not null default '[]'::jsonb,
  rejection_reason text,
  reviewed_at timestamptz,
  approved_at timestamptz,
  published_at timestamptz,
  published_by uuid references auth.users (id)
);

alter table public.plugin_submissions
  add column if not exists updated_at timestamptz not null default now(),
  add column if not exists created_by_email text,
  add column if not exists validation_state text not null default 'pending',
  add column if not exists validation_errors jsonb not null default '[]'::jsonb,
  add column if not exists reviewed_at timestamptz,
  add column if not exists approved_at timestamptz,
  add column if not exists published_at timestamptz,
  add column if not exists published_by uuid references auth.users (id),
  add column if not exists rejection_reason text;

alter table public.plugin_submissions
  alter column status set default 'draft';

create table if not exists public.plugin_submission_status_history (
  id bigint generated always as identity primary key,
  submission_id uuid not null references public.plugin_submissions (id) on delete cascade,
  changed_at timestamptz not null default now(),
  changed_by uuid references auth.users (id),
  from_status text,
  to_status text not null,
  reason text,
  validation_state text,
  validation_errors jsonb not null default '[]'::jsonb
);

create or replace function public.marketplace_validate_submission(row_data public.plugin_submissions)
returns jsonb
language plpgsql
stable
set search_path = public
as $$
declare
  issues text[] := '{}';
  cat text;
  mc text;
begin
  if coalesce(trim(row_data.plugin_id), '') = '' then
    issues := array_append(issues, 'plugin_id_missing');
  end if;
  if coalesce(trim(row_data.name), '') = '' then
    issues := array_append(issues, 'name_missing');
  end if;
  if coalesce(trim(row_data.version), '') !~ '^[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.-]+)?$' then
    issues := array_append(issues, 'version_invalid_semver');
  end if;
  if coalesce(trim(row_data.summary), '') = '' then
    issues := array_append(issues, 'summary_missing');
  elsif length(trim(row_data.summary)) < 20 then
    issues := array_append(issues, 'summary_too_short');
  end if;
  if coalesce(trim(row_data.logs), '') = '' then
    issues := array_append(issues, 'logs_missing');
  elsif length(trim(row_data.logs)) < 12 then
    issues := array_append(issues, 'logs_too_short');
  end if;

  if coalesce(trim(row_data.download_url), '') !~* '^https?://.+' then
    issues := array_append(issues, 'download_url_invalid');
  end if;
  if row_data.source_url is not null and trim(row_data.source_url) <> '' and trim(row_data.source_url) !~* '^https?://.+' then
    issues := array_append(issues, 'source_url_invalid');
  end if;

  if coalesce(array_length(row_data.minecraft_versions, 1), 0) = 0 then
    issues := array_append(issues, 'minecraft_versions_missing');
  else
    foreach mc in array row_data.minecraft_versions loop
      if coalesce(trim(mc), '') = '' then
        issues := array_append(issues, 'minecraft_versions_contains_blank');
        exit;
      end if;
    end loop;
  end if;

  if coalesce(array_length(row_data.categories, 1), 0) = 0 then
    issues := array_append(issues, 'categories_missing');
  else
    foreach cat in array row_data.categories loop
      if coalesce(trim(cat), '') = '' then
        issues := array_append(issues, 'categories_contains_blank');
        exit;
      end if;
    end loop;
  end if;

  if row_data.status = 'published' and coalesce(array_length(row_data.artifact_urls, 1), 0) = 0 then
    issues := array_append(issues, 'artifacts_missing_for_publish');
  end if;

  return to_jsonb(issues);
end;
$$;

grant execute on function public.marketplace_validate_submission(public.plugin_submissions) to authenticated;

create or replace function public.marketplace_validate_submission_record(p_id uuid)
returns table (
  submission_id uuid,
  validation_state text,
  validation_errors jsonb
)
language plpgsql
security definer
set search_path = public
as $$
declare
  row_data public.plugin_submissions;
  issues jsonb;
  state text;
begin
  select * into row_data from public.plugin_submissions where id = p_id;
  if not found then
    raise exception '[SUB_NOT_FOUND] Submission % not found', p_id;
  end if;

  issues := public.marketplace_validate_submission(row_data);
  state := case when jsonb_array_length(issues) = 0 then 'valid' else 'invalid' end;

  update public.plugin_submissions
  set validation_errors = issues,
      validation_state = state,
      updated_at = now()
  where id = p_id;

  return query
    select p_id, state, issues;
end;
$$;

grant execute on function public.marketplace_validate_submission_record(uuid) to authenticated;

create or replace function public.transition_plugin_submission_status(
  p_submission_id uuid,
  p_next_status text,
  p_reason text default null
)
returns public.plugin_submissions
language plpgsql
security definer
set search_path = public
as $$
declare
  row_data public.plugin_submissions;
  current_status text;
  next_status text := lower(trim(coalesce(p_next_status, '')));
  issues jsonb;
  state text;
begin
  if not public.is_marketplace_admin() then
    raise exception '[SUB_FORBIDDEN] Admin role required';
  end if;

  select * into row_data
  from public.plugin_submissions
  where id = p_submission_id
  for update;

  if not found then
    raise exception '[SUB_NOT_FOUND] Submission % not found', p_submission_id;
  end if;

  if next_status not in ('draft', 'review', 'approved', 'published', 'rejected') then
    raise exception '[SUB_STATUS_INVALID] Invalid status: %', next_status;
  end if;

  -- keep validation in sync before transition checks
  current_status := row_data.status;
  row_data.status := next_status;
  issues := public.marketplace_validate_submission(row_data);
  state := case when jsonb_array_length(issues) = 0 then 'valid' else 'invalid' end;

  update public.plugin_submissions
  set validation_errors = issues,
      validation_state = state,
      updated_at = now()
  where id = p_submission_id;

  if current_status = next_status then
    return (
      select s from public.plugin_submissions s where s.id = p_submission_id
    );
  end if;

  if current_status = 'draft' and next_status not in ('review', 'rejected') then
    raise exception '[SUB_STATUS_TRANSITION_DENIED] draft -> % not allowed', next_status;
  end if;
  if current_status = 'review' and next_status not in ('approved', 'rejected', 'draft') then
    raise exception '[SUB_STATUS_TRANSITION_DENIED] review -> % not allowed', next_status;
  end if;
  if current_status = 'approved' and next_status not in ('published', 'rejected', 'review') then
    raise exception '[SUB_STATUS_TRANSITION_DENIED] approved -> % not allowed', next_status;
  end if;
  if current_status = 'published' and next_status not in ('published') then
    raise exception '[SUB_STATUS_TRANSITION_DENIED] published -> % not allowed', next_status;
  end if;
  if current_status = 'rejected' and next_status not in ('draft', 'review') then
    raise exception '[SUB_STATUS_TRANSITION_DENIED] rejected -> % not allowed', next_status;
  end if;

  if next_status = 'published' and state <> 'valid' then
    raise exception '[SUB_VALIDATION_REQUIRED] Submission must be valid before publish';
  end if;

  update public.plugin_submissions
  set status = next_status,
      rejection_reason = case when next_status = 'rejected' then nullif(trim(coalesce(p_reason, '')), '') else null end,
      reviewed_at = case when next_status = 'review' and reviewed_at is null then now() else reviewed_at end,
      approved_at = case when next_status = 'approved' then now() else approved_at end,
      published_at = case when next_status = 'published' then now() else published_at end,
      published_by = case when next_status = 'published' then auth.uid() else published_by end,
      updated_at = now()
  where id = p_submission_id;

  return (
    select s from public.plugin_submissions s where s.id = p_submission_id
  );
end;
$$;

grant execute on function public.transition_plugin_submission_status(uuid, text, text) to authenticated;

create or replace function public.plugin_submission_before_write()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  if tg_op = 'INSERT' then
    new.created_by := coalesce(new.created_by, auth.uid());
    new.created_by_email := coalesce(new.created_by_email, nullif(trim(coalesce(auth.jwt()->>'email', '')), ''));
    new.status := lower(trim(coalesce(new.status, 'draft')));
    if new.status not in ('draft', 'review', 'approved', 'published', 'rejected') then
      raise exception '[SUB_STATUS_INVALID] Invalid status: %', new.status;
    end if;
    if new.status <> 'draft' then
      raise exception '[SUB_STATUS_TRANSITION_DENIED] New submissions must start in draft';
    end if;
    new.updated_at := now();
    new.validation_errors := public.marketplace_validate_submission(new);
    new.validation_state := case when jsonb_array_length(new.validation_errors) = 0 then 'valid' else 'invalid' end;
    return new;
  end if;

  new.status := lower(trim(coalesce(new.status, old.status)));
  if new.status not in ('draft', 'review', 'approved', 'published', 'rejected') then
    raise exception '[SUB_STATUS_INVALID] Invalid status: %', new.status;
  end if;

  if new.status <> old.status then
    if old.status = 'draft' and new.status not in ('review', 'rejected') then
      raise exception '[SUB_STATUS_TRANSITION_DENIED] draft -> % not allowed', new.status;
    end if;
    if old.status = 'review' and new.status not in ('approved', 'rejected', 'draft') then
      raise exception '[SUB_STATUS_TRANSITION_DENIED] review -> % not allowed', new.status;
    end if;
    if old.status = 'approved' and new.status not in ('published', 'rejected', 'review') then
      raise exception '[SUB_STATUS_TRANSITION_DENIED] approved -> % not allowed', new.status;
    end if;
    if old.status = 'published' and new.status not in ('published') then
      raise exception '[SUB_STATUS_TRANSITION_DENIED] published -> % not allowed', new.status;
    end if;
    if old.status = 'rejected' and new.status not in ('draft', 'review') then
      raise exception '[SUB_STATUS_TRANSITION_DENIED] rejected -> % not allowed', new.status;
    end if;
  end if;

  new.validation_errors := public.marketplace_validate_submission(new);
  new.validation_state := case when jsonb_array_length(new.validation_errors) = 0 then 'valid' else 'invalid' end;

  if new.status = 'published' and new.validation_state <> 'valid' then
    raise exception '[SUB_VALIDATION_REQUIRED] Submission must be valid before publish';
  end if;

  new.reviewed_at := case when new.status = 'review' and old.reviewed_at is null then now() else old.reviewed_at end;
  new.approved_at := case when new.status = 'approved' and new.status <> old.status then now() else old.approved_at end;
  new.published_at := case when new.status = 'published' and new.status <> old.status then now() else old.published_at end;
  new.published_by := case when new.status = 'published' and new.status <> old.status then auth.uid() else old.published_by end;
  if new.status <> 'rejected' then
    new.rejection_reason := null;
  end if;
  new.updated_at := now();
  return new;
end;
$$;

drop trigger if exists trg_plugin_submission_before_write on public.plugin_submissions;
create trigger trg_plugin_submission_before_write
before insert or update on public.plugin_submissions
for each row
execute function public.plugin_submission_before_write();

create or replace function public.plugin_submission_after_status_change()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  if tg_op = 'INSERT' then
    insert into public.plugin_submission_status_history (
      submission_id,
      changed_by,
      from_status,
      to_status,
      reason,
      validation_state,
      validation_errors
    ) values (
      new.id,
      auth.uid(),
      null,
      new.status,
      'created',
      new.validation_state,
      new.validation_errors
    );
    return new;
  end if;

  if old.status is distinct from new.status then
    insert into public.plugin_submission_status_history (
      submission_id,
      changed_by,
      from_status,
      to_status,
      reason,
      validation_state,
      validation_errors
    ) values (
      new.id,
      auth.uid(),
      old.status,
      new.status,
      case when new.status = 'rejected' then new.rejection_reason else null end,
      new.validation_state,
      new.validation_errors
    );
  end if;

  return new;
end;
$$;

drop trigger if exists trg_plugin_submission_after_status_change on public.plugin_submissions;
create trigger trg_plugin_submission_after_status_change
after insert or update on public.plugin_submissions
for each row
execute function public.plugin_submission_after_status_change();

alter table public.marketplace_admins enable row level security;
alter table public.plugin_submissions enable row level security;
alter table public.plugin_submission_status_history enable row level security;

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

drop policy if exists "plugin_submission_status_history_admin_read" on public.plugin_submission_status_history;
create policy "plugin_submission_status_history_admin_read"
on public.plugin_submission_status_history
for select
to authenticated
using (public.is_marketplace_admin());

create index if not exists idx_plugin_submissions_created_at
  on public.plugin_submissions (created_at desc);

create index if not exists idx_plugin_submissions_plugin_id
  on public.plugin_submissions (plugin_id);

create index if not exists idx_plugin_submissions_status
  on public.plugin_submissions (status);

create index if not exists idx_plugin_submissions_validation_state
  on public.plugin_submissions (validation_state);

create index if not exists idx_plugin_submission_history_submission
  on public.plugin_submission_status_history (submission_id, changed_at desc);

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
