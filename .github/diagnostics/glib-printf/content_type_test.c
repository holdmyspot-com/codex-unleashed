#include <gio/gio.h>

static gpointer
check_content_types (gpointer unused)
{
  gchar *type;
  gchar *mime;
  gchar *description;
  gboolean uncertain;

  (void) unused;
  g_assert_true (g_content_type_equals ("PUBLIC.PNG", "public.png"));
  g_assert_false (g_content_type_equals ("public.png", "public.jpeg"));
  g_assert_true (g_content_type_is_a ("public.png", "public.image"));
  g_assert_false (g_content_type_is_a ("public.png", "public.audio"));
  g_assert_false (g_content_type_is_a ("public.png", "org.codex.unregistered"));
  g_assert_false (g_content_type_is_a ("org.codex.unregistered", "public.image"));
  g_assert_true (g_content_type_can_be_executable ("com.apple.application-bundle"));
  g_assert_true (g_content_type_can_be_executable ("public.shell-script"));
  g_assert_false (g_content_type_can_be_executable ("public.png"));

  type = g_content_type_from_mime_type ("image/png");
  g_assert_cmpstr (type, ==, "public.png");
  mime = g_content_type_get_mime_type (type);
  g_assert_cmpstr (mime, ==, "image/png");
  g_free (mime);
  description = g_content_type_get_description (type);
  g_assert_nonnull (description);
  g_assert_cmpstr (description, !=, "unknown");
  g_free (description);
  g_free (type);

  mime = g_content_type_get_mime_type ("org.codex.unregistered");
  g_assert_cmpstr (mime, ==, "application/octet-stream");
  g_free (mime);
  description = g_content_type_get_description ("org.codex.unregistered");
  g_assert_cmpstr (description, ==, "unknown");
  g_free (description);

  type = g_content_type_guess ("image.png", NULL, 0, &uncertain);
  g_assert_cmpstr (type, ==, "public.png");
  g_assert_false (uncertain);
  g_free (type);
  type = g_content_type_guess ("widget.ui", NULL, 0, NULL);
  g_assert_cmpstr (type, ==, "public.xml");
  g_free (type);
  type = g_content_type_guess ("document.txt", NULL, 0, NULL);
  g_assert_cmpstr (type, ==, "public.text");
  g_free (type);
  type = g_content_type_guess ("/Volumes/Drive/", NULL, 0, NULL);
  g_assert_cmpstr (type, ==, "public.volume");
  g_free (type);
  type = g_content_type_guess ("/folder.codex-unregistered-extension/", NULL, 0, &uncertain);
  g_assert_cmpstr (type, ==, "public.folder");
  g_assert_true (uncertain);
  g_free (type);
  type = g_content_type_guess ("image.codex-unregistered-extension", NULL, 0, NULL);
  g_assert_true (g_str_has_prefix (type, "dyn."));
  g_free (type);
  return NULL;
}

int
main (void)
{
  GThread *thread = g_thread_new ("content-types", check_content_types, NULL);
  g_thread_join (thread);
  return 0;
}
