/* Native Cargo registration and exact descriptor transport. Build as a bridge or MC_WRAPPER.
 * All vectors use application-owned network-order uint32 lengths and exact UTF-8 bytes.
 * The compiler wrapper remains alive as Cargo's child until an authenticated controller responds.
 */
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <sys/proc_info.h>
#include <sys/sysctl.h>
#include <libproc.h>
#include <mach/mach.h>
#include <dlfcn.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <limits.h>

/* One bounded exact private protocol frame. */
#define MC_LIMIT (1024U*1024U)
/* Retain partial socket ownership before any externally visible effect. */
struct mc_server { int fd,bound,close_attempted,close_error;char path[104];dev_t device;ino_t inode; };
/* Every imported stream remains owned independently of compiler retry lifetimes. */
struct mc_peer { int fd,streams[3],received,responded,close_attempted,close_error;pid_t pid;uid_t uid;gid_t gid;audit_token_t token; };

/* Transfer exactly the requested bytes, preserving the first native failure. */
static int mc_io(int fd,void *value,size_t size,int writing) {
 unsigned char *cursor=value;
 while(size){ssize_t n=writing?write(fd,cursor,size):read(fd,cursor,size);if(n<0 && errno==EINTR)continue;if(n<=0)return n==0?EPIPE:errno;cursor+=n;size-=(size_t)n;}
 return 0;
}

/* Bind one fresh socket; retain the partial owner even after a failed creation. */
int mc_listen(const char *path,int uid,int gid,void **output) {
 if(!path || !output || uid<0 || gid<0 || strlen(path)>=sizeof(((struct mc_server *)0)->path))return EINVAL;
 struct mc_server *server=calloc(1,sizeof *server);if(!server)return ENOMEM;server->fd=-1;strcpy(server->path,path);*output=server;
 server->fd=socket(AF_UNIX,SOCK_STREAM,0);if(server->fd<0)return errno;
 if(fcntl(server->fd,F_SETFD,FD_CLOEXEC)<0 || fcntl(server->fd,F_SETFL,O_NONBLOCK)<0)return errno;
 struct sockaddr_un address;memset(&address,0,sizeof address);address.sun_family=AF_UNIX;strcpy(address.sun_path,path);
 if(bind(server->fd,(struct sockaddr *)&address,sizeof address)<0)return errno;server->bound=1;
 struct stat identity;if(lstat(path,&identity)<0)return errno;server->device=identity.st_dev;server->inode=identity.st_ino;
 if(chown(path,(uid_t)uid,(gid_t)gid)<0 || chmod(path,0600)<0 || listen(server->fd,128)<0)return errno;
 return 0;
}

/* Accept one kernel-authenticated peer; return -1 only when no client is currently queued. */
int mc_accept(void *handle,void **output,int *pid,int *uid,int *gid) {
 struct mc_server *server=handle;if(!server || !output || !pid || !uid || !gid)return EINVAL;*output=NULL;
 struct mc_peer *peer=calloc(1,sizeof *peer);if(!peer)return ENOMEM;peer->fd=-1;for(int i=0;i<3;i++)peer->streams[i]=-1;
 peer->fd=accept(server->fd,NULL,NULL);if(peer->fd<0){int error=errno;free(peer);return error==EAGAIN || error==EWOULDBLOCK?-1:error;}*output=peer;
 if(fcntl(peer->fd,F_SETFD,FD_CLOEXEC)<0)return errno;
 /* Darwin accepted sockets inherit the listener's O_NONBLOCK; registration and replies are blocking exchanges. */
 int flags=fcntl(peer->fd,F_GETFL);if(flags<0 || fcntl(peer->fd,F_SETFL,flags&~O_NONBLOCK)<0)return errno;
 int enabled=1;if(setsockopt(peer->fd,SOL_SOCKET,SO_NOSIGPIPE,&enabled,sizeof enabled)<0)return errno;
 socklen_t bytes=sizeof peer->pid;if(getsockopt(peer->fd,SOL_LOCAL,LOCAL_PEERPID,&peer->pid,&bytes)<0)return errno;
 if(bytes!=sizeof peer->pid || peer->pid<=0)return EIO;
 if(getpeereid(peer->fd,&peer->uid,&peer->gid)<0)return errno;
 bytes=sizeof peer->token;if(getsockopt(peer->fd,SOL_LOCAL,LOCAL_PEERTOKEN,&peer->token,&bytes)<0)return errno;
 if(bytes!=sizeof peer->token || peer->token.val[5]!=(uint32_t)peer->pid || peer->token.val[1]!=peer->uid || peer->token.val[2]!=peer->gid)return EPERM;
 if(peer->uid>INT_MAX || peer->gid>INT_MAX)return EOVERFLOW;
 *pid=peer->pid;*uid=(int)peer->uid;*gid=(int)peer->gid;return 0;
}

