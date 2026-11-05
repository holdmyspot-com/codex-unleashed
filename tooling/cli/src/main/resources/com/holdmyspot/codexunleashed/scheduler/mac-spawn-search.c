/* Native replacements for posix_spawnp and posix_spawn, loaded by mac-spawn-search.js. */
typedef void *Attributes;
typedef int (*Spawn) (int * pid, const char * path, const void * actions, Attributes * attributes,
    char * const * argv, char * const * environment);
typedef struct {
  Attributes * attributes;
  short flags;
} Search;

extern Spawn originals[2];
extern unsigned long search_key;
extern int get_flags (Attributes * attributes, short * flags);
extern int set_flags (Attributes * attributes, short flags);
extern void * get_search (unsigned long key);
extern int set_search (unsigned long key, const void * value);

int replacement_posix_spawnp (int * pid, const char * path, const void * actions, Attributes * attributes,
    char * const * argv, char * const * environment)
{
  Search search;
  void * outer;
  int result;

  // Without readable flags the spawn itself rejects the attribute object, so there is nothing to restore.
  if (attributes == 0 || get_flags (attributes, &search.flags) != 0)
    return originals[0] (pid, path, actions, attributes, argv, environment);
  search.attributes = attributes;
  outer = get_search (search_key);
  if (set_search (search_key, &search) != 0)
    return originals[0] (pid, path, actions, attributes, argv, environment);
  result = originals[0] (pid, path, actions, attributes, argv, environment);
  set_search (search_key, outer);
  set_flags (attributes, search.flags);
  return result;
}

int replacement_posix_spawn (int * pid, const char * path, const void * actions, Attributes * attributes,
    char * const * argv, char * const * environment)
{
  int result = originals[1] (pid, path, actions, attributes, argv, environment);
  Search * search = get_search (search_key);

  if (search != 0)
    set_flags (search->attributes, search->flags);
  return result;
}
