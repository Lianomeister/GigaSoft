// Copy this file and fill with your Supabase project values.
// The anon key is safe for frontend usage when RLS policies are configured correctly.
window.CLOCKWORK_SUPABASE = {
  url: "https://ekleitqpibtnuswedwuo.supabase.co",
  anonKey: "sb_publishable_qJ-CZdZehnMvjL1rvX0uFQ_aWaOhuWd",
  tables: {
    admins: "marketplace_admins",
    submissions: "plugin_submissions"
  },
  buckets: {
    images: "marketplace-images",
    files: "marketplace-files"
  },
  // Uploads from these accounts get automatic "core" category + "Core ✓" badge.
  // Fill your own user id/email here.
  coreUploaderIds: [],
  coreUploaderEmails: []
};