/* Receive exactly three borrowed descriptions, then exactly one bounded complete registration frame. */
int mc_receive(void *handle,void *output,int capacity,int *bytes) {
 struct mc_peer *peer=handle;if(!peer || !output || !bytes || capacity<=0 || peer->received)return EINVAL;peer->received=1;*bytes=0;
 char value=0,ancillary[CMSG_SPACE(3*sizeof(int))];struct iovec vector={&value,1};struct msghdr message;
 memset(&message,0,sizeof message);memset(ancillary,0,sizeof ancillary);message.msg_iov=&vector;message.msg_iovlen=1;message.msg_control=ancillary;message.msg_controllen=sizeof ancillary;
 ssize_t n;do{n=recvmsg(peer->fd,&message,0);}while(n<0 && errno==EINTR);if(n<0)return errno;
 struct cmsghdr *header=CMSG_FIRSTHDR(&message);
 if(header && header->cmsg_level==SOL_SOCKET && header->cmsg_type==SCM_RIGHTS && header->cmsg_len>=CMSG_LEN(0)){
  size_t imported=header->cmsg_len-CMSG_LEN(0);if(imported<=3*sizeof(int) && imported%sizeof(int)==0)memcpy(peer->streams,CMSG_DATA(header),imported);
 }
 if(n!=1 || value!='C' || (message.msg_flags&(MSG_CTRUNC|MSG_TRUNC))!=0 || !header || header->cmsg_level!=SOL_SOCKET || header->cmsg_type!=SCM_RIGHTS || header->cmsg_len!=CMSG_LEN(3*sizeof(int)) || CMSG_NXTHDR(&message,header)!=NULL)return EPROTO;
 for(int i=0;i<3;i++){if(peer->streams[i]<0)return EBADF;if(fcntl(peer->streams[i],F_SETFD,FD_CLOEXEC)<0)return errno;}
 uint32_t size;int error=mc_io(peer->fd,&size,sizeof size,0);if(error)return error;size=ntohl(size);
 if(size==0 || size>MC_LIMIT || size>(uint32_t)capacity)return EOVERFLOW;
 error=mc_io(peer->fd,output,size,0);if(!error)*bytes=(int)size;return error;
}

/* Return borrowed imported descriptors; only a native workload duplicates them for an attempt. */
int mc_streams(void *handle,int output[3]) {
 struct mc_peer *peer=handle;if(!peer || !output || !peer->received)return EINVAL;
 for(int i=0;i<3;i++){if(peer->streams[i]<0)return EBADF;output[i]=peer->streams[i];}return 0;
}

/* Add one exact string without trusting a terminator outside the bounded frame. */
static int mc_string(unsigned char *payload,size_t *used,const char *text) {
 size_t size=strlen(text);if(size>MC_LIMIT || *used>MC_LIMIT-4 || size>MC_LIMIT-*used-4)return EOVERFLOW;
 uint32_t length=htonl((uint32_t)size);memcpy(payload+*used,&length,4);*used+=4;memcpy(payload+*used,text,size);*used+=size;return 0;
}
/* Encode a complete argv or environment vector, preserving empty argument strings. */
static int mc_vector(unsigned char *payload,size_t *used,char **values,int count) {
 if(count<0 || count>65536 || *used>MC_LIMIT-4)return EOVERFLOW;uint32_t size=htonl((uint32_t)count);memcpy(payload+*used,&size,4);*used+=4;
 for(int i=0;i<count;i++){int error=mc_string(payload,used,values[i]);if(error)return error;}return 0;
}

/* Pin the socket's actual audit-token incarnation, including image version and executable UUID.
 * This uses the same private XNU ABI proved by the workload owner, never a newly selected PID. */
