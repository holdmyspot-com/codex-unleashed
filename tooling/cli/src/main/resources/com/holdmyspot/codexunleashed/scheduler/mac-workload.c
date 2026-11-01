/* Own one launchd workload and its original root wait. Compile this source both
 * as a shared FFM bridge and, with MW_ANCHOR, as the controlled launchd anchor.
 * Control messages use a private root-owned Unix socket, not the root's streams.
 */
#include <CoreFoundation/CoreFoundation.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/event.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/proc_info.h>
#include <libproc.h>
#include <mach/mach.h>
#include <mach/vm_prot.h>
#include <dlfcn.h>
#include <spawn.h>
#include <poll.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <limits.h>
#include <grp.h>
#include <sys/time.h>

/* Private ABI layouts are pinned by source-proof.json's XNU headers. */
struct mw_unique { uint8_t uuid[16]; uint64_t unique,parent; int32_t version,original_parent_version; uint64_t reserved[2]; };
struct mw_coalitions { uint64_t ids[2],reserved[3]; };
struct mw_coalition_count { uint64_t id; uint32_t type,tasks; };
typedef int (*mw_query)(int32_t,int32_t,uint32_t,uint32_t,uint64_t,uint64_t,void *,int32_t);
typedef int (*mw_signal)(audit_token_t *,int);
typedef int (*mw_coalition_list)(int,int,void *,int);

/* A retained watch binds a live process before any requested effect. */
struct mw_member { pid_t pid; uint64_t unique; int watch; int terminal; int signal_attempted; struct mw_member *next; };
/* Partial state stays allocated and callable after every failed acquisition. */
struct mw_owner {
 int listener,control,stdio[3],bootstrapped,bootstrap_attempted,payload_created,socket_bound,plist_created,released,root_waited,root_status,closed;
 pid_t anchor,root; uint64_t anchor_unique,coalition; uid_t uid; gid_t gid;
 char directory[PATH_MAX],socket_path[104],plist[PATH_MAX],payload[PATH_MAX],label[128],service[160];
 mw_query query; mw_signal signal_process; mw_coalition_list list_coalitions;
 struct mw_member *members;
};

/* Transfer exactly size bytes; a short peer close remains a failed operation. */
static int mw_io(int fd,void *buffer,size_t size,int writing) {
 unsigned char *p=buffer;
 while(size){ssize_t n=writing?write(fd,p,size):read(fd,p,size);if(n<0 && errno==EINTR)continue;if(n<=0)return n==0?EPIPE:errno;p+=n;size-=(size_t)n;}
 return 0;
}

/* Query a flavor only while the kernel still holds the expected unique process. */
static int mw_read(struct mw_owner *owner,pid_t pid,uint64_t unique,int flavor,void *out,int bytes) {
 errno=0;int n=owner->query(2,pid,(uint32_t)flavor,2,unique,0,out,bytes);
 return n==bytes?0:(errno?errno:EIO);
}

/* Read executable vnode identity from a kernel mapped region, never stat(path). */
static int mw_executable(struct mw_owner *owner,pid_t pid,uint64_t unique,uint64_t *device,uint64_t *inode) {
 char executable[PROC_PIDPATHINFO_MAXSIZE];memset(executable,0,sizeof executable);
 int n=owner->query(2,pid,PROC_PIDPATHINFO,2,unique,0,executable,sizeof executable);
 if(n<0)return errno?errno:EIO;
 if(executable[0]==0 || memchr(executable,0,sizeof executable)==NULL)return EIO;
 uint64_t address=0;
 for(unsigned int i=0;i<65536;i++){
  struct proc_regionwithpathinfo region;memset(&region,0,sizeof region);
  errno=0;n=owner->query(2,pid,PROC_PIDREGIONPATHINFO,2,unique,address,&region,sizeof region);
  /* This flavor returns EINVAL when the kernel cannot produce a mapped region.
   * Reject incomplete image inventory; other call failures retain their errno. */
  if(n!=(int)sizeof region){int error=errno?errno:EIO;return n<0 && error==EINVAL?-3:error;}
  if((region.prp_prinfo.pri_protection&VM_PROT_EXECUTE)!=0 && strcmp(region.prp_vip.vip_path,executable)==0){
   *device=region.prp_vip.vip_vi.vi_stat.vst_dev;*inode=region.prp_vip.vip_vi.vi_stat.vst_ino;
   return *inode?0:EIO;
  }
  uint64_t next=region.prp_prinfo.pri_address+region.prp_prinfo.pri_size;
  if(next<=address)return EOVERFLOW;address=next;
 }
 return EOVERFLOW;
}

/* Snapshot layout: PID, unique, version, coalition, PPID, birth sec/usec,
 * effective/real/saved UID, effective/real/saved GID, executable dev/inode,
 * original parent version, and both executable UUID halves. */
