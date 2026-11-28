-- ============================================================================
-- SoarTV v2 — Household Cloud Sync & Automatic Zero-Knowledge Vault Schema
-- 1 Household = 1 Supabase Database (Multiple Users, Multiple TVs, Phones, PCs)
-- Run once in the Supabase SQL Editor (idempotent).
-- ============================================================================

-- 1. Extensions & unexposed private household authorization schema
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions;
CREATE SCHEMA IF NOT EXISTS private;

CREATE TABLE IF NOT EXISTS private.household_auth_config (
    id integer PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    household_key_sha256 text NOT NULL DEFAULT '',
    allowed_emails text[] NOT NULL DEFAULT '{}',
    updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO private.household_auth_config (id)
VALUES (1)
ON CONFLICT (id) DO NOTHING;

ALTER TABLE private.household_auth_config ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON SCHEMA private FROM PUBLIC, anon, authenticated;
REVOKE ALL ON TABLE private.household_auth_config FROM PUBLIC, anon, authenticated;

DROP POLICY IF EXISTS service_role_only_household_auth_config ON private.household_auth_config;
CREATE POLICY service_role_only_household_auth_config
    ON private.household_auth_config FOR ALL TO service_role
    USING (true) WITH CHECK (true);

-- 2. Reusable updated_at trigger & Dual-Path Household RLS authorization helper
CREATE OR REPLACE FUNCTION public.set_updated_at()
RETURNS trigger LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION private.check_household_authorization()
RETURNS boolean LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    cfg record;
    jwt_claims jsonb;
    jwt_email text;
    hdr_json jsonb;
    supplied_key text;
    supplied_hash text;
BEGIN
    IF current_user = 'service_role' THEN
        RETURN true;
    END IF;

    SELECT household_key_sha256, allowed_emails
      INTO cfg
      FROM private.household_auth_config
     WHERE id = 1;

    IF NOT FOUND THEN
        RETURN false;
    END IF;

    jwt_claims := coalesce((SELECT auth.jwt()), '{}'::jsonb);
    IF coalesce(jwt_claims ->> 'role', '') = 'service_role' THEN
        RETURN true;
    END IF;

    jwt_email := lower(coalesce(jwt_claims ->> 'email', ''));
    IF jwt_email <> '' AND cfg.allowed_emails IS NOT NULL AND jwt_email = ANY(cfg.allowed_emails) THEN
        RETURN true;
    END IF;

    BEGIN
        hdr_json := COALESCE(NULLIF(current_setting('request.headers', true), ''), '{}')::jsonb;
    EXCEPTION WHEN OTHERS THEN
        hdr_json := '{}'::jsonb;
    END;

    supplied_key := coalesce(hdr_json ->> 'x-soartv-household-key', '');
    IF char_length(supplied_key) >= 32 AND coalesce(cfg.household_key_sha256, '') <> '' THEN
        supplied_hash := encode(extensions.digest(convert_to(supplied_key, 'UTF8'), 'sha256'), 'hex');
        IF supplied_hash = lower(cfg.household_key_sha256) THEN
            RETURN true;
        END IF;
    END IF;

    RETURN false;
END;
$$;

REVOKE ALL ON FUNCTION private.check_household_authorization() FROM PUBLIC;
GRANT USAGE ON SCHEMA private TO anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION private.check_household_authorization() TO anon, authenticated, service_role;

CREATE OR REPLACE FUNCTION public.is_authorized_soartv_request()
RETURNS boolean LANGUAGE sql STABLE SECURITY INVOKER SET search_path = '' AS $$
    SELECT private.check_household_authorization();
$$;

GRANT EXECUTE ON FUNCTION public.is_authorized_soartv_request() TO anon, authenticated, service_role;

-- 3. Tier 1 — Household Tables & Automatic Multi-Slot E2EE Vault
CREATE TABLE IF NOT EXISTS public.household_settings (
    id integer PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    settings jsonb NOT NULL DEFAULT '{}'::jsonb,
    field_timestamps jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO public.household_settings (id)
VALUES (1)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS public.encrypted_vault (
    vault_id text PRIMARY KEY CHECK (char_length(trim(vault_id)) > 0),
    kdf_algorithm text NOT NULL DEFAULT 'PBKDF2-HMAC-SHA256',
    kdf_salt text NOT NULL CHECK (char_length(trim(kdf_salt)) > 0),
    kdf_iterations integer NOT NULL DEFAULT 120000 CHECK (kdf_iterations >= 100000),
    cipher_iv text NOT NULL CHECK (char_length(trim(cipher_iv)) > 0),
    ciphertext text NOT NULL CHECK (char_length(trim(ciphertext)) > 0),
    key_count integer NOT NULL DEFAULT 0 CHECK (key_count >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.gaming_hosts (
    host_id text PRIMARY KEY CHECK (char_length(trim(host_id)) > 0),
    hostname text NOT NULL DEFAULT '',
    lan_ip text NOT NULL DEFAULT '',
    agent_port integer NOT NULL DEFAULT 8485
        CONSTRAINT chk_gaming_hosts_agent_port CHECK (agent_port BETWEEN 1 AND 65535),
    mac_addresses text[] NOT NULL DEFAULT '{}',
    sunshine_uuid text NOT NULL DEFAULT '',
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- 4. Tier 2 — User Profiles, Per-Profile Settings & Media Tables
CREATE TABLE IF NOT EXISTS public.user_profiles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug text NOT NULL UNIQUE,
    display_name text NOT NULL,
    avatar_color text NOT NULL DEFAULT '#38BDF8',
    trakt_username text NOT NULL DEFAULT '',
    is_default boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.user_profile_settings (
    profile_id uuid PRIMARY KEY REFERENCES public.user_profiles(id) ON DELETE CASCADE,
    settings jsonb NOT NULL DEFAULT '{}'::jsonb,
    field_timestamps jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.watchlist_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id uuid NOT NULL REFERENCES public.user_profiles(id) ON DELETE CASCADE,
    media_key text NOT NULL,
    imdb_id text NOT NULL DEFAULT '',
    kind text NOT NULL DEFAULT 'movie' CHECK (kind IN ('movie', 'tv')),
    title text NOT NULL,
    poster_url text NOT NULL DEFAULT '',
    backdrop_url text NOT NULL DEFAULT '',
    source_app text NOT NULL DEFAULT 'stremio',
    deep_link text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (profile_id, media_key)
);

CREATE TABLE IF NOT EXISTS public.watch_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id uuid NOT NULL REFERENCES public.user_profiles(id) ON DELETE CASCADE,
    media_key text NOT NULL,
    imdb_id text NOT NULL DEFAULT '',
    kind text NOT NULL DEFAULT 'movie' CHECK (kind IN ('movie', 'tv')),
    title text NOT NULL,
    season integer NOT NULL DEFAULT 0,
    episode integer NOT NULL DEFAULT 0,
    progress_percent numeric NOT NULL DEFAULT 0
        CONSTRAINT chk_watch_history_progress_percent CHECK (progress_percent >= 0 AND progress_percent <= 100),
    is_completed boolean NOT NULL DEFAULT false,
    poster_url text NOT NULL DEFAULT '',
    backdrop_url text NOT NULL DEFAULT '',
    source_app text NOT NULL DEFAULT 'stremio',
    watched_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (profile_id, media_key)
);

CREATE TABLE IF NOT EXISTS public.media_votes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id uuid NOT NULL REFERENCES public.user_profiles(id) ON DELETE CASCADE,
    media_key text NOT NULL,
    kind text NOT NULL DEFAULT 'movie' CHECK (kind IN ('movie', 'tv')),
    title text NOT NULL DEFAULT '',
    vote smallint NOT NULL CHECK (vote IN (-1, 1)),
    voted_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (profile_id, media_key)
);

CREATE TABLE IF NOT EXISTS public.shared_reels (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sender_name text NOT NULL DEFAULT 'Remote',
    recipient_profile_id uuid REFERENCES public.user_profiles(id) ON DELETE SET NULL,
    url text NOT NULL CHECK (char_length(trim(url)) > 0),
    platform text NOT NULL DEFAULT 'reel',
    title text NOT NULL DEFAULT '',
    thumbnail_url text NOT NULL DEFAULT '',
    watched boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- 5. Tier 3 — Per-TV Device Registry & Settings (Permanent TV UUID, Cross-Brand Safe)
CREATE TABLE IF NOT EXISTS public.tv_devices (
    device_id text PRIMARY KEY CHECK (char_length(trim(device_id)) > 0),
    device_name text NOT NULL,
    room_name text NOT NULL DEFAULT 'Living Room',
    platform text NOT NULL DEFAULT 'android-tv'
        CHECK (platform IN ('android-tv', 'lg-webos', 'samsung-tizen', 'web')),
    brand text NOT NULL DEFAULT 'GENERIC',
    model text NOT NULL DEFAULT '',
    lan_ip text NOT NULL DEFAULT '',
    remote_port integer NOT NULL DEFAULT 8484
        CONSTRAINT chk_tv_devices_remote_port CHECK (remote_port BETWEEN 0 AND 65535),
    mdns_host text NOT NULL DEFAULT '',
    realtime_channel text NOT NULL DEFAULT '',
    app_version text NOT NULL DEFAULT '2.0',
    tv_settings jsonb NOT NULL DEFAULT '{}'::jsonb,
    field_timestamps jsonb NOT NULL DEFAULT '{}'::jsonb,
    is_active boolean NOT NULL DEFAULT true,
    replaced_by text NOT NULL DEFAULT '',
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- 6. Triggers
CREATE OR REPLACE TRIGGER trg_household_settings_updated_at
    BEFORE UPDATE ON public.household_settings FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE OR REPLACE TRIGGER trg_encrypted_vault_updated_at
    BEFORE UPDATE ON public.encrypted_vault FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE OR REPLACE TRIGGER trg_gaming_hosts_updated_at
    BEFORE UPDATE ON public.gaming_hosts FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE OR REPLACE TRIGGER trg_user_profiles_updated_at
    BEFORE UPDATE ON public.user_profiles FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE OR REPLACE TRIGGER trg_user_profile_settings_updated_at
    BEFORE UPDATE ON public.user_profile_settings FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE OR REPLACE TRIGGER trg_watchlist_items_updated_at
    BEFORE UPDATE ON public.watchlist_items FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE OR REPLACE TRIGGER trg_watch_history_updated_at
    BEFORE UPDATE ON public.watch_history FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE OR REPLACE TRIGGER trg_media_votes_updated_at
    BEFORE UPDATE ON public.media_votes FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE OR REPLACE TRIGGER trg_shared_reels_updated_at
    BEFORE UPDATE ON public.shared_reels FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE OR REPLACE TRIGGER trg_tv_devices_updated_at
    BEFORE UPDATE ON public.tv_devices FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- 7. Indexes
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_profiles_single_default ON public.user_profiles (is_default) WHERE is_default = true;
CREATE INDEX IF NOT EXISTS idx_user_profiles_order ON public.user_profiles (is_default DESC, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_watchlist_profile_created ON public.watchlist_items (profile_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_watch_history_profile_watched ON public.watch_history (profile_id, watched_at DESC);
CREATE INDEX IF NOT EXISTS idx_shared_reels_watched_created ON public.shared_reels (watched, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_shared_reels_recipient ON public.shared_reels (recipient_profile_id);
CREATE INDEX IF NOT EXISTS idx_tv_devices_active_seen ON public.tv_devices (is_active, last_seen_at DESC);
CREATE INDEX IF NOT EXISTS idx_gaming_hosts_seen ON public.gaming_hosts (last_seen_at DESC);

-- 8. Explicit Grants & Dual-Path RLS Policies
REVOKE TRUNCATE, REFERENCES, TRIGGER ON
    public.household_settings, public.encrypted_vault, public.gaming_hosts,
    public.user_profiles, public.user_profile_settings,
    public.watchlist_items, public.watch_history, public.media_votes,
    public.shared_reels, public.tv_devices
FROM anon, authenticated;

GRANT SELECT, INSERT, UPDATE, DELETE ON
    public.household_settings, public.encrypted_vault, public.gaming_hosts,
    public.user_profiles, public.user_profile_settings,
    public.watchlist_items, public.watch_history, public.media_votes,
    public.shared_reels, public.tv_devices
TO anon, authenticated;

ALTER TABLE public.household_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.encrypted_vault ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.gaming_hosts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_profile_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.watchlist_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.watch_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.media_votes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.shared_reels ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.tv_devices ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS household_settings_all ON public.household_settings;
CREATE POLICY household_settings_all ON public.household_settings FOR ALL TO anon, authenticated
    USING ((SELECT public.is_authorized_soartv_request())) WITH CHECK ((SELECT public.is_authorized_soartv_request()));

DROP POLICY IF EXISTS household_encrypted_vault_all ON public.encrypted_vault;
CREATE POLICY household_encrypted_vault_all ON public.encrypted_vault FOR ALL TO anon, authenticated
    USING ((SELECT public.is_authorized_soartv_request())) WITH CHECK ((SELECT public.is_authorized_soartv_request()));

DROP POLICY IF EXISTS household_gaming_hosts_all ON public.gaming_hosts;
CREATE POLICY household_gaming_hosts_all ON public.gaming_hosts FOR ALL TO anon, authenticated
    USING ((SELECT public.is_authorized_soartv_request())) WITH CHECK ((SELECT public.is_authorized_soartv_request()));

DROP POLICY IF EXISTS household_profiles_all ON public.user_profiles;
CREATE POLICY household_profiles_all ON public.user_profiles FOR ALL TO anon, authenticated
    USING ((SELECT public.is_authorized_soartv_request())) WITH CHECK ((SELECT public.is_authorized_soartv_request()));

DROP POLICY IF EXISTS household_profile_settings_all ON public.user_profile_settings;
CREATE POLICY household_profile_settings_all ON public.user_profile_settings FOR ALL TO anon, authenticated
    USING ((SELECT public.is_authorized_soartv_request())) WITH CHECK ((SELECT public.is_authorized_soartv_request()));

DROP POLICY IF EXISTS household_watchlist_all ON public.watchlist_items;
CREATE POLICY household_watchlist_all ON public.watchlist_items FOR ALL TO anon, authenticated
    USING ((SELECT public.is_authorized_soartv_request())) WITH CHECK ((SELECT public.is_authorized_soartv_request()));

DROP POLICY IF EXISTS household_history_all ON public.watch_history;
CREATE POLICY household_history_all ON public.watch_history FOR ALL TO anon, authenticated
    USING ((SELECT public.is_authorized_soartv_request())) WITH CHECK ((SELECT public.is_authorized_soartv_request()));

DROP POLICY IF EXISTS household_votes_all ON public.media_votes;
CREATE POLICY household_votes_all ON public.media_votes FOR ALL TO anon, authenticated
    USING ((SELECT public.is_authorized_soartv_request())) WITH CHECK ((SELECT public.is_authorized_soartv_request()));

DROP POLICY IF EXISTS household_reels_all ON public.shared_reels;
CREATE POLICY household_reels_all ON public.shared_reels FOR ALL TO anon, authenticated
    USING ((SELECT public.is_authorized_soartv_request())) WITH CHECK ((SELECT public.is_authorized_soartv_request()));

DROP POLICY IF EXISTS household_tv_devices_all ON public.tv_devices;
CREATE POLICY household_tv_devices_all ON public.tv_devices FOR ALL TO anon, authenticated
    USING ((SELECT public.is_authorized_soartv_request())) WITH CHECK ((SELECT public.is_authorized_soartv_request()));
