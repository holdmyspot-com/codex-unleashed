#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <sys/prctl.h>
#include <time.h>
#ifdef LAUNCH_FAULTS
#include <dlfcn.h>
#endif
#include <unistd.h>
extern char **environ;
#ifdef LAUNCH_FAULTS
/* Fault injection is confined to a separately launched test JVM via an owned LD_PRELOAD library. */
int posix_spawn_file_actions_destroy(posix_spawn_file_actions_t *actions) {
 int (*operation)(posix_spawn_file_actions_t *)=dlsym(RTLD_NEXT,"posix_spawn_file_actions_destroy");
 int result=operation(actions);if(getenv("QUIET_FAIL_DESTROY"))return EINVAL;return result;
}
int kill(pid_t pid,int number) {
 static int denied=0;
 int (*operation)(pid_t,int)=dlsym(RTLD_NEXT,"kill");
 if(pid<0 && number==SIGTERM && getenv("QUIET_DENY_ONCE") && !denied){denied=1;errno=EPERM;return -1;}
 return operation(pid,number);
}
#endif
/* Test fixture only: the native child exposes actual OS state at the launcher boundary. */
static void cooperative_term(int number) { (void)number; }
static int tree_ready(const char *mode, const char *path) {
 int channel[2];if(pipe(channel))return 80;
 struct sigaction action;memset(&action,0,sizeof action);action.sa_handler=cooperative_term;sigemptyset(&action.sa_mask);
 if(sigaction(SIGTERM,&action,NULL))return 81;
 pid_t child=fork();if(child<0)return 82;
 if(child==0) {
  close(channel[0]);signal(SIGTERM,SIG_DFL);
  if(write(channel[1],"R",1)!=1){_exit(83);}
  close(channel[1]);
  for(;;)pause();
 }
 close(channel[1]);char ready;if(read(channel[0],&ready,1)!=1)return 84;close(channel[0]);
 FILE *output=fopen(path,"w");if(!output)return 85;
 fprintf(output,"%ld %ld\n",(long)getpid(),(long)child);if(fclose(output))return 86;
 if(!strcmp(mode,"leader-before-child"))return 0;
 int status;while(waitpid(child,&status,0)<0)if(errno!=EINTR)return 87;
 return 0;
}
/* The supervisor adopts/reaps the fixture's orphan by its recorded exact PID, outside the tested session. */
static int supervise(const char *receipt_path,char **command) {
 if(prctl(PR_SET_CHILD_SUBREAPER,1))return 90;
 pid_t driver=fork();if(driver<0)return 91;
 if(driver==0){execv(command[0],command);_exit(92);}
 pid_t root=0,orphan=0;int driver_status=0,driver_done=0,orphan_done=0;
 struct timespec start;clock_gettime(CLOCK_MONOTONIC,&start);
 for(;;) {
  if(!root){FILE *receipt_file=fopen(receipt_path,"r");if(receipt_file){long a,b;if(fscanf(receipt_file,"%ld %ld",&a,&b)==2){root=(pid_t)a;orphan=(pid_t)b;}fclose(receipt_file);}}
  if(orphan>0 && !orphan_done){int status;pid_t result=waitpid(orphan,&status,WNOHANG);if(result==orphan){orphan_done=1;printf("supervisor_reaped=%ld\n",(long)orphan);fflush(stdout);}}
  if(!driver_done){pid_t result=waitpid(driver,&driver_status,WNOHANG);if(result==driver)driver_done=1;}
  if(driver_done && (!root || orphan_done))return WIFEXITED(driver_status)?WEXITSTATUS(driver_status):93;
  struct timespec now;clock_gettime(CLOCK_MONOTONIC,&now);
  if(now.tv_sec-start.tv_sec>35){if(root>0)kill(-root,SIGKILL);kill(driver,SIGKILL);while(waitpid(driver,&driver_status,0)<0 && errno==EINTR){}if(orphan>0)while(waitpid(orphan,&driver_status,0)<0 && errno==EINTR){}return 94;}
  struct timespec pause_time={0,1000000};nanosleep(&pause_time,NULL);
 }
}
static void receipt(void) {
    sigset_t set; char cwd[8192];
    if (sigprocmask(SIG_SETMASK,NULL,&set) || !getcwd(cwd,sizeof cwd)) exit(70);
    printf("pid=%ld\npgid=%ld\nsid=%ld\ncwd=%s\n",(long)getpid(),(long)getpgrp(),(long)getsid(0),cwd);
    printf("probe_environment=%s\n",getenv("LAUNCH_PROOF") ? getenv("LAUNCH_PROOF") : "absent");
    for(int n=1;n<NSIG;n++){struct sigaction a;if(sigaction(n,NULL,&a))continue;
      printf("signal.%d.blocked=%d\nsignal.%d.disposition=%s\n",n,sigismember(&set,n),n,a.sa_handler==SIG_IGN?"ignore":a.sa_handler==SIG_DFL?"default":"handler");}
    printf("fd0=%s\n",fcntl(0,F_GETFD)<0 && errno==EBADF?"closed":"open");
    printf("fd77=%s\n",fcntl(77,F_GETFD)<0 && errno==EBADF?"closed":"open");
    fflush(stdout);
}
int main(int argc,char **argv) {
 const char *basename=strrchr(argv[0],'/');basename=basename?basename+1:argv[0];
 if(!strcmp(basename,"sudo")) {
  signal(SIGTERM,SIG_IGN);const char *path=getenv("QUIET_HELPER_RECEIPT");if(!path)return 98;
  FILE *output=fopen(path,"w");if(!output)return 99;fprintf(output,"%ld\n",(long)getpid());if(fclose(output))return 100;
  for(;;)pause();
 }
 if(argc>=4 && !strcmp(argv[1],"supervise"))return supervise(argv[2],argv+3);
 if(argc>=3 && (!strcmp(argv[1],"tree-ready") || !strcmp(argv[1],"leader-before-child")))return tree_ready(argv[1],argv[2]);
 if(argc>=2 && !strcmp(argv[1],"abi")) {
  printf("attr=%zu/%zu\nactions=%zu/%zu\nset=%zu/%zu\npid=%zu/%zu\nint=%zu\nshort=%zu\npointer=%zu\n",sizeof(posix_spawnattr_t),_Alignof(posix_spawnattr_t),sizeof(posix_spawn_file_actions_t),_Alignof(posix_spawn_file_actions_t),sizeof(sigset_t),_Alignof(sigset_t),sizeof(pid_t),_Alignof(pid_t),sizeof(int),sizeof(short),sizeof(void*));
  printf("SETSID=%d\nSETSIGMASK=%d\nSETSIGDEF=%d\n",POSIX_SPAWN_SETSID,POSIX_SPAWN_SETSIGMASK,POSIX_SPAWN_SETSIGDEF);
  printf("F_GETFD=%d\nEBADF=%d\n",F_GETFD,EBADF);
  printf("SIGINT=%d\nSIGQUIT=%d\nSIGTERM=%d\nSIGPIPE=%d\nSIGXFSZ=%d\nSIGKILL=%d\nENOENT=%d\nENOTDIR=%d\nEINTR=%d\nESRCH=%d\nEPERM=%d\nWNOHANG=%d\n",SIGINT,SIGQUIT,SIGTERM,SIGPIPE,SIGXFSZ,SIGKILL,ENOENT,ENOTDIR,EINTR,ESRCH,EPERM,WNOHANG);
  int exit_status=143<<8,signal_status=SIGTERM;
  printf("ordinary.raw=%d\nordinary.exited=%d\nordinary.code=%d\nsignal.raw=%d\nsignal.signaled=%d\nsignal.number=%d\nsignal.core=%d\n",exit_status,WIFEXITED(exit_status),WEXITSTATUS(exit_status),signal_status,WIFSIGNALED(signal_status),WTERMSIG(signal_status),WCOREDUMP(signal_status)!=0);return 0;
 }
 if(argc>=3 && !strcmp(argv[1],"closed-stdin")) {close(0);execv(argv[2],argv+2);return 95;}
 if(argc>=2 && !strcmp(argv[1],"streams")) {char buffer[128];ssize_t count=read(0,buffer,sizeof buffer);if(count<0)return 96;if(write(1,buffer,(size_t)count)!=count)return 97;fprintf(stderr,"native-stderr\n");return 0;}
 if(argc>=3 && !strcmp(argv[1],"fdwrap")) {
  int fd=open("/dev/null",O_RDONLY);if(fd<0||dup2(fd,77)<0)return 71;if(fd!=77)close(fd);
  execv(argv[2],argv+2);perror("fdwrap execv");return 72;
 }
 if(argc>=4 && (!strcmp(argv[1],"platform") || !strcmp(argv[1],"platform-closed"))) {
  posix_spawnattr_t attr;posix_spawn_file_actions_t actions;sigset_t set;pid_t pid;int status,r;
  if((r=posix_spawnattr_init(&attr)) || (r=posix_spawn_file_actions_init(&actions)))return r;
  sigemptyset(&set);if((r=posix_spawnattr_setsigmask(&attr,&set)))return r;
  int numbers[]={SIGINT,SIGQUIT,SIGTERM,SIGPIPE,SIGXFSZ};for(size_t i=0;i<sizeof(numbers)/sizeof(numbers[0]);i++)sigaddset(&set,numbers[i]);
  if((r=posix_spawnattr_setsigdefault(&attr,&set)) || (r=posix_spawnattr_setflags(&attr,POSIX_SPAWN_SETSID|POSIX_SPAWN_SETSIGMASK|POSIX_SPAWN_SETSIGDEF)) || (r=posix_spawn_file_actions_addchdir_np(&actions,argv[3])) || (r=posix_spawn_file_actions_addclosefrom_np(&actions,3)))return r;
  if(!strcmp(argv[1],"platform-closed") && (r=posix_spawn_file_actions_addclose(&actions,0)))return r;
  char *child[]={argv[2],"receipt",NULL};r=posix_spawn(&pid,argv[2],&actions,&attr,child,environ);
  posix_spawn_file_actions_destroy(&actions);posix_spawnattr_destroy(&attr);if(r){printf("spawn_errno=%d\n",r);return r;}
  while(waitpid(pid,&status,0)<0){if(errno!=EINTR)return 73;}
  printf("raw=%d\nexited=%d\nexit_code=%d\n",status,WIFEXITED(status),WIFEXITED(status)?WEXITSTATUS(status):-1);return 0;
 }
 if(argc>=3 && !strcmp(argv[1],"pause-ready")) {
  if(argc>=4 && !strcmp(argv[3],"ignore-term"))signal(SIGTERM,SIG_IGN);
  char temporary[8192];if(snprintf(temporary,sizeof temporary,"%s.tmp",argv[2])>=(int)sizeof temporary)return 78;
  FILE *ready=fopen(temporary,"w");if(!ready)return 76;
  fprintf(ready,"%ld\n",(long)getpid());if(fclose(ready))return 77;
  if(rename(temporary,argv[2]))return 79;
  for(;;)pause();
 }
 if(argc>=2 && !strcmp(argv[1],"receipt")){receipt();return 0;}
 if(argc>=3 && !strcmp(argv[1],"exit"))return atoi(argv[2]);
 if(argc>=3 && !strcmp(argv[1],"signal")){raise(atoi(argv[2]));return 74;}
 if(argc>=2 && !strcmp(argv[1],"args")){for(int i=2;i<argc;i++){printf("arg.%d=",i-2);for(const unsigned char *s=(const unsigned char *)argv[i];*s;s++)printf("%02x",*s);putchar('\n');}return 0;}
 return 75;
}