static int mw_identity(struct mw_owner *owner,pid_t pid,uint64_t out[18]) {
 struct mw_unique unique,before_after;struct mw_coalitions coalition;struct proc_bsdinfo bsd;
 int n=proc_pidinfo(pid,17,0,&unique,sizeof unique);if(n!=(int)sizeof unique)return errno?errno:ESRCH;
 int error=mw_read(owner,pid,unique.unique,20,&coalition,sizeof coalition);
 if(!error)error=mw_read(owner,pid,unique.unique,PROC_PIDTBSDINFO,&bsd,sizeof bsd);
 uint64_t device=0,inode=0;if(!error)error=mw_executable(owner,pid,unique.unique,&device,&inode);
 /* An exec or exit can invalidate an intermediate query before its trailing
  * image check. Correlate even failed observations to this exact incarnation;
  * stable-image query failures remain failures, never empty measurements. */
 int after_error=mw_read(owner,pid,unique.unique,17,&before_after,sizeof before_after);
 if(after_error==ESRCH)return ESRCH;
 if(!after_error && (unique.version!=before_after.version || memcmp(unique.uuid,before_after.uuid,16)!=0))return EAGAIN;
 if(error)return error;if(after_error)return after_error;
 if(unique.version!=before_after.version || memcmp(unique.uuid,before_after.uuid,16)!=0)return EAGAIN;
 out[0]=(uint64_t)pid;out[1]=unique.unique;out[2]=(uint32_t)unique.version;out[3]=coalition.ids[0];out[4]=bsd.pbi_ppid;
 out[5]=bsd.pbi_start_tvsec;out[6]=bsd.pbi_start_tvusec;out[7]=bsd.pbi_uid;out[8]=bsd.pbi_ruid;out[9]=bsd.pbi_svuid;
 out[10]=bsd.pbi_gid;out[11]=bsd.pbi_rgid;out[12]=bsd.pbi_svgid;out[13]=device;out[14]=inode;out[15]=(uint32_t)unique.original_parent_version;
 memcpy(out+16,unique.uuid,16);return 0;
}

/* Retain one exact exit watch before attaching or controlling an incarnation. */
static int mw_watch(struct mw_owner *owner,pid_t pid,uint64_t unique) {
 for(struct mw_member *m=owner->members;m;m=m->next)if(m->pid==pid && m->unique==unique)return 0;
 struct mw_member *member=calloc(1,sizeof *member);if(!member)return ENOMEM;
 member->pid=pid;member->unique=unique;member->watch=kqueue();if(member->watch<0){int e=errno;free(member);return e;}
 struct kevent change;EV_SET(&change,pid,EVFILT_PROC,EV_ADD|EV_ENABLE|EV_ONESHOT,NOTE_EXIT|NOTE_EXITSTATUS,0,NULL);
 if(kevent(member->watch,&change,1,NULL,0,NULL)<0){int e=errno;close(member->watch);free(member);return e;}
 struct mw_unique after;int e=mw_read(owner,pid,unique,17,&after,sizeof after);
 if(e){close(member->watch);free(member);return e;}
 member->next=owner->members;owner->members=member;return 0;
}

/* Authenticate a live workload image and retain its exit watch. */
int mw_snapshot(void *handle,int pid,uint64_t out[18]) {
 struct mw_owner *owner=handle;if(!owner || owner->closed || pid<=0)return EINVAL;
 struct mw_unique selected;struct mw_coalitions membership;
 int n=proc_pidinfo(pid,17,0,&selected,sizeof selected);if(n!=(int)sizeof selected)return errno?errno:ESRCH;
 int error=mw_read(owner,pid,selected.unique,20,&membership,sizeof membership);if(error)return error;
 if(membership.ids[0]!=owner->coalition || pid==owner->anchor)return EPERM;
 error=mw_identity(owner,pid,out);if(error)return error;
 if(out[1]!=selected.unique)return ESRCH;
 if(out[3]!=owner->coalition || pid==owner->anchor)return EPERM;
 if(out[7]!=owner->uid || out[8]!=owner->uid || out[9]!=owner->uid || out[10]!=owner->gid || out[11]!=owner->gid || out[12]!=owner->gid)return EPERM;
 return mw_watch(owner,pid,out[1]);
}

/* Execute the trusted launchctl with explicit arguments and no inherited environment. */
static int mw_launchctl(const char *operation,const char *subject,const char *path) {
 pid_t child;char *args[5]={"/bin/launchctl",(char *)operation,(char *)subject,(char *)path,NULL};
 if(!path)args[3]=NULL;
 char *environment[]={"PATH=/usr/bin:/bin:/usr/sbin:/sbin",NULL};
 int error=posix_spawn(&child,args[0],NULL,NULL,args,environment);if(error)return error;
 int status;while(waitpid(child,&status,0)<0){if(errno!=EINTR)return errno;}
 return WIFEXITED(status) && WEXITSTATUS(status)==0?0:EIO;
}