struct mc_unique { uint8_t uuid[16];uint64_t unique,parent;int32_t version,original_parent_version;uint64_t reserved[2]; };
typedef int (*mc_query)(int32_t,int32_t,uint32_t,uint32_t,uint64_t,uint64_t,void *,int32_t);
static int mc_identity_of(pid_t pid,uint32_t version,struct mc_unique *identity) {
 mc_query query=(mc_query)dlsym(RTLD_DEFAULT,"__proc_info_extended_id");if(!query)return ENOTSUP;
 errno=0;int bytes=query(2,pid,17,1,version,0,identity,sizeof *identity);
 return bytes==(int)sizeof *identity?0:(errno?errno:EIO);
}
static int mc_identity(struct mc_peer *peer,struct mc_unique *identity) { return mc_identity_of(peer->pid,peer->token.val[7],identity); }

/* Expose the kernel endpoint incarnation for the consuming workload authority's independent comparison. */
int mc_peer_identity(void *handle,uint64_t output[5]) {
 struct mc_peer *peer=handle;if(!peer || !output)return EINVAL;struct mc_unique identity;
 int error=mc_identity(peer,&identity);if(error)return error;
 output[0]=(uint64_t)peer->pid;output[1]=identity.unique;output[2]=(uint32_t)identity.version;output[3]=peer->uid;output[4]=peer->gid;return 0;
}

/* Read one process's exact initial kernel argv and environment, requiring at least minimum arguments.
 * On success the caller frees *raw, *arguments and *environment; the vectors point into *raw. */
static int mc_initial_vectors(pid_t pid,int minimum,unsigned char **raw_out,char ***arguments_out,int *argument_count_out,
 char ***environment_out,int *environment_count_out) {
 *raw_out=NULL;*arguments_out=NULL;*environment_out=NULL;*argument_count_out=0;*environment_count_out=0;
 int maximum=0;size_t maximum_bytes=sizeof maximum;if(sysctlbyname("kern.argmax",&maximum,&maximum_bytes,NULL,0)<0)return errno;
 if(maximum_bytes!=sizeof maximum || maximum<=0 || maximum>(int)MC_LIMIT)return EOVERFLOW;
 size_t raw_size=(size_t)maximum+sizeof(int);unsigned char *raw=calloc(raw_size,1);if(!raw)return ENOMEM;*raw_out=raw;
 int mib[]={CTL_KERN,KERN_PROCARGS2,pid};if(sysctl(mib,3,raw,&raw_size,NULL,0)<0)return errno;
 if(raw_size<sizeof(int))return EPROTO;int argument_count;memcpy(&argument_count,raw,sizeof argument_count);
 if(argument_count<minimum || argument_count>65536)return EPROTO;
 unsigned char *cursor=raw+sizeof(int),*end=raw+raw_size,*terminator=memchr(cursor,0,(size_t)(end-cursor));
 if(!terminator)return EPROTO;cursor=terminator+1;while(cursor<end && *cursor==0)cursor++;
 char **arguments=calloc((size_t)argument_count+1,sizeof *arguments);*arguments_out=arguments;
 char **environment=calloc(65537,sizeof *environment);*environment_out=environment;
 if(!arguments || !environment)return ENOMEM;
 for(int i=0;i<argument_count;i++){
  terminator=memchr(cursor,0,(size_t)(end-cursor));if(!terminator)return EPROTO;
  arguments[i]=(char *)cursor;cursor=terminator+1;
 }
 int environment_count=0;
 while(cursor<end && *cursor!=0){
  if(environment_count==65536)return EOVERFLOW;
  terminator=memchr(cursor,0,(size_t)(end-cursor));if(!terminator)return EPROTO;
  environment[environment_count++]=(char *)cursor;cursor=terminator+1;
 }
 *argument_count_out=argument_count;*environment_count_out=environment_count;return 0;
}

/* Independently read exact initial kernel argv/environment and current cwd of the same socket endpoint.
 * The trusted fixed wrapper does not mutate its initial vectors or cwd while awaiting the controller.
 * Restricted or omitted environments cannot match its required nonempty registration environment.
 * Consumers separately authenticate workload membership, direct parenting and executable vnode. */
