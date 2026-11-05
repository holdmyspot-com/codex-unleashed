/* Native read and write replacements for the Cargo jobserver FIFO, loaded by mac-cargo-tokens.js. */
typedef long (*Transfer) (int fd, void * buffer, unsigned long count);

extern int descriptor_stat (int fd, void * data);
extern int * error_location (void);
extern long mediate (int operation, void * buffer, unsigned long count);
extern void fail (int operation);
extern unsigned int fifo_device;
extern unsigned long long fifo_inode;
extern Transfer originals[2];

static long transfer (int operation, int fd, void * buffer, unsigned long count)
{
  Transfer original = originals[operation];
  if (count == 0)
    return original (fd, buffer, count);
  int saved = *error_location ();
  /* Darwin __DARWIN_STRUCT_STAT64 begins dev32, mode16, links16, inode64 on both supported ABIs. */
  unsigned long long data[18];
  int status = descriptor_stat (fd, data);
  int failure = *error_location ();
  *error_location () = saved;
  if (status != 0)
  {
    if (failure == 9)
      return original (fd, buffer, count);
    fail (operation);
  }
  unsigned char * bytes = (unsigned char *) data;
  int fifo = ((*(unsigned short *) (bytes + 4)) & 0xf000) == 0x1000;
  if (!fifo || *(unsigned int *) bytes != fifo_device || *(unsigned long long *) (bytes + 8) != fifo_inode)
    return original (fd, buffer, count);
  long result = mediate (operation, buffer, count);
  *error_location () = saved;
  return result;
}

long replacement_read (int fd, void * buffer, unsigned long count)
{
  return transfer (0, fd, buffer, count);
}

long replacement_write (int fd, void * buffer, unsigned long count)
{
  return transfer (1, fd, buffer, count);
}