/* Serialize the launchd property list with its platform serializer. */
static int mw_plist(struct mw_owner *owner,const char *helper) {
 CFStringRef label=CFStringCreateWithCString(NULL,owner->label,kCFStringEncodingUTF8);
 CFStringRef program=CFStringCreateWithCString(NULL,helper,kCFStringEncodingUTF8);
 CFStringRef directory=CFStringCreateWithCString(NULL,owner->directory,kCFStringEncodingUTF8);
 if(!label || !program || !directory){if(label)CFRelease(label);if(program)CFRelease(program);if(directory)CFRelease(directory);return ENOMEM;}
 const void *arguments_values[]={program,CFSTR("--anchor"),directory};
 CFArrayRef arguments=CFArrayCreate(NULL,arguments_values,3,&kCFTypeArrayCallBacks);
 const void *keys[]={CFSTR("Label"),CFSTR("ProgramArguments"),CFSTR("RunAtLoad"),CFSTR("KeepAlive")};
 const void *values[]={label,arguments,kCFBooleanTrue,kCFBooleanFalse};
 CFDictionaryRef dictionary=arguments?CFDictionaryCreate(NULL,keys,values,4,&kCFTypeDictionaryKeyCallBacks,&kCFTypeDictionaryValueCallBacks):NULL;
 CFDataRef data=dictionary?CFPropertyListCreateData(NULL,dictionary,kCFPropertyListXMLFormat_v1_0,0,NULL):NULL;
 int result=ENOMEM;
 if(data){int fd=open(owner->plist,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW,0600);if(fd<0)result=errno;else{owner->plist_created=1;result=mw_io(fd,(void *)CFDataGetBytePtr(data),(size_t)CFDataGetLength(data),1);if(close(fd)<0 && result==0)result=errno;}}
 if(data)CFRelease(data);if(dictionary)CFRelease(dictionary);if(arguments)CFRelease(arguments);CFRelease(directory);CFRelease(program);CFRelease(label);return result;
}

/* Send only duplicated original standard descriptors through the authenticated socket. */
static int mw_send_streams(struct mw_owner *owner) {
 char value='S';struct iovec vector={&value,1};char ancillary[CMSG_SPACE(3*sizeof(int))];memset(ancillary,0,sizeof ancillary);
 struct msghdr message;memset(&message,0,sizeof message);message.msg_iov=&vector;message.msg_iovlen=1;message.msg_control=ancillary;message.msg_controllen=sizeof ancillary;
 struct cmsghdr *header=CMSG_FIRSTHDR(&message);header->cmsg_level=SOL_SOCKET;header->cmsg_type=SCM_RIGHTS;header->cmsg_len=CMSG_LEN(3*sizeof(int));memcpy(CMSG_DATA(header),owner->stdio,3*sizeof(int));
 ssize_t sent=sendmsg(owner->control,&message,0);if(sent<0)return errno;return sent==1?0:EIO;
}

/* Create one exclusive native owner. The output handle is retained before the first
 * external effect, including when a later stage returns an error. Payload is an
 * application-owned binary sequence: uid/gid, argv count and UTF-8 NUL-separated
 * argv, environment count and entries, then one absolute working-directory entry. Each
 * string includes a native-endian uint32 length and its terminal NUL. */