int mc_capture(void *handle,void *output,int capacity,int *bytes) {
 struct mc_peer *peer=handle;if(!peer || !output || capacity<=0 || !bytes || !peer->received)return EINVAL;*bytes=0;
 struct mc_unique before,after;int error=mc_identity(peer,&before);if(error)return error;
 unsigned char *raw=NULL,*payload=calloc(MC_LIMIT,1);char **arguments=NULL,**environment=NULL;int argument_count=0,environment_count=0;
 if(!payload){error=ENOMEM;goto done;}
 error=mc_initial_vectors(peer->pid,2,&raw,&arguments,&argument_count,&environment,&environment_count);if(error)goto done;
 struct proc_vnodepathinfo cwd;memset(&cwd,0,sizeof cwd);errno=0;
 if(proc_pidinfo(peer->pid,PROC_PIDVNODEPATHINFO,0,&cwd,sizeof cwd)!=(int)sizeof cwd){error=errno?errno:EIO;goto done;}
 if(cwd.pvi_cdir.vip_path[0]!='/' || !memchr(cwd.pvi_cdir.vip_path,0,sizeof cwd.pvi_cdir.vip_path)){error=EPROTO;goto done;}
 char *working_directory[]={cwd.pvi_cdir.vip_path,NULL};size_t used=0;
 error=mc_vector(payload,&used,arguments+1,argument_count-1);if(!error)error=mc_vector(payload,&used,environment,environment_count);if(!error)error=mc_vector(payload,&used,working_directory,1);if(error)goto done;
 error=mc_identity(peer,&after);if(error)goto done;
 if(before.unique!=after.unique || before.version!=after.version || memcmp(before.uuid,after.uuid,sizeof before.uuid)!=0){error=ESTALE;goto done;}
 if(used>(size_t)capacity){error=EOVERFLOW;goto done;}memcpy(output,payload,used);*bytes=(int)used;
 done:free(arguments);free(environment);free(raw);free(payload);return error;
}

/* Read the exact initial kernel environment of the endpoint's direct parent image, such as a Cargo build script.
 * version is the parent's image version; a parent that exits or execs another image fails with the identity query
 * or ESTALE. The frame holds one environment vector; consumers separately authenticate the parent's lineage. */
int mc_parent_environment(void *handle,int pid,int version,void *output,int capacity,int *bytes) {
 struct mc_peer *peer=handle;if(!peer || !output || capacity<=0 || !bytes || !peer->received || pid<=0 || pid==peer->pid)return EINVAL;
 *bytes=0;struct mc_unique before,after;int error=mc_identity_of(pid,(uint32_t)version,&before);if(error)return error;
 unsigned char *raw=NULL,*payload=calloc(MC_LIMIT,1);char **arguments=NULL,**environment=NULL;int argument_count=0,environment_count=0;
 if(!payload){error=ENOMEM;goto done;}
 error=mc_initial_vectors(pid,1,&raw,&arguments,&argument_count,&environment,&environment_count);if(error)goto done;
 size_t used=0;error=mc_vector(payload,&used,environment,environment_count);if(error)goto done;
 error=mc_identity_of(pid,(uint32_t)version,&after);if(error)goto done;
 if(before.unique!=after.unique || before.version!=after.version || memcmp(before.uuid,after.uuid,sizeof before.uuid)!=0){error=ESTALE;goto done;}
 if(used>(size_t)capacity){error=EOVERFLOW;goto done;}memcpy(output,payload,used);*bytes=(int)used;
 done:free(arguments);free(environment);free(raw);free(payload);return error;
}

/* Publish exactly one genuine compiler result; uncertain writes are never replayed. */
int mc_respond(void *handle,int result) {
 struct mc_peer *peer=handle;if(!peer || result<0 || result>255 || peer->responded)return EINVAL;peer->responded=1;
 uint32_t value=htonl((uint32_t)result);return mc_io(peer->fd,&value,sizeof value,1);
}

/* Close imported streams and the peer only after the caller has collected every attempt using them. */
int mc_close_peer(void *handle) {
 struct mc_peer *peer=handle;if(!peer)return EINVAL;if(peer->close_attempted)return peer->close_error;peer->close_attempted=1;int error=0;
 for(int i=0;i<3;i++)if(peer->streams[i]>=0 && close(peer->streams[i])<0 && !error)error=errno;
 if(peer->fd>=0 && close(peer->fd)<0 && !error)error=errno;peer->close_error=error;if(!error)free(peer);return error;
}

