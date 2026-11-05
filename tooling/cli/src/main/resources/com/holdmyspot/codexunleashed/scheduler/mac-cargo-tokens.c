/* Native read and write replacements for the Cargo jobserver FIFO, loaded by mac-cargo-tokens.js. */
typedef long (*Transfer) (int fd, void * buffer, unsigned long count);

extern int descriptor_stat (int fd, void * data);
extern int descriptor_flags (int fd, int command);
extern int * error_location (void);
extern long mediate (int operation, void * buffer, unsigned long count, int blocking);
extern void fail (int operation, int cause);
extern unsigned int fifo_device;
extern unsigned long long fifo_inode;
extern Transfer originals[2];

/* Darwin fcntl F_GETFL command, O_NONBLOCK flag and EAGAIN. */
#define GET_FLAGS 3
#define NONBLOCKING 0x4
#define AGAIN 35

/* Inspection failures passed to fail, in the order mac-cargo-tokens.js names them. */
#define STAT_FAILED 0
#define FLAGS_UNAVAILABLE 1

/*
 * Mediates a one-byte transfer of the jobserver FIFO. A read through an O_NONBLOCK descriptor is answered at once, and
 * a declined one fails with EAGAIN as the native read of an empty FIFO would.
 */
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
    fail (operation, STAT_FAILED);
  }
  unsigned char * bytes = (unsigned char *) data;
  int fifo = ((*(unsigned short *) (bytes + 4)) & 0xf000) == 0x1000;
  if (!fifo || *(unsigned int *) bytes != fifo_device || *(unsigned long long *) (bytes + 8) != fifo_inode)
    return original (fd, buffer, count);
  int blocking = 1;
  if (operation == 0)
  {
    int flags = descriptor_flags (fd, GET_FLAGS);
    if (flags < 0)
      fail (operation, FLAGS_UNAVAILABLE);
    blocking = (flags & NONBLOCKING) == 0;
  }
  *error_location () = saved;
  long result = mediate (operation, buffer, count, blocking);
  *error_location () = result < 0 ? AGAIN : saved;
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