int mw_open(const char *directory,const char *helper,const void *payload,int payload_bytes,int uid,int gid,const int streams[3],void **output) {
 if(!directory || !helper || !payload || payload_bytes<=0 || !streams || !output || uid<0 || gid<0 || geteuid()!=0)return EINVAL;
 for(int i=0;i<3;i++)if(streams[i]<0)return EBADF;
 struct mw_owner *owner=calloc(1,sizeof *owner);if(!owner)return ENOMEM;
 owner->listener=-1;owner->control=-1;for(int i=0;i<3;i++)owner->stdio[i]=-1;*output=owner;owner->uid=(uid_t)uid;owner->gid=(gid_t)gid;
 owner->query=(mw_query)dlsym(RTLD_DEFAULT,"__proc_info_extended_id");owner->signal_process=(mw_signal)dlsym(RTLD_DEFAULT,"proc_signal_with_audittoken");owner->list_coalitions=(mw_coalition_list)dlsym(RTLD_DEFAULT,"proc_listcoalitions");
 if(!owner->query || !owner->signal_process || !owner->list_coalitions)return ENOTSUP;
 if(strlen(directory)>=sizeof owner->directory || strlen(directory)+13>=sizeof owner->socket_path)return ENAMETOOLONG;
 strcpy(owner->directory,directory);snprintf(owner->socket_path,sizeof owner->socket_path,"%s/control.sock",directory);snprintf(owner->plist,sizeof owner->plist,"%s/service.plist",directory);snprintf(owner->payload,sizeof owner->payload,"%s/command.bin",directory);
 struct stat root,executable;if(lstat(directory,&root)<0)return errno;if(!S_ISDIR(root.st_mode) || root.st_uid!=0 || (root.st_mode&077)!=0)return EPERM;
 if(lstat(helper,&executable)<0)return errno;if(!S_ISREG(executable.st_mode) || executable.st_uid!=0 || (executable.st_mode&022)!=0)return EPERM;
 snprintf(owner->label,sizeof owner->label,"com.holdmyspot.codexunleashed.workload.%llu.%llu",(unsigned long long)root.st_dev,(unsigned long long)root.st_ino);snprintf(owner->service,sizeof owner->service,"system/%s",owner->label);
 for(int i=0;i<3;i++){owner->stdio[i]=fcntl(streams[i],F_DUPFD_CLOEXEC,3);if(owner->stdio[i]<0)return errno;}
 int fd=open(owner->payload,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW,0600);if(fd<0)return errno;owner->payload_created=1;int error=mw_io(fd,(void *)payload,(size_t)payload_bytes,1);if(close(fd)<0 && !error)error=errno;if(error)return error;
 owner->listener=socket(AF_UNIX,SOCK_STREAM,0);if(owner->listener<0)return errno;
 struct sockaddr_un address;memset(&address,0,sizeof address);address.sun_family=AF_UNIX;strcpy(address.sun_path,owner->socket_path);
 if(bind(owner->listener,(struct sockaddr *)&address,sizeof address)<0)return errno;owner->socket_bound=1;
 if(chmod(owner->socket_path,0600)<0 || listen(owner->listener,1)<0)return errno;
 error=mw_plist(owner,helper);if(error)return error;
 owner->bootstrap_attempted=1;error=mw_launchctl("bootstrap","system",owner->plist);if(error)return error;owner->bootstrapped=1;
 struct pollfd ready={owner->listener,POLLIN,0};int n;do{n=poll(&ready,1,30000);}while(n<0 && errno==EINTR);if(n<=0)return n==0?ETIMEDOUT:errno;
 owner->control=accept(owner->listener,NULL,NULL);if(owner->control<0)return errno;
 int no_sigpipe=1;if(setsockopt(owner->control,SOL_SOCKET,SO_NOSIGPIPE,&no_sigpipe,sizeof no_sigpipe)<0)return errno;
 struct timeval timeout={30,0};if(setsockopt(owner->control,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof timeout)<0 || setsockopt(owner->control,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof timeout)<0)return errno;
 socklen_t length=sizeof owner->anchor;if(getsockopt(owner->control,SOL_LOCAL,LOCAL_PEERPID,&owner->anchor,&length)<0)return errno;if(length!=sizeof owner->anchor)return EIO;
 uid_t peer_uid;gid_t peer_gid;if(getpeereid(owner->control,&peer_uid,&peer_gid)<0 || peer_uid!=0)return EPERM;
 uint64_t anchor[18],self[18];error=mw_identity(owner,owner->anchor,anchor);if(error)return error;error=mw_identity(owner,getpid(),self);if(error)return error;
 if(anchor[13]!=(uint64_t)(uint32_t)executable.st_dev || anchor[14]!=(uint64_t)executable.st_ino || anchor[3]==self[3])return EPERM;
 owner->anchor_unique=anchor[1];owner->coalition=anchor[3];
 error=mw_send_streams(owner);if(error)return error;int32_t root_pid;error=mw_io(owner->control,&root_pid,sizeof root_pid,0);if(error)return error;if(root_pid<=0)return EIO;owner->root=root_pid;
 uint64_t root_identity[18];return mw_snapshot(owner,owner->root,root_identity);
}

/* Return the independently authenticated original held root. */
int mw_root(void *handle,uint64_t out[18]) { struct mw_owner *owner=handle;return owner && owner->root>0?mw_snapshot(owner,owner->root,out):EINVAL; }

/* Release the original byte gate once; consuming the opportunity precedes I/O. */
int mw_release(void *handle) {
 struct mw_owner *owner=handle;if(!owner || owner->released || owner->closed)return EINVAL;owner->released=1;
 uint64_t out[18];int error=mw_snapshot(owner,owner->root,out);if(error)return error;
 char command='R';error=mw_io(owner->control,&command,1,1);if(error)return error;int32_t result;error=mw_io(owner->control,&result,sizeof result,0);return error?error:result;
}

/* Validate the retained non-forking anchor before using coalition accounting. */
static int mw_anchor_valid(struct mw_owner *owner) {
 struct mw_coalitions coalitions;int error=mw_read(owner,owner->anchor,owner->anchor_unique,20,&coalitions,sizeof coalitions);
 return error?error:(coalitions.ids[0]==owner->coalition?0:EPERM);
}

/* Return a kernel resource-coalition task count; inventory truncation is a failure. */
static int mw_task_count(struct mw_owner *owner,int *count) {
 struct mw_coalition_count *rows=calloc(65536,sizeof *rows);if(!rows)return ENOMEM;
 int capacity=65536*(int)sizeof *rows,n=owner->list_coalitions(1,0,rows,capacity),error=ENOENT;
 if(n<=0 || n>=capacity || n%(int)sizeof *rows){free(rows);return EOVERFLOW;}
 for(int i=0;i<n/(int)sizeof *rows;i++)if(rows[i].id==owner->coalition && rows[i].type==0){*count=(int)rows[i].tasks;error=0;break;}
 free(rows);return error;
}