/* Remove only the exact socket incarnation created by this owner. */
int mc_close_server(void *handle) {
 struct mc_server *server=handle;if(!server)return EINVAL;if(server->close_attempted)return server->close_error;server->close_attempted=1;int error=0;
 if(server->fd>=0 && close(server->fd)<0)error=errno;
 if(server->bound){struct stat identity;if(lstat(server->path,&identity)<0){if(!error)error=errno;}else if(identity.st_dev!=server->device || identity.st_ino!=server->inode){if(!error)error=ESTALE;}else if(unlink(server->path)<0 && !error)error=errno;}
 server->close_error=error;if(!error)free(server);return error;
}

/* Own one fresh FIFO and retain its exact vnode before publication or token effects. */
struct mc_fifo { int fd,created,close_attempted,close_error;dev_t device;ino_t inode;char path[PATH_MAX]; };

/* Create explicit extra capacity; the implicit Cargo slot is never inserted in this FIFO. */
int mc_fifo_create(const char *path,int uid,int gid,int jobs,void **output) {
 if(!path || !output || path[0]!='/' || strlen(path)>=PATH_MAX || uid<=0 || gid<=0 || jobs<1 || jobs>1024)return EINVAL;
 *output=NULL;struct mc_fifo *owner=calloc(1,sizeof *owner);if(!owner)return ENOMEM;
 owner->fd=-1;strcpy(owner->path,path);*output=owner;
 if(mkfifo(path,0600)<0)return errno;owner->created=1;
 struct stat initial;if(lstat(path,&initial)<0)return errno;
 owner->device=initial.st_dev;owner->inode=initial.st_ino;
 if(!S_ISFIFO(initial.st_mode))return EPROTO;
 if(chown(path,(uid_t)uid,(gid_t)gid)<0)return errno;
 owner->fd=open(path,O_RDWR|O_NONBLOCK|O_CLOEXEC|O_NOFOLLOW);if(owner->fd<0)return errno;
 struct stat opened,current;if(fstat(owner->fd,&opened)<0 || lstat(path,&current)<0)return errno;
 if(!S_ISFIFO(opened.st_mode) || opened.st_dev!=owner->device || opened.st_ino!=owner->inode || current.st_dev!=owner->device || current.st_ino!=owner->inode || opened.st_uid!=(uid_t)uid || opened.st_gid!=(gid_t)gid || (opened.st_mode&07777)!=0600)return ESTALE;
 for(int i=1;i<jobs;i++){
  unsigned char token=(unsigned char)(i-1);ssize_t n;
  do{n=write(owner->fd,&token,1);}while(n<0 && errno==EINTR);
  if(n!=1)return n<0?errno:EIO;
 }
 return 0;
}

/* Revalidate the actual retained descriptor before exposing its exact FIFO vnode identity. */
int mc_fifo_identity(void *handle,uint64_t output[2]) {
 struct mc_fifo *owner=handle;if(!owner || !output || owner->fd<0 || owner->close_attempted)return EINVAL;
 struct stat actual;if(fstat(owner->fd,&actual)<0)return errno;
 if(!S_ISFIFO(actual.st_mode) || actual.st_dev!=owner->device || actual.st_ino!=owner->inode)return ESTALE;
 output[0]=(uint64_t)actual.st_dev;output[1]=(uint64_t)actual.st_ino;return 0;
}

/* Return an exact byte or -1 only for an independently known empty nonblocking FIFO read. */
int mc_fifo_read(void *handle,int *output) {
 struct mc_fifo *owner=handle;if(!owner || !output || owner->fd<0 || owner->close_attempted)return EINVAL;
 *output=-1;unsigned char token;ssize_t n;
 do{n=read(owner->fd,&token,1);}while(n<0 && errno==EINTR);
 if(n<0){int error=errno;return error==EAGAIN || error==EWOULDBLOCK?0:error;}
 if(n!=1)return EPIPE;*output=(int)token;return 0;
}

/* Return precisely the original opaque byte; callers halt accounting after any uncertain native effect. */
int mc_fifo_write(void *handle,int value) {
 struct mc_fifo *owner=handle;if(!owner || owner->fd<0 || owner->close_attempted || value<0 || value>255)return EINVAL;
 unsigned char token=(unsigned char)value;ssize_t n;
 do{n=write(owner->fd,&token,1);}while(n<0 && errno==EINTR);
 return n==1?0:(n<0?errno:EIO);
}

