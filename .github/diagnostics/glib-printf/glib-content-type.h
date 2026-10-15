/* WORKAROUND: https://github.com/holdmyspot-com/codex-unleashed/issues/61
 * Replace deprecated CoreServices UTI calls with UniformTypeIdentifiers.
 * Remove when the pinned GLib release uses the modern API itself.
 */
#include <UniformTypeIdentifiers/UniformTypeIdentifiers.h>

static gboolean
content_type_equals (CFStringRef first, CFStringRef second)
{
  @autoreleasepool
    {
      UTType *type = [UTType typeWithIdentifier: (NSString *) first];
      UTType *other = [UTType typeWithIdentifier: (NSString *) second];
      return [type isEqual: other];
    }
}

static gboolean
content_type_conforms_to (CFStringRef identifier, CFStringRef parent_identifier)
{
  @autoreleasepool
    {
      UTType *type = [UTType typeWithIdentifier: (NSString *) identifier];
      UTType *parent = [UTType typeWithIdentifier: (NSString *) parent_identifier];
      return parent != nil && [type conformsToType: parent];
    }
}

static CFStringRef
content_type_copy_description (CFStringRef identifier)
{
  @autoreleasepool
    {
      UTType *type = [UTType typeWithIdentifier: (NSString *) identifier];
      NSString *description = [type localizedDescription];
      return description == nil ? NULL : CFStringCreateCopy (NULL, (CFStringRef) description);
    }
}

static CFStringRef
content_type_create_identifier_for_tag (NSString *tag_class, CFStringRef tag)
{
  @autoreleasepool
    {
      UTType *type = [UTType typeWithTag: (NSString *) tag
                              tagClass: tag_class
                      conformingToType: nil];
      NSString *identifier = [type identifier];
      return identifier == nil ? NULL : CFStringCreateCopy (NULL, (CFStringRef) identifier);
    }
}

static CFStringRef
content_type_copy_mime_type (CFStringRef identifier)
{
  @autoreleasepool
    {
      UTType *type = [UTType typeWithIdentifier: (NSString *) identifier];
      NSString *mime_type = [type preferredMIMEType];
      return mime_type == nil ? NULL : CFStringCreateCopy (NULL, (CFStringRef) mime_type);
    }
}