/* Sort full rows by exact incarnation without reducing their retained identity fields. */
static int mw_row_order(const void *left,const void *right) {
 const uint64_t *a=left,*b=right;
 for(int i=0;i<3;i++){if(a[i]<b[i])return -1;if(a[i]>b[i])return 1;}return 0;
}

/* Collect the current authenticated cohort, excluding only its non-forking anchor.
 * Task counts detect an incomplete observation; they never establish terminal authority. */
static int mw_collect(struct mw_owner *owner,uint64_t *rows,int capacity,int *count) {
 int error=mw_anchor_valid(owner);if(error)return error;
 int before=0;error=mw_task_count(owner,&before);if(error)return error;
 if(before<=0)return EIO;if(before-1>capacity)return EOVERFLOW;
 pid_t *pids=calloc(32768,sizeof *pids);if(!pids)return ENOMEM;
 int bytes=proc_listpids(PROC_ALL_PIDS,0,pids,32768*(int)sizeof *pids);
 if(bytes<=0 || bytes>=32768*(int)sizeof *pids || bytes%(int)sizeof *pids){free(pids);return EOVERFLOW;}
 int found=0;
 for(int i=0;i<bytes/(int)sizeof *pids;i++){
  pid_t pid=pids[i];if(pid<=0 || pid==owner->anchor)continue;
  struct mw_unique unique;errno=0;int n=proc_pidinfo(pid,17,0,&unique,sizeof unique);
  if(n!=(int)sizeof unique){if(errno==EACCES || errno==EPERM){free(pids);return errno;}continue;}
  struct mw_coalitions membership;error=mw_read(owner,pid,unique.unique,20,&membership,sizeof membership);
  if(error){if(error==ESRCH || error==ENOENT)continue;free(pids);return error;}
  if(membership.ids[0]!=owner->coalition)continue;
  if(found==capacity){free(pids);return EOVERFLOW;}
  error=mw_snapshot(owner,pid,rows+19*found);
  if(error){free(pids);return error==ESRCH || error==ENOENT?EAGAIN:error;}
  if(rows[19*found+1]!=unique.unique){free(pids);return EAGAIN;}
  found++;
 }
 free(pids);int after=0;error=mw_task_count(owner,&after);if(error)return error;
 error=mw_anchor_valid(owner);if(error)return error;
 if(before!=after)return EAGAIN;
 if(found!=after-1)return -3;
 qsort(rows,(size_t)found,19*sizeof(uint64_t),mw_row_order);*count=found;return 0;
}

/* Return full authenticated image rows plus current native resident bytes at field18.
 * A second independent cohort observation rejects membership or image churn. A
 * successful empty observation is a measured inventory, never a completion certificate.
 * Return -2 only for independently observed cohort/image churn and -3 only for
 * task-count disagreement or unavailable executable-region inventory; no partial rows are returned.
 * Other failures remain errno values. */
int mw_inventory(void *handle,uint64_t *rows,int capacity,int *count) {
 struct mw_owner *owner=handle;if(!owner || owner->closed || !rows || !count || capacity<=0 || capacity>32768)return EINVAL;
 *count=0;uint64_t *before=calloc((size_t)capacity,19*sizeof *before),*after=calloc((size_t)capacity,19*sizeof *after);
 if(!before || !after){free(before);free(after);return ENOMEM;}
 int first_count=0,second_count=0,error=mw_collect(owner,before,capacity,&first_count);
 if(error)goto done;
 for(int i=0;i<first_count;i++){
  uint64_t *row=before+19*i;struct proc_taskinfo task;
  error=mw_read(owner,(pid_t)row[0],row[1],PROC_PIDTASKINFO,&task,sizeof task);
  if(error){if(error==ESRCH || error==ENOENT)error=EAGAIN;goto done;}
  row[18]=task.pti_resident_size;
 }
 error=mw_collect(owner,after,capacity,&second_count);if(error)goto done;
 if(first_count!=second_count){error=EAGAIN;goto done;}
 for(int i=0;i<first_count;i++)if(memcmp(before+19*i,after+19*i,18*sizeof(uint64_t))!=0){error=EAGAIN;goto done;}
 memcpy(rows,before,(size_t)first_count*19*sizeof(uint64_t));*count=first_count;
 done:free(before);free(after);return error==EAGAIN?-2:error;
}

/* Discover exact live members without a primary-event PID oracle and signal each
 * incarnation through a genuine kernel audit token after watch/revalidation. */