/* Close once after all consumers retire, unlinking only the exact retained FIFO vnode. */
int mc_fifo_close(void *handle) {
 struct mc_fifo *owner=handle;if(!owner)return EINVAL;
 if(owner->close_attempted)return owner->close_error;owner->close_attempted=1;int error=0;
 if(owner->fd>=0 && close(owner->fd)<0)error=errno;
 if(owner->created){
  struct stat current;
  if(lstat(owner->path,&current)<0){if(!error)error=errno;}
  else if(current.st_dev!=owner->device || current.st_ino!=owner->inode || !S_ISFIFO(current.st_mode)){if(!error)error=ESTALE;}
  else if(unlink(owner->path)<0 && !error)error=errno;
 }
 owner->close_error=error;if(!error)free(owner);return error;
}

#ifdef MC_WRAPPER
#include <poll.h>
#include <signal.h>

/* Start of the rustc artifact notification line, which Cargo accepts only once per compiler invocation. */
#define MC_ARTIFACT "{\"$message_type\":\"artifact\""
/* Every compiler attempt writes to the same relayed stderr, and Cargo panics when one compiler invocation reports the
 * same artifact twice, as a retried attempt does. The filter forwards a line that starts with MC_ARTIFACT only the
 * first time its exact bytes are seen and passes every other byte through in order. The stream has no attempt
 * boundary, so an incomplete last line of a stopped attempt joins the next attempt's first line.
 * held is the current line while it may be, or is, an artifact notification; out is -1 once Cargo's stderr fails,
 * after which bytes are still read so that attempts never block, but are discarded. */
struct mc_filter { unsigned char *held;size_t used,size;unsigned char **seen;size_t *lengths;size_t count;int passing,out; };

/* Write bytes to Cargo's stderr, discarding them after the first failed write. */
static void mc_forward(struct mc_filter *filter,const void *bytes,size_t size) {
 if(filter->out>=0 && size && mc_io(filter->out,(void *)bytes,size,1))filter->out=-1;
}

/* Hold one byte of the current line; returns ENOMEM when the line cannot grow. */
static int mc_hold(struct mc_filter *filter,unsigned char value) {
 if(filter->used==filter->size){size_t size=filter->size?filter->size*2:256;unsigned char *grown=realloc(filter->held,size);if(!grown)return ENOMEM;filter->held=grown;filter->size=size;}
 filter->held[filter->used++]=value;return 0;
}

/* Forward a complete held artifact notification unless its exact bytes were already forwarded. */
static int mc_admit(struct mc_filter *filter) {
 for(size_t i=0;i<filter->count;i++)if(filter->lengths[i]==filter->used && !memcmp(filter->seen[i],filter->held,filter->used)){filter->used=0;return 0;}
 unsigned char **seen=realloc(filter->seen,(filter->count+1)*sizeof *seen);if(!seen)return ENOMEM;filter->seen=seen;
 size_t *lengths=realloc(filter->lengths,(filter->count+1)*sizeof *lengths);if(!lengths)return ENOMEM;filter->lengths=lengths;
 unsigned char *copy=malloc(filter->used);if(!copy)return ENOMEM;memcpy(copy,filter->held,filter->used);
 seen[filter->count]=copy;lengths[filter->count]=filter->used;filter->count++;
 mc_forward(filter,filter->held,filter->used);filter->used=0;return 0;
}

/* Filter the next bytes read from compiler stderr; returns 0 or ENOMEM. */
static int mc_filter_bytes(struct mc_filter *filter,const unsigned char *bytes,size_t size) {
 const size_t prefix=sizeof MC_ARTIFACT-1;size_t start=0;
 for(size_t i=0;i<size;i++){
  unsigned char value=bytes[i];
  if(filter->passing){if(value=='\n'){mc_forward(filter,bytes+start,i+1-start);start=i+1;filter->passing=0;}continue;}
  start=i+1;if(mc_hold(filter,value))return ENOMEM;
  if(filter->used<=prefix){
   if(value==(unsigned char)MC_ARTIFACT[filter->used-1])continue;
   mc_forward(filter,filter->held,filter->used);filter->used=0;filter->passing=value!='\n';continue;
  }
  if(value=='\n' && mc_admit(filter))return ENOMEM;
 }
 if(filter->passing)mc_forward(filter,bytes+start,size-start);
 return 0;
}