int mw_cancel(void *handle) {
 struct mw_owner *owner=handle;if(!owner || owner->closed)return EINVAL;
 int error=mw_anchor_valid(owner);if(error)return error;
 pid_t *pids=calloc(32768,sizeof *pids);if(!pids)return ENOMEM;
 int bytes=proc_listpids(PROC_ALL_PIDS,0,pids,32768*(int)sizeof *pids);if(bytes<=0 || bytes>=32768*(int)sizeof *pids || bytes%(int)sizeof *pids){free(pids);return EOVERFLOW;}
 for(int i=0;i<bytes/(int)sizeof *pids;i++){
  pid_t pid=pids[i];if(pid<=0 || pid==owner->anchor)continue;
  struct mw_unique unique;int n=proc_pidinfo(pid,17,0,&unique,sizeof unique);if(n!=(int)sizeof unique)continue;
  struct mw_coalitions coalitions;error=mw_read(owner,pid,unique.unique,20,&coalitions,sizeof coalitions);if(error || coalitions.ids[0]!=owner->coalition)continue;
  error=mw_watch(owner,pid,unique.unique);if(error==ESRCH)continue;if(error){free(pids);return error;}
  struct mw_member *member=owner->members;while(member && (member->pid!=pid || member->unique!=unique.unique))member=member->next;
  if(!member){free(pids);return EIO;}if(member->signal_attempted)continue;
  mach_port_t task=MACH_PORT_NULL;kern_return_t kr=task_for_pid(mach_task_self(),pid,&task);if(kr!=KERN_SUCCESS){free(pids);return EACCES;}
  audit_token_t token;mach_msg_type_number_t count=TASK_AUDIT_TOKEN_COUNT;kr=task_info(task,TASK_AUDIT_TOKEN,(task_info_t)&token,&count);kern_return_t released=mach_port_deallocate(mach_task_self(),task);
  if(kr!=KERN_SUCCESS || released!=KERN_SUCCESS || count!=TASK_AUDIT_TOKEN_COUNT || token.val[5]!=(uint32_t)pid){free(pids);return EIO;}
  struct mw_unique current;n=owner->query(2,pid,17,1,token.val[7],0,&current,sizeof current);if(n!=(int)sizeof current || current.unique!=unique.unique)continue;
  error=mw_read(owner,pid,unique.unique,20,&coalitions,sizeof coalitions);if(error || coalitions.ids[0]!=owner->coalition)continue;
  member->signal_attempted=1;error=owner->signal_process(&token,SIGKILL);if(error && error!=ESRCH){free(pids);return error;}
 }
 free(pids);return 0;
}

/* Compose a stable no-user-producer cut, retained native exits and genuine root
 * wait. Returns -1 while a required observation is still unresolved; positive
 * errno values report failures and zero returns the genuine original result. */
int mw_terminal(void *handle,int *result) {
 struct mw_owner *owner=handle;if(!owner || !result || owner->closed)return EINVAL;
 int error=mw_anchor_valid(owner);if(error)return error;int tasks=0;error=mw_task_count(owner,&tasks);if(error)return error;if(tasks!=1)return -1;
 for(struct mw_member *member=owner->members;member;member=member->next){
  if(member->terminal)continue;struct kevent event;struct timespec no_wait={0,0};int n=kevent(member->watch,NULL,0,&event,1,&no_wait);
  if(n<0)return errno;if(n==0)return -1;if(event.ident!=(uintptr_t)member->pid || event.filter!=EVFILT_PROC || (event.fflags&NOTE_EXIT)==0 || (event.flags&EV_ERROR)!=0)return EIO;member->terminal=1;
 }
 if(!owner->root_waited){char command='W';error=mw_io(owner->control,&command,1,1);if(error)return error;int32_t status;error=mw_io(owner->control,&status,sizeof status,0);if(error)return error;if(status==INT_MIN)return -1;owner->root_status=status;owner->root_waited=1;}
 error=mw_anchor_valid(owner);if(error)return error;error=mw_task_count(owner,&tasks);if(error)return error;if(tasks!=1)return -1;
 int status=owner->root_status;if(WIFEXITED(status))*result=WEXITSTATUS(status);else if(WIFSIGNALED(status))*result=128+WTERMSIG(status);else return EIO;return 0;
}

/* Release resources only after their native users have independently terminated. */
static void mw_destroy(struct mw_owner *owner) {
 owner->bootstrapped=0;owner->closed=1;if(owner->control>=0)close(owner->control);if(owner->listener>=0)close(owner->listener);for(int i=0;i<3;i++)if(owner->stdio[i]>=0)close(owner->stdio[i]);
 for(struct mw_member *m=owner->members;m;){struct mw_member *next=m->next;close(m->watch);free(m);m=next;}
 if(owner->socket_bound)unlink(owner->socket_path);if(owner->plist_created)unlink(owner->plist);if(owner->payload_created)unlink(owner->payload);free(owner);
}

/* Before bootstrap, release only files this owner created. After an accepted
 * anchor but before stream handoff, no workload can have forked: remove the exact
 * service and observe its native exit. Uncertain bootstrap retains authority. */
static int mw_close_unstarted(struct mw_owner *owner) {
 if(owner->root!=0 || owner->coalition!=0)return EBUSY;
 if(!owner->bootstrap_attempted){mw_destroy(owner);return 0;}
 if(!owner->bootstrapped || owner->anchor<=0)return EBUSY;
 struct mw_unique before,after;int n=proc_pidinfo(owner->anchor,17,0,&before,sizeof before);if(n!=(int)sizeof before)return errno?errno:ESRCH;
 int watch=kqueue();if(watch<0)return errno;struct kevent change;EV_SET(&change,owner->anchor,EVFILT_PROC,EV_ADD|EV_ENABLE|EV_ONESHOT,NOTE_EXIT|NOTE_EXITSTATUS,0,NULL);
 if(kevent(watch,&change,1,NULL,0,NULL)<0){int error=errno;close(watch);return error;}
 int error=mw_read(owner,owner->anchor,before.unique,17,&after,sizeof after);if(error){close(watch);return error;}
 error=mw_launchctl("bootout",owner->service,NULL);if(error){close(watch);return error;}
 struct kevent terminal;struct timespec timeout={30,0};n=kevent(watch,NULL,0,&terminal,1,&timeout);close(watch);
 if(n!=1 || terminal.ident!=(uintptr_t)owner->anchor || (terminal.fflags&NOTE_EXIT)==0)return n<0?errno:ETIMEDOUT;
 mw_destroy(owner);return 0;
}

/* Close images first at the Java boundary. This operation then observes the
 * anchor's own native exit, removes the exact service and releases native handles. */
int mw_close(void *handle) {
 struct mw_owner *owner=handle;if(!owner || owner->closed)return EINVAL;
 if(owner->root==0 && owner->coalition==0)return mw_close_unstarted(owner);
 int result,error=mw_terminal(owner,&result);if(error)return error;
 int watch=kqueue();if(watch<0)return errno;struct kevent change;EV_SET(&change,owner->anchor,EVFILT_PROC,EV_ADD|EV_ENABLE|EV_ONESHOT,NOTE_EXIT|NOTE_EXITSTATUS,0,NULL);
 if(kevent(watch,&change,1,NULL,0,NULL)<0){error=errno;close(watch);return error;}
 error=mw_anchor_valid(owner);if(error){close(watch);return error;}char command='X';error=mw_io(owner->control,&command,1,1);if(error){close(watch);return error;}
 int32_t response;error=mw_io(owner->control,&response,sizeof response,0);if(error || response){close(watch);return error?error:response;}
 struct kevent terminal;struct timespec timeout={30,0};int n=kevent(watch,NULL,0,&terminal,1,&timeout);close(watch);
 if(n!=1 || terminal.ident!=(uintptr_t)owner->anchor || (terminal.fflags&NOTE_EXIT)==0)return n<0?errno:ETIMEDOUT;
 error=mw_launchctl("bootout",owner->service,NULL);if(error)return error;
 mw_destroy(owner);return 0;
}

#ifdef MW_ANCHOR
/* Receive exactly three descriptors from the authenticated invoking process. */
static int mw_receive_streams(int socket_fd,int streams[3]) {
 char value=0,ancillary[CMSG_SPACE(3*sizeof(int))];struct iovec vector={&value,1};struct msghdr message;memset(&message,0,sizeof message);memset(ancillary,0,sizeof ancillary);
 message.msg_iov=&vector;message.msg_iovlen=1;message.msg_control=ancillary;message.msg_controllen=sizeof ancillary;
 ssize_t n=recvmsg(socket_fd,&message,0);if(n!=1 || value!='S' || (message.msg_flags&(MSG_CTRUNC|MSG_TRUNC))!=0)return EIO;
 struct cmsghdr *header=CMSG_FIRSTHDR(&message);if(!header || header->cmsg_level!=SOL_SOCKET || header->cmsg_type!=SCM_RIGHTS || header->cmsg_len!=CMSG_LEN(3*sizeof(int)))return EIO;
 memcpy(streams,CMSG_DATA(header),3*sizeof(int));return 0;
}

/* Release the parent's or a failed decoder's explicitly retained vector. */
static void mw_free_vector(char **values) {
 if(!values)return;for(size_t i=0;values[i];i++)free(values[i]);free(values);
}

/* Decode only the application's owned binary argv/environment representation. */
static int mw_read_vector(int fd,char ***output) {
 uint32_t count;int error=mw_io(fd,&count,sizeof count,0);if(error)return error;if(count>65536)return EOVERFLOW;
 char **values=calloc((size_t)count+1,sizeof *values);if(!values)return ENOMEM;*output=values;
 for(uint32_t i=0;i<count;i++){
  uint32_t bytes;error=mw_io(fd,&bytes,sizeof bytes,0);if(error || bytes==0 || bytes>1024*1024)return error?error:EINVAL;
  values[i]=calloc(bytes,1);if(!values[i])return ENOMEM;error=mw_io(fd,values[i],bytes,0);if(error)return error;
  if(values[i][bytes-1]!=0 || strlen(values[i])+1!=bytes)return EINVAL;
 }
 *output=values;return 0;
}