/* Relay compiler stderr until the controller answers and every copy of the pipe's write end has closed, then forward
 * an incomplete final line. Returns 0 after storing the controller's result, or the wrapper's failure status. */
static int mc_relay(int control,int errors,uint32_t *result) {
 struct mc_filter filter;memset(&filter,0,sizeof filter);filter.out=STDERR_FILENO;
 unsigned char buffer[65536];int answered=0,open=1;
 while(!answered || open){
  struct pollfd descriptors[2]={{.fd=open?errors:-1,.events=POLLIN},{.fd=answered?-1:control,.events=POLLIN}};
  if(poll(descriptors,2,-1)<0){if(errno==EINTR)continue;return 94;}
  if(descriptors[0].revents){ssize_t n=read(errors,buffer,sizeof buffer);if(n<0 && errno!=EINTR)return 95;if(n==0)open=0;if(n>0 && mc_filter_bytes(&filter,buffer,(size_t)n))return 96;}
  if(descriptors[1].revents){if(mc_io(control,result,sizeof *result,0))return 91;answered=1;*result=ntohl(*result);}
 }
 mc_forward(&filter,filter.held,filter.used);
 return 0;
}

extern char **environ;
/* Remain Cargo's direct child while the controller owns separately isolated compiler attempts. */
int main(int argc,char **argv) {
 const char *path=getenv("CODEX_CARGO_SOCKET");if(argc<2 || !path || strlen(path)>=104)return 80;
 char cwd[PATH_MAX];if(!getcwd(cwd,sizeof cwd))return 81;
 int fd=socket(AF_UNIX,SOCK_STREAM,0);if(fd<0)return 82;int enabled=1;if(setsockopt(fd,SOL_SOCKET,SO_NOSIGPIPE,&enabled,sizeof enabled)<0)return 83;
 struct sockaddr_un address;memset(&address,0,sizeof address);address.sun_family=AF_UNIX;strcpy(address.sun_path,path);if(connect(fd,(struct sockaddr *)&address,sizeof address)<0)return 84;
 uid_t uid;gid_t gid;if(getpeereid(fd,&uid,&gid)<0 || uid!=0)return 85;
 unsigned char *payload=calloc(MC_LIMIT,1);if(!payload)return 86;size_t used=0;int environment_count=0;while(environ[environment_count]){if(++environment_count>65536)return 87;}
 char *working_directory[]={cwd,NULL};int error=mc_vector(payload,&used,argv+1,argc-1);if(!error)error=mc_vector(payload,&used,environ,environment_count);if(!error)error=mc_vector(payload,&used,working_directory,1);if(error)return 88;
 char value='C',ancillary[CMSG_SPACE(3*sizeof(int))];struct iovec vector={&value,1};struct msghdr message;memset(&message,0,sizeof message);memset(ancillary,0,sizeof ancillary);
 message.msg_iov=&vector;message.msg_iovlen=1;message.msg_control=ancillary;message.msg_controllen=sizeof ancillary;struct cmsghdr *header=CMSG_FIRSTHDR(&message);header->cmsg_level=SOL_SOCKET;header->cmsg_type=SCM_RIGHTS;header->cmsg_len=CMSG_LEN(3*sizeof(int));int errors[2];if(pipe(errors)<0 || fcntl(errors[0],F_SETFD,FD_CLOEXEC)<0 || fcntl(errors[1],F_SETFD,FD_CLOEXEC)<0)return 93;
 /* A failed write to Cargo's closed stderr must end only forwarding, not the wrapper that reports the result. */
 signal(SIGPIPE,SIG_IGN);
 int streams[]={0,1,errors[1]};memcpy(CMSG_DATA(header),streams,sizeof streams);
 ssize_t sent;do{sent=sendmsg(fd,&message,0);}while(sent<0 && errno==EINTR);if(sent!=1)return 89;close(errors[1]);
 uint32_t size=htonl((uint32_t)used);error=mc_io(fd,&size,sizeof size,1);if(!error)error=mc_io(fd,payload,used,1);free(payload);if(error)return 90;
 uint32_t result;error=mc_relay(fd,errors[0],&result);if(error)return error;close(fd);close(errors[0]);return result<=255?(int)result:92;
}
#endif