/* Remain a non-forking launchd anchor after creating exactly one held original
 * root. The root alone receives original streams and executes the requested image. */
int main(int argc,char **argv) {
 if(argc!=3 || strcmp(argv[1],"--anchor")!=0 || geteuid()!=0)return 80;
 struct sigaction ignored;memset(&ignored,0,sizeof ignored);ignored.sa_handler=SIG_IGN;sigemptyset(&ignored.sa_mask);if(sigaction(SIGPIPE,&ignored,NULL)<0)return 80;
 char socket_path[104],payload[PATH_MAX];if(strlen(argv[2])+13>=sizeof socket_path)return 81;
 struct stat directory;if(lstat(argv[2],&directory)<0 || !S_ISDIR(directory.st_mode) || directory.st_uid!=0 || (directory.st_mode&077)!=0)return 82;
 snprintf(socket_path,sizeof socket_path,"%s/control.sock",argv[2]);snprintf(payload,sizeof payload,"%s/command.bin",argv[2]);
 int control=socket(AF_UNIX,SOCK_STREAM,0);if(control<0)return 83;struct sockaddr_un address;memset(&address,0,sizeof address);address.sun_family=AF_UNIX;strcpy(address.sun_path,socket_path);
 if(connect(control,(struct sockaddr *)&address,sizeof address)<0)return 84;uid_t peer_uid;gid_t peer_gid;if(getpeereid(control,&peer_uid,&peer_gid)<0 || peer_uid!=0)return 85;
 int streams[3];if(mw_receive_streams(control,streams))return 86;
 int input=open(payload,O_RDONLY|O_NOFOLLOW);if(input<0)return 87;uint32_t identity[2];if(mw_io(input,identity,sizeof identity,0))return 88;
 char **command=NULL,**environment=NULL,**working_directory=NULL;
 if(mw_read_vector(input,&command) || mw_read_vector(input,&environment) || mw_read_vector(input,&working_directory) || !command || !command[0] || !working_directory || !working_directory[0] || working_directory[1] || working_directory[0][0]!='/'){
  mw_free_vector(command);mw_free_vector(environment);mw_free_vector(working_directory);return 89;
 }
 char trailing;ssize_t remaining;do{remaining=read(input,&trailing,1);}while(remaining<0 && errno==EINTR);
 if(remaining!=0){mw_free_vector(command);mw_free_vector(environment);mw_free_vector(working_directory);return 89;}close(input);
 int gate[2],ready[2];if(pipe(gate)<0 || pipe(ready)<0){mw_free_vector(command);mw_free_vector(environment);mw_free_vector(working_directory);return 90;}pid_t root=fork();if(root<0){mw_free_vector(command);mw_free_vector(environment);mw_free_vector(working_directory);return 91;}
 if(root==0){
  close(control);close(gate[1]);close(ready[0]);
  if(setgroups(0,NULL)<0 || setgid((gid_t)identity[1])<0 || setuid((uid_t)identity[0])<0 || chdir(working_directory[0])<0)_exit(92);
  for(int i=0;i<3;i++)if(dup2(streams[i],i)<0)_exit(93);
  for(int i=0;i<3;i++)if(streams[i]>2)close(streams[i]);
  char initialized='I';if(mw_io(ready[1],&initialized,1,1))_exit(94);close(ready[1]);
  char release;if(mw_io(gate[0],&release,1,0) || release!='R')_exit(94);close(gate[0]);
  struct sigaction normal;memset(&normal,0,sizeof normal);normal.sa_handler=SIG_DFL;sigemptyset(&normal.sa_mask);if(sigaction(SIGPIPE,&normal,NULL)<0)_exit(94);
  execve(command[0],command,environment);_exit(127);
 }
 mw_free_vector(command);mw_free_vector(environment);mw_free_vector(working_directory);
 close(gate[0]);close(ready[1]);for(int i=0;i<3;i++)close(streams[i]);char initialized;if(mw_io(ready[0],&initialized,1,0) || initialized!='I')return 95;close(ready[0]);int32_t root_pid=root;if(mw_io(control,&root_pid,sizeof root_pid,1))return 95;
 int released=0,waited=0,status=0;
 for(;;){
  char request;if(mw_io(control,&request,1,0))return 96;int32_t response=0;
  if(request=='R'){
   if(released)response=EALREADY;else{released=1;char release='R';response=mw_io(gate[1],&release,1,1);close(gate[1]);}
  }else if(request=='W'){
   if(!waited){pid_t result=waitpid(root,&status,WNOHANG);if(result<0)response=INT_MIN;else if(result==root)waited=1;}
   response=waited?status:INT_MIN;
  }else if(request=='X'){
   if(!waited)response=EBUSY;
   if(mw_io(control,&response,sizeof response,1))return 97;
   if(!response){close(control);return 0;}continue;
  }else response=EINVAL;
  if(mw_io(control,&response,sizeof response,1))return 98;
 }
}
#endif
